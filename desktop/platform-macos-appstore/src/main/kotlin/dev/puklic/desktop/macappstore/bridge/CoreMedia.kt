package dev.puklic.desktop.macappstore.bridge

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * JNA bindings for the CoreMedia.framework symbols used by VideoToolbox encoder
 * + decoder paths.
 *
 * Includes:
 *  - CMTime construction (`CMTimeMake`).
 *  - CMBlockBuffer / CMSampleBuffer accessors (read AVCC bytes + sample
 *    attachments out of encoder output).
 *  - CMSampleBufferCreate + CMVideoFormatDescriptionCreate path used by the
 *    decoder to wrap incoming Annex-B NALs (after rewriting to length-prefixed
 *    AVCC) into a CMSampleBuffer pushed to VTDecompressionSession.
 *  - CMVideoFormatDescriptionGetH264ParameterSetAtIndex — keyframe SPS/PPS
 *    extraction, mirroring FP-5 IosH264Encoder.
 */
internal interface CoreMedia : Library {

    @Structure.FieldOrder("value", "timescale", "flags", "epoch")
    open class CMTime : Structure(), Structure.ByValue {
        @JvmField var value: Long = 0
        @JvmField var timescale: Int = 0
        @JvmField var flags: Int = 0
        @JvmField var epoch: Long = 0
    }

    fun CMTimeMake(value: Long, timescale: Int): CMTime

    fun CMSampleBufferGetDataBuffer(sample: Pointer?): Pointer?
    fun CMSampleBufferGetFormatDescription(sample: Pointer?): Pointer?
    fun CMSampleBufferGetSampleAttachmentsArray(sample: Pointer?, createIfNecessary: Byte): Pointer?

    fun CMBlockBufferGetDataLength(block: Pointer?): Long
    fun CMBlockBufferGetDataPointer(
        block: Pointer?,
        offset: Long,
        lengthAtOffsetOut: LongByReference?,
        totalLengthOut: LongByReference?,
        dataPointerOut: PointerByReference,
    ): Int

    fun CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
        videoDesc: Pointer?,
        index: Long,
        parameterSetPointerOut: PointerByReference?,
        parameterSetSizeOut: LongByReference?,
        parameterSetCountOut: LongByReference?,
        nalUnitHeaderLengthOut: IntByReference?,
    ): Int

    fun CMVideoFormatDescriptionCreateFromH264ParameterSets(
        allocator: Pointer?,
        parameterSetCount: Long,
        parameterSetPointers: Pointer?,
        parameterSetSizes: Pointer?,
        nalUnitHeaderLength: Int,
        formatDescriptionOut: PointerByReference,
    ): Int

    fun CMBlockBufferCreateWithMemoryBlock(
        allocator: Pointer?,
        memoryBlock: Pointer?,
        blockLength: Long,
        blockAllocator: Pointer?,
        customBlockSource: Pointer?,
        offsetToData: Long,
        dataLength: Long,
        flags: Int,
        blockBufferOut: PointerByReference,
    ): Int

    fun CMSampleBufferCreateReady(
        allocator: Pointer?,
        dataBuffer: Pointer?,
        formatDescription: Pointer?,
        numSamples: Long,
        numSampleTimingEntries: Long,
        sampleTimingArray: Pointer?,
        numSampleSizeEntries: Long,
        sampleSizeArray: Pointer?,
        sampleBufferOut: PointerByReference,
    ): Int

    companion object {
        val INSTANCE: CoreMedia by lazy { Native.load("CoreMedia", CoreMedia::class.java) }

        /** `kCMSampleAttachmentKey_NotSync` — value `false` indicates the sample is an IDR keyframe. */
        val K_CM_SAMPLE_ATTACHMENT_KEY_NOT_SYNC: Pointer by lazy {
            CoreFoundation.readGlobalPointer("kCMSampleAttachmentKey_NotSync")
        }
    }
}
