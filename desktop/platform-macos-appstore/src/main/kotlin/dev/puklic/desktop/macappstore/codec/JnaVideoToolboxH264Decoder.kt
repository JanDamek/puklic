package dev.puklic.desktop.macappstore.codec

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import dev.puklic.desktop.macappstore.bridge.CoreFoundation
import dev.puklic.desktop.macappstore.bridge.CoreMedia
import dev.puklic.desktop.macappstore.bridge.CoreVideo
import dev.puklic.desktop.macappstore.bridge.VideoToolbox
import dev.puklic.voice.codec.video.H264Decoder
import dev.puklic.voice.codec.video.H264DecoderFactory

/**
 * Mac App Store variant of [H264Decoder] backed by `VTDecompressionSession`
 * via JNA.
 *
 * Input: Annex-B NAL units (`00 00 00 01 ‖ NAL`). The decoder splits each
 * input into NALs, classifies them (SPS/PPS/IDR/slice), builds a
 * `CMVideoFormatDescription` from the first SPS+PPS pair, and pushes the
 * remaining payload as a `CMSampleBuffer` with 4-byte length-prefix (AVCC)
 * payload.
 *
 * Output: ARGB `Int` per pixel — converted from VT's BGRA `CVPixelBuffer`
 * output in [decompressionCallback]. Mirrors the iOS-side decoder pattern of
 * FP-5, scaled down to the JVM consumer model.
 */
public class JnaVideoToolboxH264Decoder(
    private val width: Int,
    private val height: Int,
) : H264Decoder {

    private val cf: CoreFoundation = CoreFoundation.INSTANCE
    private val cm: CoreMedia = CoreMedia.INSTANCE
    private val cv: CoreVideo = CoreVideo.INSTANCE
    private val vt: VideoToolbox = VideoToolbox.INSTANCE

    private var session: Pointer? = null
    private var formatDescription: Pointer? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var closed: Boolean = false
    private var lastFrame: IntArray? = null

    /** Strong-held trampoline so the VT queue can dispatch into the JVM. */
    @Suppress("unused")
    private val decompressionCallback = object : VideoToolbox.DecompressionOutputCallback {
        override fun invoke(
            outputCallbackRefCon: Pointer?,
            sourceFrameRefCon: Pointer?,
            status: Int,
            infoFlags: Int,
            imageBuffer: Pointer?,
            presentationTimeStamp: CoreMedia.CMTime,
            presentationDuration: CoreMedia.CMTime,
        ) {
            if (status != 0 || imageBuffer == null) return
            lastFrame = readBgraAsArgb(imageBuffer)
        }
    }

    init {
        require(width > 0 && height > 0) { "width/height must be > 0" }
    }

    public override fun decode(annexBNalUnit: ByteArray): IntArray? {
        check(!closed) { "JnaVideoToolboxH264Decoder is closed" }
        val nals = splitAnnexB(annexBNalUnit)
        if (nals.isEmpty()) return null

        // Classify NALs by `nal_unit_type` (low 5 bits of first byte).
        val pictureNals = ArrayList<ByteArray>(nals.size)
        for (nal in nals) {
            if (nal.isEmpty()) continue
            when (nal[0].toInt() and 0x1F) {
                NAL_TYPE_SPS -> sps = nal
                NAL_TYPE_PPS -> pps = nal
                else -> pictureNals += nal
            }
        }
        ensureSession()
        if (session == null || pictureNals.isEmpty()) return null

        // Build AVCC payload: each NAL prefixed with 4-byte big-endian length.
        val avccSize = pictureNals.sumOf { VideoToolbox.AVCC_LENGTH_PREFIX_SIZE + it.size }
        val avcc = ByteArray(avccSize)
        var off = 0
        for (nal in pictureNals) {
            val len = nal.size
            avcc[off] = ((len ushr 24) and 0xFF).toByte()
            avcc[off + 1] = ((len ushr 16) and 0xFF).toByte()
            avcc[off + 2] = ((len ushr 8) and 0xFF).toByte()
            avcc[off + 3] = (len and 0xFF).toByte()
            nal.copyInto(avcc, off + VideoToolbox.AVCC_LENGTH_PREFIX_SIZE)
            off += VideoToolbox.AVCC_LENGTH_PREFIX_SIZE + len
        }

        val blockMem = Memory(avcc.size.toLong()).apply { write(0L, avcc, 0, avcc.size) }
        val blockOut = PointerByReference()
        val rc1 = cm.CMBlockBufferCreateWithMemoryBlock(
            allocator = null,
            memoryBlock = blockMem,
            blockLength = avcc.size.toLong(),
            blockAllocator = null,
            customBlockSource = null,
            offsetToData = 0L,
            dataLength = avcc.size.toLong(),
            flags = 0,
            blockBufferOut = blockOut,
        )
        if (rc1 != 0) return null
        val block = blockOut.value ?: return null

        val sampleOut = PointerByReference()
        val rc2 = cm.CMSampleBufferCreateReady(
            allocator = null,
            dataBuffer = block,
            formatDescription = formatDescription,
            numSamples = 1L,
            numSampleTimingEntries = 0L,
            sampleTimingArray = null,
            numSampleSizeEntries = 0L,
            sampleSizeArray = null,
            sampleBufferOut = sampleOut,
        )
        cf.CFRelease(block)
        if (rc2 != 0) return null
        val sample = sampleOut.value ?: return null

        lastFrame = null
        val rc3 = vt.VTDecompressionSessionDecodeFrame(
            session = session,
            sampleBuffer = sample,
            decodeFlags = 0,
            sourceFrameRefCon = null,
            infoFlagsOut = null,
        )
        cf.CFRelease(sample)
        if (rc3 != 0) return null
        return lastFrame
    }

    public override fun close() {
        if (closed) return
        closed = true
        session?.let {
            vt.VTDecompressionSessionFinishDelayedFrames(it)
            vt.VTDecompressionSessionInvalidate(it)
            cf.CFRelease(it)
        }
        formatDescription?.let { cf.CFRelease(it) }
        session = null
        formatDescription = null
    }

    // --- internals -----------------------------------------------------

    private fun ensureSession() {
        val haveSps = sps
        val havePps = pps
        if (session != null || haveSps == null || havePps == null) return

        // Pack SPS+PPS pointers + sizes into contiguous arrays VT expects.
        val spsMem = Memory(haveSps.size.toLong()).apply { write(0L, haveSps, 0, haveSps.size) }
        val ppsMem = Memory(havePps.size.toLong()).apply { write(0L, havePps, 0, havePps.size) }
        val pointers = Memory(POINTER_BYTES * 2).apply {
            setPointer(0L, spsMem)
            setPointer(POINTER_BYTES, ppsMem)
        }
        val sizes = Memory(LONG_BYTES * 2).apply {
            setLong(0L, haveSps.size.toLong())
            setLong(LONG_BYTES, havePps.size.toLong())
        }
        val fmtOut = PointerByReference()
        val rcFmt = cm.CMVideoFormatDescriptionCreateFromH264ParameterSets(
            allocator = null,
            parameterSetCount = 2L,
            parameterSetPointers = pointers,
            parameterSetSizes = sizes,
            nalUnitHeaderLength = VideoToolbox.AVCC_LENGTH_PREFIX_SIZE,
            formatDescriptionOut = fmtOut,
        )
        if (rcFmt != 0) return
        formatDescription = fmtOut.value ?: return

        val sessionOut = PointerByReference()
        val rcSes = vt.VTDecompressionSessionCreate(
            allocator = null,
            videoFormatDescription = formatDescription,
            videoDecoderSpecification = null,
            destinationImageBufferAttributes = null,
            outputCallback = com.sun.jna.CallbackReference.getFunctionPointer(decompressionCallback),
            decompressionSessionOut = sessionOut,
        )
        if (rcSes != 0) return
        session = sessionOut.value
    }

    private fun readBgraAsArgb(buffer: Pointer): IntArray? {
        cv.CVPixelBufferLockBaseAddress(buffer, 1L) // kCVPixelBufferLock_ReadOnly
        try {
            val w = cv.CVPixelBufferGetWidth(buffer).toInt()
            val h = cv.CVPixelBufferGetHeight(buffer).toInt()
            val rowStride = cv.CVPixelBufferGetBytesPerRow(buffer).toInt()
            val base = cv.CVPixelBufferGetBaseAddress(buffer) ?: return null
            val out = IntArray(w * h)
            for (row in 0 until h) {
                val rowBytes = base.getByteArray(row.toLong() * rowStride, w * BYTES_PER_BGRA_PIXEL)
                for (col in 0 until w) {
                    val i = col * BYTES_PER_BGRA_PIXEL
                    val b = rowBytes[i].toInt() and 0xFF
                    val g = rowBytes[i + 1].toInt() and 0xFF
                    val r = rowBytes[i + 2].toInt() and 0xFF
                    val a = rowBytes[i + 3].toInt() and 0xFF
                    out[row * w + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return out
        } finally {
            cv.CVPixelBufferUnlockBaseAddress(buffer, 1L)
        }
    }

    private fun splitAnnexB(payload: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(2)
        var i = 0
        var lastStart = -1
        while (i + START_CODE_3 < payload.size) {
            val isStart4 = i + START_CODE_4 <= payload.size &&
                payload[i].toInt() == 0 && payload[i + 1].toInt() == 0 &&
                payload[i + 2].toInt() == 0 && payload[i + 3].toInt() == 1
            val isStart3 = !isStart4 &&
                payload[i].toInt() == 0 && payload[i + 1].toInt() == 0 && payload[i + 2].toInt() == 1
            if (isStart4 || isStart3) {
                if (lastStart >= 0) out += payload.copyOfRange(lastStart, i)
                lastStart = i + if (isStart4) START_CODE_4 else START_CODE_3
                i = lastStart
            } else {
                i += 1
            }
        }
        if (lastStart in 0 until payload.size) out += payload.copyOfRange(lastStart, payload.size)
        // The caller may pass a bare NAL without start code.
        if (out.isEmpty() && payload.isNotEmpty()) out += payload
        return out
    }

    private companion object {
        const val NAL_TYPE_SPS: Int = 7
        const val NAL_TYPE_PPS: Int = 8
        const val START_CODE_3: Int = 3
        const val START_CODE_4: Int = 4
        const val POINTER_BYTES: Long = 8
        const val LONG_BYTES: Long = 8
        const val BYTES_PER_BGRA_PIXEL: Int = 4
    }
}

/** Factory for [JnaVideoToolboxH264Decoder] (parallel to the encoder factory). */
public object JnaVideoToolboxH264DecoderFactory : H264DecoderFactory {
    public override fun create(width: Int, height: Int): H264Decoder =
        JnaVideoToolboxH264Decoder(width, height)
}
