package dev.puklic.desktop.macappstore.codec

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import dev.puklic.desktop.macappstore.bridge.CoreFoundation
import dev.puklic.desktop.macappstore.bridge.CoreMedia
import dev.puklic.desktop.macappstore.bridge.CoreVideo
import dev.puklic.desktop.macappstore.bridge.VideoToolbox
import dev.puklic.voice.codec.video.H264Encoder
import dev.puklic.voice.codec.video.H264EncoderFactory
import dev.puklic.voice.transport.EncodedFrame

/**
 * Mac App Store variant of [H264Encoder] backed by `VTCompressionSession` via
 * JNA. Direct port of the iOS impl
 * [`IosH264Encoder`][1] — Baseline profile, real-time, no frame reordering,
 * 2-second IDR cadence. Input: I420 (planar YUV 4:2:0). Output: Annex-B NAL
 * units with 4-byte start codes; SPS+PPS are inlined ahead of each first
 * keyframe.
 *
 * Threading: VT invokes [compressionCallback] on its internal serial queue.
 * Under `RealTime=true + AllowFrameReordering=false` the callback fires before
 * `VTCompressionSessionEncodeFrame` returns, so [encode] picks up the
 * resulting frame from [outputs] synchronously. Access to [outputs] is
 * synchronised on this instance.
 *
 * [1]: shared/voice-codec/src/iosMain/kotlin/dev/puklic/voice/codec/video/IosH264Encoder.kt
 */
public class JnaVideoToolboxH264Encoder(
    private val width: Int,
    private val height: Int,
    bitrateKbps: Int,
    framerate: Int,
) : H264Encoder {

    private val cf: CoreFoundation = CoreFoundation.INSTANCE
    private val cm: CoreMedia = CoreMedia.INSTANCE
    private val cv: CoreVideo = CoreVideo.INSTANCE
    private val vt: VideoToolbox = VideoToolbox.INSTANCE

    private val framerateInternal: Int = framerate
    private var frameIndex: Long = 0
    private var sentParameterSets: Boolean = false
    private var lastTs90k: Int = 0
    private var closed: Boolean = false
    private val outputs: ArrayDeque<EncodedFrame> = ArrayDeque(4)

    /** Held strong so VT's queue can call back into the JVM without the trampoline being GC'd. */
    private val compressionCallback = object : VideoToolbox.CompressionOutputCallback {
        override fun invoke(
            outputCallbackRefCon: Pointer?,
            sourceFrameRefCon: Pointer?,
            status: Int,
            infoFlags: Int,
            sampleBuffer: Pointer?,
        ) {
            if (status != 0 || sampleBuffer == null) return
            handleEncoderOutput(sampleBuffer)
        }
    }

    private val session: Pointer

    init {
        require(width > 0 && height > 0) { "width/height must be > 0" }
        require(bitrateKbps > 0) { "bitrateKbps must be > 0" }
        require(framerate > 0) { "framerate must be > 0" }
        require(width % 2 == 0 && height % 2 == 0) { "width/height must be even for I420" }

        val sessionOut = PointerByReference()
        val rc = vt.VTCompressionSessionCreate(
            allocator = null,
            width = width,
            height = height,
            codecType = VideoToolbox.CODEC_TYPE_H264,
            encoderSpecification = null,
            sourceImageBufferAttributes = null,
            compressedDataAllocator = null,
            outputCallback = compressionCallback,
            outputCallbackRefCon = null,
            compressionSessionOut = sessionOut,
        )
        check(rc == 0) { "VTCompressionSessionCreate failed: OSStatus=$rc" }
        session = sessionOut.value ?: error("VTCompressionSessionCreate returned null session")

        setBool(VideoToolbox.K_VT_PROP_REAL_TIME, true)
        setBool(VideoToolbox.K_VT_PROP_ALLOW_REORDER, false)
        setRawProperty(VideoToolbox.K_VT_PROP_PROFILE_LEVEL, VideoToolbox.K_VT_PROFILE_H264_BASELINE_AUTO)
        setInt(VideoToolbox.K_VT_PROP_AVG_BITRATE, bitrateKbps * 1000)
        setInt(VideoToolbox.K_VT_PROP_MAX_KEYFRAME_INTERVAL, framerate * 2)
        setInt(VideoToolbox.K_VT_PROP_EXPECTED_FRAMERATE, framerate)
    }

    public override fun encode(yuv420p: ByteArray): EncodedFrame? {
        check(!closed) { "JnaVideoToolboxH264Encoder is closed" }
        val expected = width * height * 3 / 2
        require(yuv420p.size == expected) {
            "Expected $expected I420 bytes for ${width}x${height}, got ${yuv420p.size}"
        }
        val pixelBuffer = createNv12PixelBuffer(yuv420p)
        try {
            val pts = cm.CMTimeMake(frameIndex, framerateInternal)
            val duration = cm.CMTimeMake(1L, framerateInternal)
            frameIndex += 1
            val rc = vt.VTCompressionSessionEncodeFrame(
                session = session,
                imageBuffer = pixelBuffer,
                presentationTimeStamp = pts,
                duration = duration,
                frameProperties = null,
                sourceFrameRefcon = null,
                infoFlagsOut = null,
            )
            check(rc == 0) { "VTCompressionSessionEncodeFrame failed: OSStatus=$rc" }
        } finally {
            cf.CFRelease(pixelBuffer)
        }
        synchronized(this) {
            return if (outputs.isNotEmpty()) outputs.removeFirst() else null
        }
    }

    public override fun close() {
        if (closed) return
        closed = true
        vt.VTCompressionSessionCompleteFrames(session, cm.CMTimeMake(0L, framerateInternal))
        vt.VTCompressionSessionInvalidate(session)
        cf.CFRelease(session)
    }

    // --- internals -----------------------------------------------------

    private fun createNv12PixelBuffer(i420: ByteArray): Pointer {
        val out = PointerByReference()
        val rc = cv.CVPixelBufferCreate(
            allocator = null,
            width = width.toLong(),
            height = height.toLong(),
            pixelFormatType = CoreVideo.PIXEL_FORMAT_420V_NV12,
            pixelBufferAttributes = null,
            pixelBufferOut = out,
        )
        check(rc == 0) { "CVPixelBufferCreate(NV12) failed: $rc" }
        val buf = out.value ?: error("CVPixelBufferCreate returned null pixel buffer")
        cv.CVPixelBufferLockBaseAddress(buf, 0L)
        try {
            val yPlane = cv.CVPixelBufferGetBaseAddressOfPlane(buf, 0L)
                ?: error("CVPixelBufferGetBaseAddressOfPlane(Y) null")
            val uvPlane = cv.CVPixelBufferGetBaseAddressOfPlane(buf, 1L)
                ?: error("CVPixelBufferGetBaseAddressOfPlane(UV) null")
            val yRowStride = cv.CVPixelBufferGetBytesPerRowOfPlane(buf, 0L).toInt()
            val uvRowStride = cv.CVPixelBufferGetBytesPerRowOfPlane(buf, 1L).toInt()

            val chromaWidth = width / 2
            val chromaHeight = height / 2
            val ySize = width * height
            val chromaSize = chromaWidth * chromaHeight

            // Y plane — row-by-row to honour CVPixelBuffer row padding.
            for (row in 0 until height) {
                yPlane.write(row.toLong() * yRowStride, i420, row * width, width)
            }
            // NV12 UV is interleaved chroma; iOS source has separate U then V planes.
            val uvInterleaved = ByteArray(chromaWidth * 2)
            for (row in 0 until chromaHeight) {
                val uRowOff = ySize + row * chromaWidth
                val vRowOff = ySize + chromaSize + row * chromaWidth
                for (col in 0 until chromaWidth) {
                    uvInterleaved[col * 2] = i420[uRowOff + col]
                    uvInterleaved[col * 2 + 1] = i420[vRowOff + col]
                }
                uvPlane.write(row.toLong() * uvRowStride, uvInterleaved, 0, chromaWidth * 2)
            }
        } finally {
            cv.CVPixelBufferUnlockBaseAddress(buf, 0L)
        }
        return buf
    }

    private fun handleEncoderOutput(sampleBuffer: Pointer) {
        val isKeyframe = sampleIsKeyframe(sampleBuffer)
        val payloads = ArrayList<ByteArray>(4)
        if (isKeyframe && !sentParameterSets) {
            val fmt = cm.CMSampleBufferGetFormatDescription(sampleBuffer)
            if (fmt != null) {
                extractParameterSets(fmt).forEach { payloads += it }
            }
            sentParameterSets = true
        }
        val avcc = copyDataFromSampleBuffer(sampleBuffer)
        if (avcc != null) {
            convertAvccToAnnexB(avcc).forEach { payloads += it }
        }
        if (payloads.isEmpty()) return

        val totalSize = payloads.sumOf { VideoToolbox.ANNEXB_START_CODE_SIZE + it.size }
        val packed = ByteArray(totalSize)
        var off = 0
        for (nalu in payloads) {
            packed[off] = 0
            packed[off + 1] = 0
            packed[off + 2] = 0
            packed[off + 3] = 1
            nalu.copyInto(packed, off + VideoToolbox.ANNEXB_START_CODE_SIZE)
            off += VideoToolbox.ANNEXB_START_CODE_SIZE + nalu.size
        }
        synchronized(this) {
            lastTs90k += VideoToolbox.RTP_VIDEO_CLOCK_HZ / framerateInternal
            outputs.addLast(EncodedFrame(bytes = packed, ts90k = lastTs90k, keyframe = isKeyframe))
        }
    }

    private fun sampleIsKeyframe(sampleBuffer: Pointer): Boolean {
        val attachments = cm.CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, 0) ?: return true
        if (cf.CFArrayGetCount(attachments) == 0L) return true
        val dict = cf.CFArrayGetValueAtIndex(attachments, 0) ?: return true
        val notSync = cf.CFDictionaryGetValue(dict, CoreMedia.K_CM_SAMPLE_ATTACHMENT_KEY_NOT_SYNC)
            ?: return true
        return cf.CFBooleanGetValue(notSync).toInt() == 0
    }

    private fun extractParameterSets(format: Pointer): List<ByteArray> {
        val result = ArrayList<ByteArray>(2)
        val countRef = LongByReference()
        val sizeRef = LongByReference()
        val ptrRef = PointerByReference()
        val s0 = cm.CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            videoDesc = format,
            index = 0L,
            parameterSetPointerOut = ptrRef,
            parameterSetSizeOut = sizeRef,
            parameterSetCountOut = countRef,
            nalUnitHeaderLengthOut = null,
        )
        if (s0 != 0) return result
        val count = countRef.value.toInt()
        if (count <= 0) return result
        ptrRef.value?.let { p -> result += p.getByteArray(0L, sizeRef.value.toInt()) }
        for (i in 1 until count) {
            val pRef = PointerByReference()
            val sRef = LongByReference()
            val s = cm.CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                videoDesc = format,
                index = i.toLong(),
                parameterSetPointerOut = pRef,
                parameterSetSizeOut = sRef,
                parameterSetCountOut = null,
                nalUnitHeaderLengthOut = null,
            )
            if (s != 0) break
            val p = pRef.value ?: break
            result += p.getByteArray(0L, sRef.value.toInt())
        }
        return result
    }

    private fun copyDataFromSampleBuffer(sampleBuffer: Pointer): ByteArray? {
        val block: Pointer = cm.CMSampleBufferGetDataBuffer(sampleBuffer) ?: return null
        val len = cm.CMBlockBufferGetDataLength(block).toInt()
        if (len <= 0) return null
        val ptrRef = PointerByReference()
        val totalRef = LongByReference()
        val lenAtOffsetRef = LongByReference()
        val rc = cm.CMBlockBufferGetDataPointer(
            block = block,
            offset = 0L,
            lengthAtOffsetOut = lenAtOffsetRef,
            totalLengthOut = totalRef,
            dataPointerOut = ptrRef,
        )
        if (rc != 0) return null
        val src = ptrRef.value ?: return null
        return src.getByteArray(0L, len)
    }

    private fun convertAvccToAnnexB(avcc: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(2)
        var off = 0
        while (off + VideoToolbox.AVCC_LENGTH_PREFIX_SIZE <= avcc.size) {
            val len = ((avcc[off].toInt() and 0xFF) shl 24) or
                ((avcc[off + 1].toInt() and 0xFF) shl 16) or
                ((avcc[off + 2].toInt() and 0xFF) shl 8) or
                (avcc[off + 3].toInt() and 0xFF)
            off += VideoToolbox.AVCC_LENGTH_PREFIX_SIZE
            if (len <= 0 || off + len > avcc.size) break
            out += avcc.copyOfRange(off, off + len)
            off += len
        }
        return out
    }

    private fun setBool(key: Pointer, value: Boolean) {
        val ref = if (value) CoreFoundation.K_CF_BOOLEAN_TRUE else CoreFoundation.K_CF_BOOLEAN_FALSE
        val rc = vt.VTSessionSetProperty(session, key, ref)
        check(rc == 0) { "VTSessionSetProperty(bool) failed: OSStatus=$rc" }
    }

    private fun setInt(key: Pointer, value: Int) {
        val valueMem = Memory(Int.SIZE_BYTES.toLong()).apply { setInt(0L, value) }
        val number = cf.CFNumberCreate(null, CoreFoundation.CF_NUMBER_INT_TYPE, valueMem)
        try {
            val rc = vt.VTSessionSetProperty(session, key, number)
            check(rc == 0) { "VTSessionSetProperty(int) failed: OSStatus=$rc" }
        } finally {
            cf.CFRelease(number)
        }
    }

    private fun setRawProperty(key: Pointer, value: Pointer) {
        val rc = vt.VTSessionSetProperty(session, key, value)
        check(rc == 0) { "VTSessionSetProperty(obj) failed: OSStatus=$rc" }
    }
}

/**
 * Factory for [JnaVideoToolboxH264Encoder]. Used by the Mac App Store
 * [`DependencyGraph`][1] wiring (FP-14d) to inject the H.264 encoder where the
 * GPL desktop build uses the libavcodec path.
 *
 * [1]: desktop/app/src/macAppStore/kotlin/dev/puklic/desktop/appstore/MacAppStoreMainKt.kt
 */
public object JnaVideoToolboxH264EncoderFactory : H264EncoderFactory {
    public override fun create(width: Int, height: Int, bitrateKbps: Int, framerate: Int): H264Encoder =
        JnaVideoToolboxH264Encoder(width, height, bitrateKbps, framerate)
}
