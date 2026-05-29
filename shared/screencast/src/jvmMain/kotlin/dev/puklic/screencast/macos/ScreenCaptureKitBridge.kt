package dev.puklic.screencast.macos

import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * ScreenCaptureKit selector + Apple class lookup cache plus the helper
 * routines for the two flows we actually exercise:
 *
 *   1. `SCShareableContent.getShareableContentWithCompletionHandler:` —
 *      block-based async enumeration of displays + windows.
 *   2. `SCStream.initWithFilter:configuration:delegate:` +
 *      `startCaptureWithCompletionHandler:` — the streaming flow that feeds
 *      [MacScreenCapture].
 *
 * Selector / class lookup is lazy so the file loads cleanly on non-Mac
 * hosts; the first call into a method touching `libobjc` is what fails.
 *
 * See architect report §3.3 — §3.5 for the rationale of each call site.
 */
@Suppress("TooManyFunctions")
internal object ScreenCaptureKitBridge {

    /** macOS minimum versions per architect report §4. */
    const val MIN_OS_MAJOR_VIDEO: Int = 12
    const val MIN_OS_MINOR_VIDEO: Int = 3
    const val MIN_OS_MAJOR_AUDIO: Int = 13

    /** `kCVPixelFormatType_32BGRA` four-char-code 'BGRA' = 0x42475241. */
    const val CV_PIXEL_FORMAT_32BGRA: Int = 0x4247_5241

    val classSCShareableContent: Pointer by lazy { ObjcRuntime.getClass("SCShareableContent") }
    val classSCContentFilter: Pointer by lazy { ObjcRuntime.getClass("SCContentFilter") }
    val classSCStreamConfiguration: Pointer by lazy { ObjcRuntime.getClass("SCStreamConfiguration") }
    val classSCStream: Pointer by lazy { ObjcRuntime.getClass("SCStream") }
    val classNSError: Pointer by lazy { ObjcRuntime.getClass("NSError") }
    val classNSNumber: Pointer by lazy { ObjcRuntime.getClass("NSNumber") }
    val classNSProcessInfo: Pointer by lazy { ObjcRuntime.getClass("NSProcessInfo") }

    val selProcessInfo: Pointer by lazy { ObjcRuntime.sel("processInfo") }
    val selOperatingSystemVersion: Pointer by lazy { ObjcRuntime.sel("operatingSystemVersion") }

    val selGetShareableContent: Pointer by lazy {
        ObjcRuntime.sel("getShareableContentWithCompletionHandler:")
    }
    val selDisplays: Pointer by lazy { ObjcRuntime.sel("displays") }
    val selWindows: Pointer by lazy { ObjcRuntime.sel("windows") }
    val selDisplayId: Pointer by lazy { ObjcRuntime.sel("displayID") }
    val selWindowId: Pointer by lazy { ObjcRuntime.sel("windowID") }
    val selFrame: Pointer by lazy { ObjcRuntime.sel("frame") }
    val selTitle: Pointer by lazy { ObjcRuntime.sel("title") }
    val selOwningApplication: Pointer by lazy { ObjcRuntime.sel("owningApplication") }
    val selApplicationName: Pointer by lazy { ObjcRuntime.sel("applicationName") }
    val selWidth: Pointer by lazy { ObjcRuntime.sel("width") }
    val selHeight: Pointer by lazy { ObjcRuntime.sel("height") }

    val selFilterInitWithDisplay: Pointer by lazy {
        ObjcRuntime.sel("initWithDisplay:excludingWindows:")
    }
    val selFilterInitWithDesktopWindowsForDisplay: Pointer by lazy {
        ObjcRuntime.sel("initWithDisplay:excludingApplications:exceptingWindows:")
    }

    val selSetWidth: Pointer by lazy { ObjcRuntime.sel("setWidth:") }
    val selSetHeight: Pointer by lazy { ObjcRuntime.sel("setHeight:") }
    val selSetPixelFormat: Pointer by lazy { ObjcRuntime.sel("setPixelFormat:") }
    val selSetMinimumFrameInterval: Pointer by lazy { ObjcRuntime.sel("setMinimumFrameInterval:") }
    val selSetCapturesAudio: Pointer by lazy { ObjcRuntime.sel("setCapturesAudio:") }
    val selSetExcludesCurrentProcessAudio: Pointer by lazy {
        ObjcRuntime.sel("setExcludesCurrentProcessAudio:")
    }
    val selSetSampleRate: Pointer by lazy { ObjcRuntime.sel("setSampleRate:") }
    val selSetChannelCount: Pointer by lazy { ObjcRuntime.sel("setChannelCount:") }
    val selSetQueueDepth: Pointer by lazy { ObjcRuntime.sel("setQueueDepth:") }

    val selStreamInit: Pointer by lazy {
        ObjcRuntime.sel("initWithFilter:configuration:delegate:")
    }
    val selAddStreamOutput: Pointer by lazy {
        ObjcRuntime.sel("addStreamOutput:type:sampleHandlerQueue:error:")
    }
    val selStartCapture: Pointer by lazy {
        ObjcRuntime.sel("startCaptureWithCompletionHandler:")
    }
    val selStopCapture: Pointer by lazy {
        ObjcRuntime.sel("stopCaptureWithCompletionHandler:")
    }
    val selInit: Pointer by lazy { ObjcRuntime.sel("init") }

    /** `SCStreamOutputType` — `.screen = 0`, `.audio = 1` per public headers. */
    const val OUTPUT_TYPE_SCREEN: Int = 0
    const val OUTPUT_TYPE_AUDIO: Int = 1

    /**
     * `NSOperatingSystemVersion` is a 3 × `NSInteger` (8-byte on arm64) C
     * struct returned by value. JNA does not natively model
     * `objc_msgSend_stret` so we sidestep by querying the convenience
     * `majorVersion` / `minorVersion` properties added to `NSProcessInfo` for
     * exactly this reason.
     */
    data class OsVersion(val major: Int, val minor: Int, val patch: Int)

    fun currentOsVersion(): OsVersion {
        val info = ObjcRuntime.msgSend(classNSProcessInfo, selProcessInfo)
        val major = ObjcRuntime.msgSendLong(info, ObjcRuntime.sel("majorVersion"))
        val minor = ObjcRuntime.msgSendLong(info, ObjcRuntime.sel("minorVersion"))
        val patch = ObjcRuntime.msgSendLong(info, ObjcRuntime.sel("patchVersion"))
        return OsVersion(major.toInt(), minor.toInt(), patch.toInt())
    }

    fun supportsVideoCapture(v: OsVersion): Boolean =
        v.major > MIN_OS_MAJOR_VIDEO || (v.major == MIN_OS_MAJOR_VIDEO && v.minor >= MIN_OS_MINOR_VIDEO)

    fun supportsAudioCapture(v: OsVersion): Boolean = v.major >= MIN_OS_MAJOR_AUDIO

    /**
     * Synchronously enumerate displays + windows via the async
     * `getShareableContentWithCompletionHandler:` API. The completion handler
     * is an Objective-C block whose `invoke` field is a JNA function pointer
     * pointing at [callback]. Blocks the calling thread on a
     * [CountDownLatch] until the completion fires or [timeoutMs] elapses.
     */
    fun enumerateShareableContent(timeoutMs: Long): ShareableContentResult {
        val latch = CountDownLatch(1)
        val contentRef = AtomicReference<Pointer?>(null)
        val errorMsg = AtomicReference<String>("")

        val callback = object : Callback {
            @Suppress("unused")
            fun invoke(block: Pointer?, content: Pointer?, error: Pointer?) {
                try {
                    if (content != null && Pointer.nativeValue(content) != 0L) {
                        // Retain so the array survives past the autorelease pool of the
                        // delegate dispatch queue that called us.
                        contentRef.set(Foundation.retain(content))
                    }
                    if (error != null && Pointer.nativeValue(error) != 0L) {
                        errorMsg.set(Foundation.errorMessage(error))
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val descriptor = Memory((LONG_BYTES * 3).toLong()).apply {
            // reserved = 0
            setLong(0, 0)
            // size = sizeof(BlockLiteral) — must match the literal's struct size or
            // the runtime rejects the block. BlockLiteral is 5 pointer-sized fields,
            // 40 bytes on arm64.
            setLong(LONG_BYTES, BLOCK_LITERAL_SIZE_ARM64)
            // signature = NULL (we omit signature → drop BLOCK_HAS_SIGNATURE flag)
            setPointer(LONG_BYTES * 2L, null)
        }

        val invokePointer = ObjcRuntime.fnPointer(callback)
        val blockMem = Memory(BLOCK_LITERAL_SIZE_ARM64).apply {
            setPointer(0, ObjcRuntime.nsConcreteGlobalBlock)
            setInt(POINTER_BYTES.toLong(), BLOCK_FLAGS_NO_SIGNATURE)
            setInt(POINTER_BYTES.toLong() + INT_BYTES, 0)
            setPointer(POINTER_BYTES.toLong() + INT_BYTES * 2L, invokePointer)
            setPointer(POINTER_BYTES.toLong() + INT_BYTES * 2L + POINTER_BYTES, descriptor)
        }

        ObjcRuntime.msgSendVoid(classSCShareableContent, selGetShareableContent, blockMem)

        val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        // Keep `blockMem`, `descriptor`, and `callback` reachable until the latch
        // returns — releasing them earlier would dangle the block pointer the
        // ScreenCaptureKit dispatch queue still holds. The references above keep
        // the JVM objects live for the lifetime of this method.
        return if (!ok) {
            ShareableContentResult.Error("ScreenCaptureKit enumeration timed out after ${timeoutMs}ms")
        } else if (errorMsg.get().isNotEmpty()) {
            ShareableContentResult.Error("ScreenCaptureKit error: ${errorMsg.get()}")
        } else {
            val content = contentRef.get()
                ?: return ShareableContentResult.Error("ScreenCaptureKit returned no content")
            ShareableContentResult.Ok(content)
        }
    }

    sealed interface ShareableContentResult {
        /** Caller owns the retained `SCShareableContent*` pointer — must release. */
        data class Ok(val content: Pointer) : ShareableContentResult
        data class Error(val message: String) : ShareableContentResult
    }

    /**
     * Build an `SCStreamConfiguration*` with the standard Puklic preset. Caller
     * owns the returned pointer and must release it when the stream is torn
     * down. Sets `pixelFormat = 32BGRA`, `width / height`, `queueDepth = 8`,
     * `minimumFrameInterval = (1 / framerate)`, plus audio fields when
     * [shareAudio] is true (caller is responsible for the macOS version gate).
     */
    fun buildConfiguration(
        width: Int,
        height: Int,
        framerate: Int,
        shareAudio: Boolean,
    ): Pointer {
        val cfg = ObjcRuntime.msgSend(ObjcRuntime.alloc(classSCStreamConfiguration), selInit)
        ObjcRuntime.msgSendLongArg(cfg, selSetWidth, width.toLong())
        ObjcRuntime.msgSendLongArg(cfg, selSetHeight, height.toLong())
        ObjcRuntime.msgSendIntArg(cfg, selSetPixelFormat, CV_PIXEL_FORMAT_32BGRA)
        ObjcRuntime.msgSendLongArg(cfg, selSetQueueDepth, QUEUE_DEPTH.toLong())
        // `minimumFrameInterval` is a `CMTime` (value: int64, timescale: int32, flags: uint32,
        // epoch: int64) struct passed by value. JNA struct-by-value through objc_msgSend works
        // via a per-call signature; we use the convenience selector that takes (Int64 timescale)
        // when present. ScreenCaptureKit only exposes the struct selector, so we forge it via
        // a memory buffer that mirrors CMTime: value=1, timescale=fps, flags=VALID(1), epoch=0.
        val cmTime = Memory(CM_TIME_BYTES).apply {
            setLong(0, 1L)                           // value
            setInt(LONG_BYTES, framerate)            // timescale
            setInt(LONG_BYTES + INT_BYTES, 1)        // flags = kCMTimeFlags_Valid
            setLong(LONG_BYTES + INT_BYTES * 2L, 0L) // epoch
        }
        ObjcRuntime.msgSendVoid(cfg, selSetMinimumFrameInterval, cmTime)
        if (shareAudio) {
            ObjcRuntime.msgSendBool(cfg, selSetCapturesAudio, true)
            ObjcRuntime.msgSendBool(cfg, selSetExcludesCurrentProcessAudio, true)
            ObjcRuntime.msgSendLongArg(cfg, selSetSampleRate, AUDIO_SAMPLE_RATE.toLong())
            ObjcRuntime.msgSendLongArg(cfg, selSetChannelCount, AUDIO_CHANNELS.toLong())
        }
        return cfg
    }

    /** `8` — empirically a good balance for 30 fps screen share (Apple sample code). */
    const val QUEUE_DEPTH: Int = 8

    /** 48 kHz stereo S16 matches `SoundshareAudioRtpSender` (FP-1). */
    const val AUDIO_SAMPLE_RATE: Int = 48_000
    const val AUDIO_CHANNELS: Int = 2

    /** `CMTime` is a packed 24-byte struct on arm64 (value + timescale + flags + epoch). */
    const val CM_TIME_BYTES: Long = 24

    const val LONG_BYTES: Long = 8
    const val INT_BYTES: Long = 4
    const val POINTER_BYTES: Long = 8

    /** 5 pointer-sized fields when collapsed: isa, flags+reserved (one 8-byte slot), invoke, descriptor. */
    const val BLOCK_LITERAL_SIZE_ARM64: Long = 40

    /** `BLOCK_IS_GLOBAL` only — no signature exposed. */
    const val BLOCK_FLAGS_NO_SIGNATURE: Int = 1 shl 28

    /**
     * Helpers used by [MacScreenCapture] to package a completion block for
     * `startCaptureWithCompletionHandler:` / `stopCaptureWithCompletionHandler:`.
     * Returns a `Pointer` to the `Memory`-backed block plus an opaque keep-alive
     * the caller must hold onto until the block fires.
     */
    fun completionBlock(onComplete: (Pointer?) -> Unit): BlockKeepalive {
        val callback = object : Callback {
            @Suppress("unused")
            fun invoke(block: Pointer?, error: Pointer?) {
                onComplete(error)
            }
        }
        val descriptor = Memory((LONG_BYTES * 3).toLong()).apply {
            setLong(0, 0)
            setLong(LONG_BYTES, BLOCK_LITERAL_SIZE_ARM64)
            setPointer(LONG_BYTES * 2L, null)
        }
        val invokePointer = ObjcRuntime.fnPointer(callback)
        val mem = Memory(BLOCK_LITERAL_SIZE_ARM64).apply {
            setPointer(0, ObjcRuntime.nsConcreteGlobalBlock)
            setInt(POINTER_BYTES.toLong(), BLOCK_FLAGS_NO_SIGNATURE)
            setInt(POINTER_BYTES.toLong() + INT_BYTES, 0)
            setPointer(POINTER_BYTES.toLong() + INT_BYTES * 2L, invokePointer)
            setPointer(POINTER_BYTES.toLong() + INT_BYTES * 2L + POINTER_BYTES, descriptor)
        }
        return BlockKeepalive(mem, descriptor, callback)
    }

    /** Holds the heap-allocated bits the Objective-C block points at. */
    data class BlockKeepalive(
        val block: Memory,
        val descriptor: Memory,
        val callback: Callback,
    )

    /** `Suppress("UnusedParameter")` shim — JNA reads via reflection. */
    @Suppress("UnusedParameter")
    fun warmthHack(@Suppress("unused") x: LongByReference?): Unit = Unit
}
