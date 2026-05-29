package dev.puklic.screencast.windows

import co.touchlab.kermit.Logger
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Ole32
import dev.puklic.screencast.H264EncoderFactory
import dev.puklic.screencast.ScreenCapture
import dev.puklic.voice.codec.video.H264Encoder
import dev.puklic.voice.screenshare.ScreenSource
import dev.puklic.voice.screenshare.encoder.BgraToYuv420Converter
import dev.puklic.voice.screenshare.encoder.JvmLibavH264Encoder
import dev.puklic.voice.transport.EncodedFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Windows JVM `ScreenCapture` actual using `IDXGIOutputDuplication` for video and
 * `IAudioCaptureClient` (WASAPI loopback) for system audio. Encoded frames are produced
 * by the [H264Encoder] obtained from the caller-supplied [H264EncoderFactory] — on JVM
 * desktop that's [dev.puklic.voice.screenshare.encoder.JvmLibavH264Encoder] driving libx264.
 *
 * The [frames] cold flow:
 *   1. `CoInitializeEx` on the capture thread.
 *   2. Resolve `(adapterIndex, outputIndex)` from `source.id`.
 *   3. Create `IDXGIFactory1` → `EnumAdapters1` → `EnumOutputs` → walk to the target.
 *   4. `IDXGIOutput::QueryInterface(IDXGIOutput6)` (fallback to `IDXGIOutput1` on
 *      `E_NOINTERFACE` — see kdoc on [openDuplicationOutput]).
 *   5. `D3D11CreateDevice(adapter)` to put the device on the right GPU.
 *   6. `IDXGIOutput1::DuplicateOutput(device)`.
 *   7. Construct the [BgraToYuv420Converter] sized to `(source.widthPx, source.heightPx)`
 *      and the [H264Encoder] via [h264EncoderFactory].
 *   8. Loop: `AcquireNextFrame(16ms)` → `CopyResource(staging, frameTex)` →
 *      `Map(staging) → memcpy BGRA rows respecting RowPitch → Unmap` →
 *      `BgraToYuv420Converter.convert` → `H264Encoder.encode` → emit each returned NAL.
 *      Backoff to a 1 ms sleep on `DXGI_ERROR_WAIT_TIMEOUT`; retry duplication setup on
 *      `DXGI_ERROR_ACCESS_LOST` (up to [MAX_ACCESS_LOST_RETRIES] before erroring).
 *   9. Teardown: release every COM ref + native buffer in reverse allocation order;
 *      `CoUninitialize` last.
 *
 * The [audio] flow delegates entirely to [WindowsLoopbackAudioReader.read]. Both flows
 * are cold; collecting either starts only its half of the pipeline.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "TooManyFunctions")
public class WindowsScreenCapture(
    private val source: ScreenSource,
    private val shareAudio: Boolean,
    private val h264EncoderFactory: H264EncoderFactory,
) : ScreenCapture {

    private val closed = AtomicBoolean(false)
    private val audioReader: WindowsLoopbackAudioReader? =
        if (shareAudio) WindowsLoopbackAudioReader() else null

    override val frames: Flow<EncodedFrame> = channelFlow {
        val (width, height) = requireMonitorDims()
        val (adapterIndex, outputIndex) = parseSourceId(source.id)

        val coInitHr = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED).toInt()
        if (coInitHr != WindowsDxgiBridge.S_OK && coInitHr != S_FALSE) {
            error("CoInitializeEx failed: HRESULT=0x${coInitHr.toUInt().toString(WindowsDxgiBridge.HEX_RADIX)}")
        }

        var factory: Pointer? = null
        var adapter: Pointer? = null
        var output: Pointer? = null
        var output6: Pointer? = null
        var device: Pointer? = null
        var context: Pointer? = null
        var duplication: Pointer? = null
        var staging: Pointer? = null
        var converter: BgraToYuv420Converter? = null
        var encoder: H264Encoder? = null

        try {
            factory = WindowsDxgiBridge.createFactory1()
            adapter = WindowsDxgiBridge.enumAdapters1(factory, adapterIndex)
                ?: error("No DXGI adapter at index $adapterIndex (source.id=${source.id})")
            output = WindowsDxgiBridge.enumOutputs(adapter, outputIndex)
                ?: error("No DXGI output at index $outputIndex on adapter $adapterIndex")

            output6 = openDuplicationOutput(output)
            val (dev, ctx) = WindowsDxgiBridge.createD3D11Device(adapter)
            device = dev
            context = ctx
            duplication = createDuplication(output6, device)
            staging = WindowsDxgiBridge.createStagingTexture2D(device, width, height)
            converter = BgraToYuv420Converter(width, height)
            encoder = h264EncoderFactory.invoke()

            var accessLostRetries = 0
            // Snapshot the just-allocated COM refs into non-null vals; the surrounding
            // `var ... = null` declarations are required for the finally block to release
            // partial allocations on early failure, but Kotlin can't smart-cast through
            // mutable vars so we re-bind here.
            val ctxNonNull = context!!
            val devNonNull = device!!
            val stagingNonNull = staging!!
            val converterNonNull = converter!!
            val encoderNonNull = encoder!!
            val output6NonNull = output6!!
            while (!closed.get() && !isClosedForSend) {
                val activeDuplication = duplication
                    ?: error("duplication unexpectedly null at top of capture loop")
                when (val acquired = WindowsDxgiBridge.acquireNextFrame(activeDuplication, FRAME_TIMEOUT_MS)) {
                    is WindowsDxgiBridge.AcquireNextFrameResult.Ok -> {
                        accessLostRetries = 0
                        try {
                            captureOneFrame(
                                context = ctxNonNull,
                                staging = stagingNonNull,
                                converter = converterNonNull,
                                encoder = encoderNonNull,
                                acquired = acquired,
                                width = width,
                                height = height,
                            )
                        } finally {
                            WindowsDxgiBridge.releaseFrame(activeDuplication)
                        }
                    }
                    WindowsDxgiBridge.AcquireNextFrameResult.Timeout -> {
                        // No change since last AcquireNextFrame — sleep briefly so we don't burn
                        // a CPU core when the screen is idle.
                        Thread.sleep(IDLE_SLEEP_MS)
                    }
                    WindowsDxgiBridge.AcquireNextFrameResult.AccessLost -> {
                        accessLostRetries++
                        if (accessLostRetries > MAX_ACCESS_LOST_RETRIES) {
                            error("DXGI duplication access lost $MAX_ACCESS_LOST_RETRIES times; giving up")
                        }
                        Logger.w(TAG) { "DXGI access lost; re-acquiring duplication (try $accessLostRetries)" }
                        // Release the dead duplication and create a fresh one. The Output6
                        // pointer is still valid because Output objects survive desktop mode
                        // switches.
                        WindowsDxgiBridge.release(activeDuplication)
                        duplication = createDuplication(output6NonNull, devNonNull)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(TAG) { "WindowsScreenCapture frames: ${e.message}" }
        } finally {
            encoder?.let { runCatching { it.close() } }
            converter?.let { runCatching { it.close() } }
            staging?.let { runCatching { WindowsDxgiBridge.release(it) } }
            duplication?.let { runCatching { WindowsDxgiBridge.release(it) } }
            context?.let { runCatching { WindowsDxgiBridge.release(it) } }
            device?.let { runCatching { WindowsDxgiBridge.release(it) } }
            output6?.let { runCatching { WindowsDxgiBridge.release(it) } }
            output?.let { runCatching { WindowsDxgiBridge.release(it) } }
            adapter?.let { runCatching { WindowsDxgiBridge.release(it) } }
            factory?.let { runCatching { WindowsDxgiBridge.release(it) } }
            Ole32.INSTANCE.CoUninitialize()
        }
        awaitClose { closed.set(true) }
    }.flowOn(Dispatchers.IO)

    override val audio: Flow<ShortArray>? = audioReader?.read()

    override fun close() {
        closed.set(true)
        audioReader?.stop()
    }

    /**
     * Drive one acquired frame through copy → map → swscale → libx264, sending every NAL
     * the encoder produces into the outer [channelFlow].
     */
    private suspend fun kotlinx.coroutines.channels.ProducerScope<EncodedFrame>.captureOneFrame(
        context: Pointer,
        staging: Pointer,
        converter: BgraToYuv420Converter,
        encoder: H264Encoder,
        acquired: WindowsDxgiBridge.AcquireNextFrameResult.Ok,
        width: Int,
        height: Int,
    ) {
        val resource = acquired.resource
        val texture = WindowsDxgiBridge.queryInterface(resource, WindowsDxgiBridge.IID_ID3D11Texture2D)
            ?: run {
                WindowsDxgiBridge.release(resource)
                error("AcquireNextFrame returned resource that is not ID3D11Texture2D")
            }
        try {
            WindowsDxgiBridge.copyResource(context, staging, texture)
        } finally {
            WindowsDxgiBridge.release(texture)
            WindowsDxgiBridge.release(resource)
        }

        val mapped = WindowsDxgiBridge.map(context, staging)
        val bgra = try {
            extractBgraTightlyPacked(mapped.data, mapped.rowPitch, width, height)
        } finally {
            WindowsDxgiBridge.unmap(context, staging)
        }

        val yuv = converter.convert(bgra)
        val first = encoder.encode(yuv) ?: return
        send(first)
        // Drain any further NAL units libx264 emitted from this same encoded packet (typical
        // on keyframes — SPS + PPS + IDR). The concrete [JvmLibavH264Encoder] exposes a
        // side-channel for this; non-libav implementations would inline equivalent draining
        // in their own encode() loop and never queue.
        if (encoder is JvmLibavH264Encoder) {
            while (true) {
                val next = encoder.drainPending() ?: break
                send(next)
            }
        }
    }

    /**
     * Try `QueryInterface(IID_IDXGIOutput6)` first (Win10 1803+ — gives HDR-aware metadata)
     * and fall back to `IDXGIOutput1` (Win8+) on `E_NOINTERFACE`. The fallback object is
     * returned with one COM ref the caller must release — semantically identical to a
     * successful Output6 result for our purposes (we only call DuplicateOutput, which is
     * on Output1's slot and inherited unchanged into Output6).
     */
    private fun openDuplicationOutput(output: Pointer): Pointer {
        WindowsDxgiBridge.queryInterface(output, WindowsDxgiBridge.IID_IDXGIOutput6)?.let { return it }
        WindowsDxgiBridge.queryInterface(output, WindowsDxgiBridge.IID_IDXGIOutput1)?.let { return it }
        error("Neither IDXGIOutput6 nor IDXGIOutput1 supported on this Windows version")
    }

    private fun createDuplication(output1OrLater: Pointer, device: Pointer): Pointer {
        return when (val r = WindowsDxgiBridge.duplicateOutput(output1OrLater, device)) {
            is WindowsDxgiBridge.DuplicateOutputResult.Ok -> r.duplication
            WindowsDxgiBridge.DuplicateOutputResult.AccessDenied -> error(
                "DuplicateOutput access denied — secure desktop / UAC active; retry after dismissing the prompt",
            )
            WindowsDxgiBridge.DuplicateOutputResult.Unsupported -> error(
                "DuplicateOutput unsupported on this display mode (HDR-only or non-DWM)",
            )
        }
    }

    private fun requireMonitorDims(): Pair<Int, Int> = when (val s = source) {
        is ScreenSource.Monitor -> s.widthPx to s.heightPx
        is ScreenSource.Window -> error(
            "WindowsScreenCapture only supports ScreenSource.Monitor; got Window (${s.id}). " +
                "Use WindowsGraphicsCapture (UWP) for per-window capture — not in scope for FP-9.",
        )
    }

    private fun parseSourceId(id: String): Pair<Int, Int> {
        val parts = id.split(':')
        require(parts.size == 2) { "ScreenSource.id '$id' must be 'adapterIndex:outputIndex'" }
        return parts[0].toInt() to parts[1].toInt()
    }

    /**
     * Read [width]×[height] BGRA pixels out of [src] honouring [rowPitch] (the mapped
     * texture may have padding at the end of each row). Returns a tightly-packed
     * `width × height × 4` byte array — what `BgraToYuv420Converter` expects.
     */
    private fun extractBgraTightlyPacked(
        src: Pointer,
        rowPitch: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        val rowBytes = width * BgraToYuv420Converter.BYTES_PER_BGRA_PIXEL
        require(rowPitch >= rowBytes) { "rowPitch=$rowPitch smaller than tightly-packed row=$rowBytes" }
        val out = ByteArray(rowBytes * height)
        if (rowPitch == rowBytes) {
            // Fast path — single contiguous copy.
            src.read(0L, out, 0, out.size)
        } else {
            // Slow path — per-row copy honouring stride.
            for (y in 0 until height) {
                src.read(y.toLong() * rowPitch, out, y * rowBytes, rowBytes)
            }
        }
        return out
    }

    private companion object {
        const val TAG = "WindowsScreenCapture"
        const val S_FALSE = 0x00000001
        const val FRAME_TIMEOUT_MS = 16
        const val IDLE_SLEEP_MS = 1L
        const val MAX_ACCESS_LOST_RETRIES = 3
    }
}
