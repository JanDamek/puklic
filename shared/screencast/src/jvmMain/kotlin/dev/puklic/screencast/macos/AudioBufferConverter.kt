package dev.puklic.screencast.macos

import com.sun.jna.Memory
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Converts a `CMSampleBuffer` carrying interleaved Float32 stereo PCM at
 * 48 kHz (the format `SCStreamConfiguration.sampleRate=48000,
 * channelCount=2` requests) into the `ShortArray` PCM16 frames the
 * Soundshare RTP sender consumes.
 *
 * No third-party audio library — the only operation needed is a clamped
 * float-to-S16 conversion, which is straightforward.
 */
internal object AudioBufferConverter {

    /** `kAudio_OK`. */
    private const val NO_ERR: Int = 0

    /** `AudioBufferList` minimal layout: `UInt32 mNumberBuffers + AudioBuffer[1]`. */
    private const val ABL_SIZE_ONE_BUFFER: Long = 4 + 4 + 4 + 8 // mNumberBuffers + (mNumberChannels + mDataByteSize + mData)

    /**
     * Pulls the interleaved Float32 PCM out of [sampleBuffer] and returns it
     * as `ShortArray` in S16 host-endian. Returns an empty array if the
     * buffer carries no audio bytes.
     */
    fun toPcm16(sampleBuffer: Pointer): ShortArray {
        val sizeOut = LongByReference(0)
        val blockOut = PointerByReference()
        // First call with bufferListOut=NULL to obtain the required buffer-list size.
        val sizingRc = CoreMedia.coreMedia.CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            sizeOut,
            null,
            NativeLong(0),
            null,
            null,
            0,
            null,
        )
        if (sizingRc != NO_ERR || sizeOut.value <= 0) return ShortArray(0)
        val abl = Memory(sizeOut.value)
        val fillRc = CoreMedia.coreMedia.CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            null,
            abl,
            NativeLong(sizeOut.value),
            null,
            null,
            0,
            blockOut,
        )
        if (fillRc != NO_ERR) return ShortArray(0)
        try {
            val nBuffers = abl.getInt(0L)
            if (nBuffers < 1) return ShortArray(0)
            // First AudioBuffer struct starts at offset 4 (after mNumberBuffers, which the
            // C ABI pads to 8 on 64-bit because the following struct contains a pointer).
            val firstBuffer = ABL_HEADER_SIZE
            val byteSize = abl.getInt((firstBuffer + 4).toLong())
            val data = abl.getPointer((firstBuffer + 8).toLong())
            if (byteSize <= 0 || data == null || Pointer.nativeValue(data) == 0L) return ShortArray(0)
            val floats = data.getFloatArray(0, byteSize / FLOAT_BYTES)
            return floats.toPcm16()
        } finally {
            blockOut.value?.let { Foundation.release(it) }
        }
    }

    /** mNumberBuffers (UInt32) + 4 bytes pad → first AudioBuffer starts at offset 8 on arm64. */
    private const val ABL_HEADER_SIZE: Int = 8

    private const val FLOAT_BYTES: Int = 4
    private const val PCM16_MAX: Int = 32_767
    private const val PCM16_MIN: Int = -32_768

    /**
     * Float32 [-1.0, 1.0] → S16 [-32768, 32767] with clamping. Out-of-range
     * floats are clipped (Discord clients clip too, no audible difference).
     */
    private fun FloatArray.toPcm16(): ShortArray {
        val out = ShortArray(this.size)
        for (i in indices) {
            val scaled = (this[i] * PCM16_MAX).toInt()
            out[i] = scaled.coerceIn(PCM16_MIN, PCM16_MAX).toShort()
        }
        return out
    }
}
