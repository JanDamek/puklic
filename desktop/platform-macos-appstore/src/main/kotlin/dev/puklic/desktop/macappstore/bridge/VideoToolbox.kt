package dev.puklic.desktop.macappstore.bridge

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * JNA bindings for VideoToolbox.framework — the H.264 codec engine on Apple
 * platforms. Mirrors the symbols the iOS impl ([IosH264Encoder]) consumes
 * via Kotlin/Native cinterop.
 */
internal interface VideoToolbox : Library {

    /** `VTCompressionOutputCallback` — fires on VT's internal queue when a compressed sample is ready. */
    interface CompressionOutputCallback : Callback {
        fun invoke(
            outputCallbackRefCon: Pointer?,
            sourceFrameRefCon: Pointer?,
            status: Int,
            infoFlags: Int,
            sampleBuffer: Pointer?,
        )
    }

    /** `VTDecompressionOutputCallback` — fires on VT's internal queue with the decoded image buffer. */
    interface DecompressionOutputCallback : Callback {
        fun invoke(
            outputCallbackRefCon: Pointer?,
            sourceFrameRefCon: Pointer?,
            status: Int,
            infoFlags: Int,
            imageBuffer: Pointer?,
            presentationTimeStamp: CoreMedia.CMTime,
            presentationDuration: CoreMedia.CMTime,
        )
    }

    fun VTCompressionSessionCreate(
        allocator: Pointer?,
        width: Int,
        height: Int,
        codecType: Int,
        encoderSpecification: Pointer?,
        sourceImageBufferAttributes: Pointer?,
        compressedDataAllocator: Pointer?,
        outputCallback: CompressionOutputCallback,
        outputCallbackRefCon: Pointer?,
        compressionSessionOut: PointerByReference,
    ): Int

    fun VTCompressionSessionEncodeFrame(
        session: Pointer?,
        imageBuffer: Pointer?,
        presentationTimeStamp: CoreMedia.CMTime,
        duration: CoreMedia.CMTime,
        frameProperties: Pointer?,
        sourceFrameRefcon: Pointer?,
        infoFlagsOut: Pointer?,
    ): Int

    fun VTCompressionSessionCompleteFrames(session: Pointer?, completeUntilPts: CoreMedia.CMTime): Int
    fun VTCompressionSessionInvalidate(session: Pointer?)

    fun VTDecompressionSessionCreate(
        allocator: Pointer?,
        videoFormatDescription: Pointer?,
        videoDecoderSpecification: Pointer?,
        destinationImageBufferAttributes: Pointer?,
        outputCallback: Pointer?,
        decompressionSessionOut: PointerByReference,
    ): Int

    fun VTDecompressionSessionDecodeFrame(
        session: Pointer?,
        sampleBuffer: Pointer?,
        decodeFlags: Int,
        sourceFrameRefCon: Pointer?,
        infoFlagsOut: Pointer?,
    ): Int

    fun VTDecompressionSessionInvalidate(session: Pointer?)
    fun VTDecompressionSessionFinishDelayedFrames(session: Pointer?): Int

    fun VTSessionSetProperty(session: Pointer?, propertyKey: Pointer?, propertyValue: Pointer?): Int

    companion object {
        val INSTANCE: VideoToolbox by lazy {
            Native.load("VideoToolbox", VideoToolbox::class.java)
        }

        /** `kCMVideoCodecType_H264` four-char-code 'avc1' = 0x61_76_63_31. */
        const val CODEC_TYPE_H264: Int = 0x6176_6331

        /** AVCC length-prefix size used by VT compressed output + decoder input. */
        const val AVCC_LENGTH_PREFIX_SIZE: Int = 4

        /** Annex-B start code size (4-byte 0x00000001). */
        const val ANNEXB_START_CODE_SIZE: Int = 4

        /** RTP clock for H.264 video per RFC 6184 (90 kHz). */
        const val RTP_VIDEO_CLOCK_HZ: Int = 90_000

        // VT property keys — each is a `CFStringRef` global. Dereference via the framework dylib.
        val K_VT_PROP_REAL_TIME: Pointer by lazy { vtSymbol("kVTCompressionPropertyKey_RealTime") }
        val K_VT_PROP_ALLOW_REORDER: Pointer by lazy { vtSymbol("kVTCompressionPropertyKey_AllowFrameReordering") }
        val K_VT_PROP_PROFILE_LEVEL: Pointer by lazy { vtSymbol("kVTCompressionPropertyKey_ProfileLevel") }
        val K_VT_PROP_AVG_BITRATE: Pointer by lazy { vtSymbol("kVTCompressionPropertyKey_AverageBitRate") }
        val K_VT_PROP_MAX_KEYFRAME_INTERVAL: Pointer by lazy {
            vtSymbol("kVTCompressionPropertyKey_MaxKeyFrameInterval")
        }
        val K_VT_PROP_EXPECTED_FRAMERATE: Pointer by lazy {
            vtSymbol("kVTCompressionPropertyKey_ExpectedFrameRate")
        }
        val K_VT_PROFILE_H264_BASELINE_AUTO: Pointer by lazy {
            vtSymbol("kVTProfileLevel_H264_Baseline_AutoLevel")
        }

        private fun vtSymbol(name: String): Pointer =
            com.sun.jna.NativeLibrary.getInstance("VideoToolbox")
                .getGlobalVariableAddress(name)
                .getPointer(0)
    }
}
