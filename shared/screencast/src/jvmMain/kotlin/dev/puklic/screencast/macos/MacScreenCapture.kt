package dev.puklic.screencast.macos

import co.touchlab.kermit.Logger
import com.sun.jna.Pointer
import dev.puklic.screencast.H264EncoderFactory
import dev.puklic.screencast.ScreenCapture
import dev.puklic.voice.codec.video.H264Encoder
import dev.puklic.voice.screenshare.ScreenSource
import dev.puklic.voice.transport.EncodedFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import org.bytedeco.javacpp.BytePointer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * macOS [ScreenCapture] driving a ScreenCaptureKit `SCStream`. Captures BGRA
 * frames on the framework's delegate dispatch queue, copies them into a
 * conflated channel, and runs an encoder coroutine that converts BGRA →
 * YUV420P → H.264 Annex-B NAL units.
 *
 * Audio (macOS 13+ only) is delivered on a separate `SCStreamOutputType.audio`
 * path: PCM Float32 stereo @ 48 kHz → [AudioBufferConverter] → S16 stereo.
 * On macOS 12.3 / 12.4 / 12.x the [audio] flow is `null` even if the caller
 * requested it; a Kermit warning is logged at construction.
 */
public class MacScreenCapture internal constructor(
    private val source: ScreenSource,
    shareAudio: Boolean,
    private val encoderFactory: H264EncoderFactory,
    private val width: Int = DEFAULT_WIDTH,
    private val height: Int = DEFAULT_HEIGHT,
    private val framerate: Int = DEFAULT_FRAMERATE,
) : ScreenCapture {

    private val log = Logger.withTag("MacScreenCapture")

    private val osVersion = ScreenCaptureKitBridge.currentOsVersion()
    private val audioEnabled: Boolean = shareAudio && ScreenCaptureKitBridge.supportsAudioCapture(osVersion).also {
        if (shareAudio && !it) {
            log.w {
                "Audio share requested but macOS ${osVersion.major}.${osVersion.minor} predates SCStream " +
                    "audio capture (requires macOS ${ScreenCaptureKitBridge.MIN_OS_MAJOR_AUDIO}+) — disabling."
            }
        }
    }

    init {
        if (!ScreenCaptureKitBridge.supportsVideoCapture(osVersion)) {
            error("ScreenCaptureKit requires macOS 12.3+, host is ${osVersion.major}.${osVersion.minor}")
        }
    }

    private val closed = AtomicBoolean(false)

    /**
     * Channel of raw BGRA frames (already copied out of the CVPixelBuffer).
     * Capacity 1; when full, the producer (`onScreenSampleBuffer`) deallocates
     * the new frame's `BytePointer` rather than buffering it — same effect as
     * `DROP_LATEST` but with explicit native-memory cleanup, which
     * [BufferOverflow.DROP_LATEST] alone cannot do (the channel never sees the
     * frame, so it cannot free its bytes).
     */
    private val bgraChannel = Channel<BgraFrame>(capacity = 1)

    /** Shared flow of PCM16 audio frames — replay = 0, only live samples. */
    private val audioSink = MutableSharedFlow<ShortArray>(replay = 0, extraBufferCapacity = AUDIO_BUFFER)

    private var stream: Pointer? = null
    private var configuration: Pointer? = null
    private var filter: Pointer? = null
    private var delegate: Pointer? = null
    private var startKeepalive: ScreenCaptureKitBridge.BlockKeepalive? = null
    private var stopKeepalive: ScreenCaptureKitBridge.BlockKeepalive? = null

    override val frames: Flow<EncodedFrame> = channelFlow {
        val converter = BgraToYuv420(width, height, width, height)
        val yuvScratch = ByteArray(converter.yuvSize)
        val encoder: H264Encoder = encoderFactory()
        startStream()
        try {
            while (!closed.get() && !isClosedForSend) {
                val frame = bgraChannel.receive()
                try {
                    converter.convert(frame.bgra, frame.bytesPerRow, yuvScratch)
                } finally {
                    frame.bgra.deallocate()
                }
                // [LibavPushH264Encoder] buffers all NAL units produced by one
                // YUV frame (SPS + PPS + IDR on keyframes) and returns them one
                // per call: first call with new YUV input flushes the encoder,
                // subsequent calls with an empty input drain the queue.
                val first = encoder.encode(yuvScratch) ?: continue
                send(first)
                while (true) {
                    val next = encoder.encode(EMPTY_DRAIN_INPUT) ?: break
                    send(next)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            stopStream()
            encoder.close()
            converter.close()
            awaitClose { closed.set(true) }
        }
    }.flowOn(Dispatchers.IO)

    override val audio: Flow<ShortArray>? = if (audioEnabled) audioSink.asSharedFlow() else null

    /** Called by [DelegateClass] on the framework dispatch queue. */
    internal fun onScreenSampleBuffer(sampleBuffer: Pointer) {
        if (closed.get()) return
        val imageBuffer = CoreMedia.coreMedia.CMSampleBufferGetImageBuffer(sampleBuffer)
        if (Pointer.nativeValue(imageBuffer) == 0L) return
        CoreMedia.coreVideo.CVPixelBufferLockBaseAddress(imageBuffer, CoreMedia.LOCK_READ_ONLY)
        try {
            val base = CoreMedia.coreVideo.CVPixelBufferGetBaseAddress(imageBuffer)
            if (Pointer.nativeValue(base) == 0L) return
            val bytesPerRow = CoreMedia.coreVideo.CVPixelBufferGetBytesPerRow(imageBuffer).toInt()
            val h = CoreMedia.coreVideo.CVPixelBufferGetHeight(imageBuffer).toInt()
            val total = bytesPerRow * h
            val copy = BytePointer(total.toLong())
            copy.put(base.getByteArray(0, total), 0, total)
            val offered = bgraChannel.trySend(BgraFrame(copy, bytesPerRow, h))
            if (offered.isFailure) copy.deallocate()
        } finally {
            CoreMedia.coreVideo.CVPixelBufferUnlockBaseAddress(imageBuffer, CoreMedia.LOCK_READ_ONLY)
        }
    }

    /** Called by [DelegateClass] on the framework dispatch queue. */
    internal fun onAudioSampleBuffer(sampleBuffer: Pointer) {
        if (closed.get() || !audioEnabled) return
        val pcm = AudioBufferConverter.toPcm16(sampleBuffer)
        if (pcm.isEmpty()) return
        audioSink.tryEmit(pcm)
    }

    /** Called by [DelegateClass] when ScreenCaptureKit signals stream stop. */
    internal fun onStreamStopped(error: String) {
        if (error.isNotEmpty()) log.w { "SCStream stopped with error: $error" }
        closed.set(true)
    }

    private fun startStream() {
        val sourceId = parseSourceId() ?: error("Unrecognised macOS ScreenSource id: ${source.id}")
        val osArray = enumerateDisplaysAndWindowsOnce()
        val target = findTarget(osArray, sourceId)
            ?: run {
                Foundation.release(osArray.content)
                error("ScreenCaptureKit does not see source ${source.id} — was it disconnected?")
            }
        try {
            val cfg = ScreenCaptureKitBridge.buildConfiguration(width, height, framerate, audioEnabled)
            configuration = cfg
            filter = buildFilter(target)
            delegate = DelegateClass.newInstance(this)
            val s = ObjcRuntime.msgSend(
                ObjcRuntime.alloc(ScreenCaptureKitBridge.classSCStream),
                ScreenCaptureKitBridge.selStreamInit,
                filter,
                cfg,
                delegate,
            )
            stream = s
            addStreamOutputs(s)
            val startLatch = CountDownLatch(1)
            val startKa = ScreenCaptureKitBridge.completionBlock { err ->
                if (err != null && Pointer.nativeValue(err) != 0L) {
                    log.e { "SCStream start error: ${Foundation.errorMessage(err)}" }
                }
                startLatch.countDown()
            }
            startKeepalive = startKa
            ObjcRuntime.msgSendVoid(s, ScreenCaptureKitBridge.selStartCapture, startKa.block)
            if (!startLatch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                error("SCStream start timed out after ${START_TIMEOUT_MS}ms")
            }
        } finally {
            Foundation.release(osArray.content)
        }
    }

    private fun addStreamOutputs(s: Pointer) {
        val d = delegate ?: error("delegate not allocated")
        // `sampleHandlerQueue` = NULL → ScreenCaptureKit uses its internal queue.
        // `error` out-pointer = NULL → failure is reported through the BOOL return,
        // which we ignore (addStreamOutput rarely fails for well-formed delegates).
        ObjcRuntime.msgSendVoid(
            s,
            ScreenCaptureKitBridge.selAddStreamOutput,
            d,
            ScreenCaptureKitBridge.OUTPUT_TYPE_SCREEN.toLong(),
            null,
            null,
        )
        if (audioEnabled) {
            ObjcRuntime.msgSendVoid(
                s,
                ScreenCaptureKitBridge.selAddStreamOutput,
                d,
                ScreenCaptureKitBridge.OUTPUT_TYPE_AUDIO.toLong(),
                null,
                null,
            )
        }
    }

    private fun stopStream() {
        if (!closed.compareAndSet(false, true)) return
        val s = stream
        if (s != null) {
            val stopLatch = CountDownLatch(1)
            val stopKa = ScreenCaptureKitBridge.completionBlock { _ -> stopLatch.countDown() }
            stopKeepalive = stopKa
            ObjcRuntime.msgSendVoid(s, ScreenCaptureKitBridge.selStopCapture, stopKa.block)
            stopLatch.await(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            Foundation.release(s)
            stream = null
        }
        delegate?.let {
            DelegateClass.forget(it)
            Foundation.release(it)
            delegate = null
        }
        filter?.let { Foundation.release(it); filter = null }
        configuration?.let { Foundation.release(it); configuration = null }
        // Drain any pending BGRA frames so their BytePointers can be freed.
        while (true) {
            val result = bgraChannel.tryReceive()
            if (result.isFailure) break
            result.getOrNull()?.bgra?.deallocate()
        }
        bgraChannel.close()
    }

    override fun close() {
        stopStream()
    }

    /**
     * Re-enumerate displays / windows just-in-time so we can pick the matching
     * `SCDisplay*` / `SCWindow*` to wrap into the `SCContentFilter`. We do not
     * cache because the caller may have held the [ScreenSource] across user
     * monitor reconfiguration.
     */
    private fun enumerateDisplaysAndWindowsOnce(): ScreenCaptureKitBridge.ShareableContentResult.Ok {
        return when (val r = ScreenCaptureKitBridge.enumerateShareableContent(timeoutMs = START_TIMEOUT_MS)) {
            is ScreenCaptureKitBridge.ShareableContentResult.Error -> error(r.message)
            is ScreenCaptureKitBridge.ShareableContentResult.Ok -> r
        }
    }

    private sealed interface SourceId {
        data class Display(val id: Int) : SourceId
        data class Window(val id: Int) : SourceId
    }

    private fun parseSourceId(): SourceId? {
        val parts = source.id.split(":", limit = 2)
        if (parts.size != 2) return null
        val n = parts[1].toIntOrNull() ?: return null
        return when (parts[0]) {
            "display" -> SourceId.Display(n)
            "window" -> SourceId.Window(n)
            else -> null
        }
    }

    private fun findTarget(
        content: ScreenCaptureKitBridge.ShareableContentResult.Ok,
        sourceId: SourceId,
    ): Pointer? {
        val arr = when (sourceId) {
            is SourceId.Display -> ObjcRuntime.msgSend(content.content, ScreenCaptureKitBridge.selDisplays)
            is SourceId.Window -> ObjcRuntime.msgSend(content.content, ScreenCaptureKitBridge.selWindows)
        }
        val n = Foundation.arrayCount(arr)
        for (i in 0 until n) {
            val obj = Foundation.arrayObjectAt(arr, i)
            val matchSel = when (sourceId) {
                is SourceId.Display -> ScreenCaptureKitBridge.selDisplayId
                is SourceId.Window -> ScreenCaptureKitBridge.selWindowId
            }
            val gotId = ObjcRuntime.msgSendInt(obj, matchSel)
            val wanted = when (sourceId) {
                is SourceId.Display -> sourceId.id
                is SourceId.Window -> sourceId.id
            }
            if (gotId == wanted) return Foundation.retain(obj)
        }
        return null
    }

    private fun buildFilter(target: Pointer): Pointer {
        // For both display and window targets use the simpler
        // `initWithDisplay:excludingWindows:` (display target) — the window
        // path uses `initWithDesktopIndependentWindow:` which we look up
        // lazily. Pre-macOS 13.0 has only the display form, so window
        // capture on 12.x returns the whole containing display (Discord
        // tolerates this — the user picks the window from inside the
        // shared display).
        val initWithDisplay = ScreenCaptureKitBridge.selFilterInitWithDisplay
        val initWithDesktopIndependent = ObjcRuntime.sel("initWithDesktopIndependentWindow:")
        val alloc = ObjcRuntime.alloc(ScreenCaptureKitBridge.classSCContentFilter)
        // First try the window-specific selector — succeeds on macOS 12.3+ when target is a window.
        return runCatching { ObjcRuntime.msgSend(alloc, initWithDesktopIndependent, target) }
            .getOrElse {
                val emptyArray = ObjcRuntime.msgSend(ObjcRuntime.getClass("NSArray"), ObjcRuntime.sel("array"))
                ObjcRuntime.msgSend(alloc, initWithDisplay, target, emptyArray)
            }
    }

    /** A copied BGRA frame plus its source row stride. */
    private data class BgraFrame(val bgra: BytePointer, val bytesPerRow: Int, val height: Int)

    public companion object {
        public const val DEFAULT_WIDTH: Int = 1920
        public const val DEFAULT_HEIGHT: Int = 1080
        public const val DEFAULT_FRAMERATE: Int = 30
        public const val START_TIMEOUT_MS: Long = 10_000L
        public const val STOP_TIMEOUT_MS: Long = 5_000L
        private const val AUDIO_BUFFER: Int = 32
        private val EMPTY_DRAIN_INPUT: ByteArray = ByteArray(0)
    }
}
