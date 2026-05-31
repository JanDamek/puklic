package dev.puklic.desktop.macappstore.bridge

import com.sun.jna.Memory
import com.sun.jna.Pointer

/**
 * AVFAudio (AVAudioEngine + AVAudioPCMBuffer + AVAudioFormat + AVAudioConverter)
 * selectors and small helpers used by the Mac App Store audio capture / playback
 * impls (FP-14h-3).
 *
 * Selectors are resolved lazily so the bridge class loads on any host (only the
 * actual `start()` calls force resolution against the AVFAudio framework — present
 * on every macOS since 10.10).
 *
 * Per architect plan §5.2 in
 * `docs/03_infrastructure/architect-reports/2026-05-29-fp14h-3-implementation.md`.
 */
internal object AVFAudio {

    // Class lookups
    val classAVAudioEngine: Pointer by lazy { AVAudioObjcRuntime.getClass("AVAudioEngine") }
    val classAVAudioPlayerNode: Pointer by lazy { AVAudioObjcRuntime.getClass("AVAudioPlayerNode") }
    val classAVAudioFormat: Pointer by lazy { AVAudioObjcRuntime.getClass("AVAudioFormat") }
    val classAVAudioPCMBuffer: Pointer by lazy { AVAudioObjcRuntime.getClass("AVAudioPCMBuffer") }
    val classAVAudioConverter: Pointer by lazy { AVAudioObjcRuntime.getClass("AVAudioConverter") }

    // Selectors
    val selInit: Pointer by lazy { AVAudioObjcRuntime.sel("init") }
    val selRelease: Pointer by lazy { AVAudioObjcRuntime.sel("release") }
    val selAlloc: Pointer by lazy { AVAudioObjcRuntime.sel("alloc") }

    // AVAudioEngine
    val selInputNode: Pointer by lazy { AVAudioObjcRuntime.sel("inputNode") }
    val selOutputNode: Pointer by lazy { AVAudioObjcRuntime.sel("outputNode") }
    val selMainMixerNode: Pointer by lazy { AVAudioObjcRuntime.sel("mainMixerNode") }
    val selAttachNode: Pointer by lazy { AVAudioObjcRuntime.sel("attachNode:") }
    val selDetachNode: Pointer by lazy { AVAudioObjcRuntime.sel("detachNode:") }
    val selConnectToFormat: Pointer by lazy { AVAudioObjcRuntime.sel("connect:to:format:") }
    val selPrepare: Pointer by lazy { AVAudioObjcRuntime.sel("prepare") }
    val selStartAndReturnError: Pointer by lazy { AVAudioObjcRuntime.sel("startAndReturnError:") }
    val selStop: Pointer by lazy { AVAudioObjcRuntime.sel("stop") }

    // AVAudioInputNode tap
    val selInstallTap: Pointer by lazy {
        AVAudioObjcRuntime.sel("installTapOnBus:bufferSize:format:block:")
    }
    val selRemoveTap: Pointer by lazy { AVAudioObjcRuntime.sel("removeTapOnBus:") }

    // AVAudioPlayerNode
    val selScheduleBuffer: Pointer by lazy {
        AVAudioObjcRuntime.sel("scheduleBuffer:completionHandler:")
    }
    val selPlay: Pointer by lazy { AVAudioObjcRuntime.sel("play") }
    val selPause: Pointer by lazy { AVAudioObjcRuntime.sel("pause") }
    val selPlayerStop: Pointer by lazy { AVAudioObjcRuntime.sel("stop") }

    // AVAudioFormat
    val selInitWithCommonFormat: Pointer by lazy {
        AVAudioObjcRuntime.sel("initWithCommonFormat:sampleRate:channels:interleaved:")
    }
    val selSampleRate: Pointer by lazy { AVAudioObjcRuntime.sel("sampleRate") }
    val selChannelCount: Pointer by lazy { AVAudioObjcRuntime.sel("channelCount") }
    val selFormatOfNode: Pointer by lazy { AVAudioObjcRuntime.sel("inputFormatForBus:") }
    val selOutputFormatForBus: Pointer by lazy { AVAudioObjcRuntime.sel("outputFormatForBus:") }

    // AVAudioPCMBuffer
    val selInitWithPCMFormatFrameCap: Pointer by lazy {
        AVAudioObjcRuntime.sel("initWithPCMFormat:frameCapacity:")
    }
    val selFrameLength: Pointer by lazy { AVAudioObjcRuntime.sel("frameLength") }
    val selSetFrameLength: Pointer by lazy { AVAudioObjcRuntime.sel("setFrameLength:") }
    val selFloatChannelData: Pointer by lazy { AVAudioObjcRuntime.sel("floatChannelData") }
    val selInt16ChannelData: Pointer by lazy { AVAudioObjcRuntime.sel("int16ChannelData") }
    val selFormat: Pointer by lazy { AVAudioObjcRuntime.sel("format") }

    // AVAudioConverter
    val selInitFromToFormat: Pointer by lazy {
        AVAudioObjcRuntime.sel("initFromFormat:toFormat:")
    }
    val selConvertToBufferError: Pointer by lazy {
        AVAudioObjcRuntime.sel("convertToBuffer:error:withInputFromBlock:")
    }

    /** `AVAudioCommonFormat` enum value for `AVAudioPCMFormatInt16` (= 2). Documented Apple. */
    const val PCM_FORMAT_INT16: Long = 2L

    /** AVAudioConverterInputStatus_HaveData = 0, _NoDataNow = 1, _EndOfStream = 2. */
    const val INPUT_STATUS_HAVE_DATA: Int = 0
    const val INPUT_STATUS_NO_DATA_NOW: Int = 1

    /**
     * Allocate `[[AVAudioFormat alloc] initWithCommonFormat:sampleRate:channels:interleaved:]`.
     * Returns the strong-referenced format; caller owns it (release via [release]).
     */
    fun makeFormat(
        commonFormat: Long,
        sampleRate: Double,
        channels: Int,
        interleaved: Boolean,
    ): Pointer {
        val allocd = AVAudioObjcRuntime.alloc(classAVAudioFormat)
        val initd = AVAudioObjcRuntime.msgSendInitFormat(
            target = allocd,
            sel = selInitWithCommonFormat,
            commonFormat = commonFormat,
            sampleRate = sampleRate,
            channels = channels,
            interleaved = if (interleaved) 1 else 0,
        )
        check(Pointer.nativeValue(initd) != 0L) { "AVAudioFormat init returned nil" }
        return initd
    }

    fun release(obj: Pointer?) {
        if (obj == null || Pointer.nativeValue(obj) == 0L) return
        AVAudioObjcRuntime.msgSendVoid(obj, selRelease)
    }

    // Apple Blocks ABI layout on arm64/x86_64 (LP64):
    //   BlockLiteral = { void* isa; int flags; int reserved; void* invoke; void* descriptor; } = 40 bytes
    //   BlockDescriptor = { unsigned long reserved; unsigned long size; void* signature; } = 24 bytes
    private const val POINTER_BYTES: Int = 8
    private const val INT_BYTES: Int = 4
    private const val LONG_BYTES: Int = 8
    private const val BLOCK_LITERAL_SIZE: Long = 40L
    private const val BLOCK_DESCRIPTOR_SIZE: Long = 24L
    /** No `BLOCK_HAS_SIGNATURE` since signature is null; only `BLOCK_IS_GLOBAL`. */
    private const val BLOCK_FLAGS_NO_SIGNATURE: Int = 1 shl 28

    /**
     * Build a synchronous `_NSConcreteGlobalBlock` literal whose invoke trampoline is
     * [callback]. Mirrors the layout used by
     * `shared/screencast/.../ScreenCaptureKitBridge.kt:150-169`. Caller MUST hold a
     * strong reference to the returned [BlockHandle] until the Apple runtime no longer
     * invokes the block (otherwise JNA may GC the trampoline and the Memory backing).
     */
    fun makeBlock(callback: com.sun.jna.Callback): BlockHandle {
        val descMem = Memory(BLOCK_DESCRIPTOR_SIZE).apply {
            setLong(0L, 0L)                                 // reserved
            setLong(LONG_BYTES.toLong(), BLOCK_LITERAL_SIZE) // size
            setPointer((LONG_BYTES * 2).toLong(), null)      // signature
        }
        val invokePointer = AVAudioObjcRuntime.fnPointer(callback)
        val literalMem = Memory(BLOCK_LITERAL_SIZE).apply {
            setPointer(0L, AVAudioObjcRuntime.nsConcreteGlobalBlock)
            setInt(POINTER_BYTES.toLong(), BLOCK_FLAGS_NO_SIGNATURE)
            setInt((POINTER_BYTES + INT_BYTES).toLong(), 0)
            setPointer((POINTER_BYTES + INT_BYTES * 2).toLong(), invokePointer)
            setPointer((POINTER_BYTES + INT_BYTES * 2 + POINTER_BYTES).toLong(), descMem)
        }
        return BlockHandle(literalMem, descMem, callback)
    }

    /**
     * Holds memory + callback alive for the lifetime of an Apple block. The
     * [literal] pointer is the value passed to Apple APIs that accept `id block`.
     */
    class BlockHandle(
        val literal: Memory,
        @Suppress("unused") val descriptor: Memory,
        @Suppress("unused") val callback: com.sun.jna.Callback,
    ) {
        val pointer: Pointer get() = literal
    }
}
