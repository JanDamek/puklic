package dev.puklic.screencast.windows

import co.touchlab.kermit.Logger
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WASAPI loopback audio reader. Captures Float32 stereo @ 48 kHz from the default render
 * endpoint and emits 20 ms / 48 kHz / interleaved-stereo / signed-16 frames as [ShortArray]
 * of 1920 samples (960 per channel) — matching the exact contract
 * [dev.puklic.voice.screenshare.linux.PipeWireAudioReader] exposes on Linux.
 *
 * Strategy:
 *   1. `CoInitializeEx(APARTMENTTHREADED)` on the capture thread.
 *   2. Create an `IMMDeviceEnumerator`, get the default `eRender` endpoint.
 *   3. Activate `IAudioClient`, fetch mix format, force-set it to 48 kHz stereo Float32
 *      (WASAPI shared-mode loopback auto-resamples to the requested format inside the
 *      audio engine).
 *   4. `Initialize(SHAREMODE_SHARED, STREAMFLAGS_LOOPBACK | STREAMFLAGS_EVENTCALLBACK,
 *      20 ms, 0, pwfx, NULL)`.
 *   5. `CreateEventW` + `SetEventHandle` for event-driven capture.
 *   6. `GetService(IID_IAudioCaptureClient)` + `Start()`.
 *   7. Read loop:
 *        WaitForSingleObject(hEvent) →
 *        GetBuffer → convert Float32 → S16 (clamp at [-1.0, 1.0] then × 32767) →
 *        push into ring buffer →
 *        slice 1920-sample chunks and `send(...)` →
 *        ReleaseBuffer.
 *
 * Lifecycle: the cold [read] flow opens WASAPI on first collection, runs the read loop on
 * [Dispatchers.IO], and frees every COM ref + event handle in reverse allocation order on
 * cancellation. The [AtomicBoolean] gate keeps the close path observable from any thread.
 *
 * Windows-only — the bridge calls into Dxgi.dll / Ole32.dll which are only present on
 * Windows. Callers MUST gate construction on the platform.
 */
public class WindowsLoopbackAudioReader {

    private val closed = AtomicBoolean(false)

    public fun stop() {
        closed.set(true)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    public fun read(): Flow<ShortArray> = channelFlow {
        val coInitHr = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED).toInt()
        // S_OK or S_FALSE (already initialised on this thread) both indicate success.
        if (coInitHr != WindowsWasapiBridge.S_OK && coInitHr != S_FALSE) {
            error("CoInitializeEx failed: HRESULT=0x${coInitHr.toUInt().toString(WindowsDxgiBridge.HEX_RADIX)}")
        }

        var enumerator: Pointer? = null
        var device: Pointer? = null
        var client: Pointer? = null
        var captureClient: Pointer? = null
        var pwfx: Pointer? = null
        var hEvent: WinNT.HANDLE? = null
        var started = false

        try {
            enumerator = WindowsWasapiBridge.createMMDeviceEnumerator()
            device = WindowsWasapiBridge.getDefaultAudioEndpoint(enumerator)
            client = WindowsWasapiBridge.activateAudioClient(device)

            pwfx = WindowsWasapiBridge.getMixFormat(client)
            // Force-set 48 kHz stereo Float32 in-place. WASAPI shared mode honours the
            // requested format via internal mix-engine resampling. We rewrite the bytes
            // even when the engine's native format already matches — defensive against
            // 7.1 surround setups.
            forceFloat32Stereo48k(pwfx)

            WindowsWasapiBridge.initialize(
                client,
                WindowsWasapiBridge.AUDCLNT_SHAREMODE_SHARED,
                WindowsWasapiBridge.AUDCLNT_STREAMFLAGS_LOOPBACK or
                    WindowsWasapiBridge.AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
                BUFFER_DURATION_HNS,
                pwfx,
            )

            hEvent = Kernel32.INSTANCE.CreateEvent(null, false, false, null)
                ?: error("CreateEventW returned null")
            WindowsWasapiBridge.setEventHandle(client, hEvent)
            captureClient = WindowsWasapiBridge.getService(client, WindowsWasapiBridge.IID_IAudioCaptureClient)
            WindowsWasapiBridge.start(client)
            started = true

            val pending = ShortArray(FRAME_SAMPLES_STEREO * RING_FRAMES)
            var pendingLen = 0

            while (!closed.get() && !isClosedForSend) {
                val waitRc = Kernel32.INSTANCE.WaitForSingleObject(hEvent, WAIT_TIMEOUT_MS)
                if (waitRc == WinBase.WAIT_FAILED) error("WaitForSingleObject failed: ${Kernel32.INSTANCE.GetLastError()}")
                // WAIT_TIMEOUT — try again; the engine may stall briefly under heavy load.
                if (waitRc != WinBase.WAIT_OBJECT_0) continue

                while (!closed.get()) {
                    val buf = WindowsWasapiBridge.getBuffer(captureClient) ?: break
                    try {
                        val samples = buf.numFrames * OUTPUT_CHANNELS
                        if (samples <= 0) continue
                        if (pendingLen + samples > pending.size) {
                            Logger.w(TAG) { "ring buffer overflow — dropping $pendingLen samples" }
                            pendingLen = 0
                        }
                        if (buf.isSilent) {
                            // Zero-fill — WASAPI may not have written the buffer.
                            java.util.Arrays.fill(pending, pendingLen, pendingLen + samples, 0)
                        } else {
                            convertFloat32ToS16(buf.data, buf.numFrames, pending, pendingLen)
                        }
                        pendingLen += samples
                        while (pendingLen >= FRAME_SAMPLES_STEREO) {
                            val frame = pending.copyOfRange(0, FRAME_SAMPLES_STEREO)
                            send(frame)
                            val remaining = pendingLen - FRAME_SAMPLES_STEREO
                            if (remaining > 0) {
                                System.arraycopy(pending, FRAME_SAMPLES_STEREO, pending, 0, remaining)
                            }
                            pendingLen = remaining
                        }
                    } finally {
                        WindowsWasapiBridge.releaseBuffer(captureClient, buf.numFrames)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(TAG) { "WindowsLoopbackAudioReader: ${e.message}" }
        } finally {
            if (started && client != null) {
                runCatching { WindowsWasapiBridge.stop(client) }
            }
            captureClient?.let { runCatching { WindowsWasapiBridge.release(it) } }
            client?.let { runCatching { WindowsWasapiBridge.release(it) } }
            device?.let { runCatching { WindowsWasapiBridge.release(it) } }
            enumerator?.let { runCatching { WindowsWasapiBridge.release(it) } }
            pwfx?.let { runCatching { Ole32.INSTANCE.CoTaskMemFree(it) } }
            hEvent?.let { runCatching { Kernel32.INSTANCE.CloseHandle(it) } }
            Ole32.INSTANCE.CoUninitialize()
        }

        awaitClose { closed.set(true) }
    }.flowOn(Dispatchers.IO)

    /**
     * Rewrite the WAVEFORMATEX(TENSIBLE) struct at [pwfx] in place to request 48 kHz stereo
     * Float32. The buffer was CoTaskMemAlloc'd by [WindowsWasapiBridge.getMixFormat] and is
     * sized at least 18 bytes (WAVEFORMATEX) and typically 40 bytes (WAVEFORMATEXTENSIBLE).
     */
    private fun forceFloat32Stereo48k(pwfx: Pointer) {
        val current = WindowsWasapiBridge.readWaveFormat(pwfx)
        // For EXTENSIBLE: wFormatTag stays 0xFFFE; for non-extensible we'd promote to EXTENSIBLE,
        // but the WASAPI mix format on every modern Windows is EXTENSIBLE so we trust cbSize >= 22.
        val targetTag = if (current.cbSize >= EXTENSIBLE_CB_SIZE) {
            WindowsWasapiBridge.WAVE_FORMAT_EXTENSIBLE
        } else {
            current.formatTag
        }
        val blockAlign = OUTPUT_CHANNELS * BYTES_PER_SAMPLE_F32
        val avgBytesPerSec = OUTPUT_SAMPLE_RATE * blockAlign
        pwfx.setShort(0L, targetTag.toShort())
        pwfx.setShort(2L, OUTPUT_CHANNELS.toShort())
        pwfx.setInt(4L, OUTPUT_SAMPLE_RATE)
        pwfx.setInt(8L, avgBytesPerSec)
        pwfx.setShort(12L, blockAlign.toShort())
        pwfx.setShort(14L, BITS_PER_SAMPLE_F32.toShort())
        // cbSize unchanged.
        if (current.cbSize >= EXTENSIBLE_CB_SIZE) {
            // WAVEFORMATEXTENSIBLE union:
            //   18: WORD wValidBitsPerSample (or wSamplesPerBlock); use 32.
            //   20: DWORD dwChannelMask — stereo = SPEAKER_FRONT_LEFT(1) | SPEAKER_FRONT_RIGHT(2) = 3.
            //   24: GUID SubFormat (16 bytes).
            pwfx.setShort(EXTENSIBLE_VALID_BITS_OFFSET, BITS_PER_SAMPLE_F32.toShort())
            pwfx.setInt(EXTENSIBLE_CHANNEL_MASK_OFFSET, SPEAKER_FRONT_STEREO_MASK)
            val guidBytes = WindowsWasapiBridge.KSDATAFORMAT_SUBTYPE_IEEE_FLOAT.toByteArray()
            pwfx.write(EXTENSIBLE_SUBFORMAT_OFFSET, guidBytes, 0, guidBytes.size)
        }
    }

    /**
     * Convert a native Float32 interleaved-stereo buffer ([frameCount] frames =
     * `frameCount * 2` samples) into S16 written into [dst] starting at [dstOff].
     * Saturation clamp at `[-1.0, 1.0]` before multiplying by `Short.MAX_VALUE`.
     */
    private fun convertFloat32ToS16(src: Pointer, frameCount: Int, dst: ShortArray, dstOff: Int) {
        val sampleCount = frameCount * OUTPUT_CHANNELS
        val byteCount = sampleCount.toLong() * BYTES_PER_SAMPLE_F32
        val floats = src.getFloatArray(0L, sampleCount)
        for (i in 0 until sampleCount) {
            val f = floats[i]
            val clamped = when {
                f >  1.0f ->  1.0f
                f < -1.0f -> -1.0f
                else -> f
            }
            dst[dstOff + i] = (clamped * Short.MAX_VALUE).toInt().toShort()
        }
        // Avoid unused-variable warnings on byteCount (kept for documentation parity).
        check(byteCount >= 0)
    }

    private companion object {
        const val TAG = "WindowsLoopbackAudioReader"
        const val S_FALSE = 0x00000001
        const val OUTPUT_SAMPLE_RATE = 48_000
        const val OUTPUT_CHANNELS = 2
        const val BYTES_PER_SAMPLE_F32 = 4
        const val BITS_PER_SAMPLE_F32 = 32
        // 20 ms stereo S16 frame: 48000 / 50 = 960 samples per channel × 2 channels = 1920 shorts.
        const val FRAME_SAMPLES_STEREO = 1920
        // Ring buffer = 8 frames (160 ms) of headroom before backpressure drop.
        const val RING_FRAMES = 8
        // 20 ms in 100-ns REFERENCE_TIME units.
        const val BUFFER_DURATION_HNS = 200_000L
        // 100 ms safety on the event wait; WASAPI signals every BUFFER_DURATION_HNS normally.
        const val WAIT_TIMEOUT_MS = 100
        const val EXTENSIBLE_CB_SIZE = 22
        const val EXTENSIBLE_VALID_BITS_OFFSET = 18L
        const val EXTENSIBLE_CHANNEL_MASK_OFFSET = 20L
        const val EXTENSIBLE_SUBFORMAT_OFFSET = 24L
        const val SPEAKER_FRONT_STEREO_MASK = 0x3
    }
}
