@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.puklic.voice.codec.video

import dev.puklic.voice.transport.EncodedFrame
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFBooleanGetValue
import platform.CoreFoundation.CFBooleanRef
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreMedia.CMBlockBufferGetDataLength
import platform.CoreMedia.CMBlockBufferGetDataPointer
import platform.CoreMedia.CMBlockBufferRef
import platform.CoreMedia.CMFormatDescriptionRef
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.CoreMedia.CMSampleBufferGetFormatDescription
import platform.CoreMedia.CMSampleBufferGetSampleAttachmentsArray
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMVideoFormatDescriptionGetH264ParameterSetAtIndex
import platform.CoreMedia.kCMSampleAttachmentKey_NotSync
import platform.CoreMedia.kCMTimeInvalid
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.VideoToolbox.VTCompressionSessionCompleteFrames
import platform.VideoToolbox.VTCompressionSessionCreate
import platform.VideoToolbox.VTCompressionSessionEncodeFrame
import platform.VideoToolbox.VTCompressionSessionInvalidate
import platform.VideoToolbox.VTCompressionSessionRef
import platform.VideoToolbox.VTCompressionSessionRefVar
import platform.VideoToolbox.VTSessionSetProperty
import platform.VideoToolbox.kVTCompressionPropertyKey_AllowFrameReordering
import platform.VideoToolbox.kVTCompressionPropertyKey_AverageBitRate
import platform.VideoToolbox.kVTCompressionPropertyKey_ExpectedFrameRate
import platform.VideoToolbox.kVTCompressionPropertyKey_MaxKeyFrameInterval
import platform.VideoToolbox.kVTCompressionPropertyKey_ProfileLevel
import platform.VideoToolbox.kVTCompressionPropertyKey_RealTime
import platform.VideoToolbox.kVTProfileLevel_H264_Baseline_AutoLevel
import platform.darwin.OSStatus
import platform.darwin.UInt8Var
import platform.posix.memcpy
import platform.posix.size_tVar

private const val H264_AVCC_LENGTH_PREFIX_SIZE: Int = 4
private const val H264_ANNEXB_START_CODE_SIZE: Int = 4
private const val RTP_VIDEO_CLOCK_HZ: Int = 90_000
private const val FOURCC_AVC1: UInt = 0x61_76_63_31u  // 'avc1' == kCMVideoCodecType_H264

/**
 * iOS `actual` for [H264EncoderFactory] backed by Apple VideoToolbox `VTCompressionSession`.
 *
 * - Hardware H.264 Baseline profile, real-time, no frame reordering (zero-latency push-frame).
 * - Input: I420 (planar YUV 4:2:0). Converted to NV12 (semi-planar) for VT consumption.
 * - Output: Annex-B NAL units with 4-byte 0x00000001 start codes. On the first keyframe
 *   the SPS and PPS are extracted from the `CMVideoFormatDescription` and prepended in-band.
 *
 * Threading: VT invokes the output callback on its internal serial queue. We collect the
 * frame into a per-instance [EncoderState] queue. With `RealTime = true` and
 * `AllowFrameReordering = false`, VT in practice fires the callback before
 * `VTCompressionSessionEncodeFrame` returns, so [encode] returns the matching frame.
 * During the brief pipeline warm-up VT may swallow an input; [encode] then returns `null`
 * per the [H264Encoder.encode] contract.
 */
internal class IosH264Encoder(
    private val width: Int,
    private val height: Int,
    bitrateKbps: Int,
    framerate: Int,
) : H264Encoder {

    private val state = EncoderState(framerate)
    private val stateRef: StableRef<EncoderState> = StableRef.create(state)
    private val session: VTCompressionSessionRef
    private var frameIndex: Long = 0
    private var closed: Boolean = false

    init {
        require(width > 0 && height > 0) { "width/height must be > 0" }
        require(bitrateKbps > 0) { "bitrateKbps must be > 0" }
        require(framerate > 0) { "framerate must be > 0" }
        // VT requires even dimensions for YUV420 chroma sub-sampling.
        require(width % 2 == 0 && height % 2 == 0) { "width/height must be even for I420" }

        session = createSession() ?: run {
            stateRef.dispose()
            error("VTCompressionSessionCreate failed for ${width}x${height}")
        }

        setBool(kVTCompressionPropertyKey_RealTime, true)
        setBool(kVTCompressionPropertyKey_AllowFrameReordering, false)
        setProperty(kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_H264_Baseline_AutoLevel)
        setInt(kVTCompressionPropertyKey_AverageBitRate, bitrateKbps * 1000)
        setInt(kVTCompressionPropertyKey_MaxKeyFrameInterval, framerate * 2)
        setInt(kVTCompressionPropertyKey_ExpectedFrameRate, framerate)
    }

    private fun createSession(): VTCompressionSessionRef? = memScoped {
        val out = alloc<VTCompressionSessionRefVar>()
        val status = VTCompressionSessionCreate(
            allocator = kCFAllocatorDefault,
            width = width,
            height = height,
            codecType = FOURCC_AVC1.toUInt(),
            encoderSpecification = null,
            sourceImageBufferAttributes = null,
            compressedDataAllocator = null,
            outputCallback = staticCompressionCallback,
            outputCallbackRefCon = stateRef.asCPointer(),
            compressionSessionOut = out.ptr,
        )
        if (status != 0) null else out.value
    }

    override fun encode(yuv420p: ByteArray): EncodedFrame? {
        check(!closed) { "IosH264Encoder is closed" }
        val expected = width * height * 3 / 2
        require(yuv420p.size == expected) {
            "Expected $expected I420 bytes for ${width}x${height}, got ${yuv420p.size}"
        }

        val pixelBuffer = createNv12PixelBuffer(yuv420p)
            ?: error("CVPixelBufferCreate(NV12) returned null")
        try {
            val pts = CMTimeMake(value = frameIndex, timescale = state.framerate)
            frameIndex += 1
            val status = VTCompressionSessionEncodeFrame(
                session = session,
                imageBuffer = pixelBuffer,
                presentationTimeStamp = pts,
                duration = kCMTimeInvalid.readValue(),
                frameProperties = null,
                sourceFrameRefcon = null,
                infoFlagsOut = null,
            )
            if (status != 0) error("VTCompressionSessionEncodeFrame failed: OSStatus=$status")
        } finally {
            CFRelease(pixelBuffer)
        }

        return if (state.outputs.isNotEmpty()) state.outputs.removeFirst() else null
    }

    override fun close() {
        if (closed) return
        closed = true
        VTCompressionSessionCompleteFrames(session, kCMTimeInvalid.readValue())
        VTCompressionSessionInvalidate(session)
        CFRelease(session)
        stateRef.dispose()
    }

    private fun createNv12PixelBuffer(i420: ByteArray): CVPixelBufferRef? = memScoped {
        val out = alloc<CVPixelBufferRefVar>()
        val status = CVPixelBufferCreate(
            allocator = kCFAllocatorDefault,
            width = width.convert(),
            height = height.convert(),
            pixelFormatType = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            pixelBufferAttributes = null,
            pixelBufferOut = out.ptr,
        )
        if (status != 0) return@memScoped null
        val buf = out.value ?: return@memScoped null

        CVPixelBufferLockBaseAddress(buf, 0u)
        try {
            val yPlane = CVPixelBufferGetBaseAddressOfPlane(buf, 0u)
                ?: error("CVPixelBufferGetBaseAddressOfPlane(Y) returned null")
            val uvPlane = CVPixelBufferGetBaseAddressOfPlane(buf, 1u)
                ?: error("CVPixelBufferGetBaseAddressOfPlane(UV) returned null")

            val ySize = width * height
            val chromaWidth = width / 2
            val chromaHeight = height / 2
            val chromaSize = chromaWidth * chromaHeight

            i420.usePinned { pinned ->
                memcpy(yPlane, pinned.addressOf(0), ySize.convert())
            }
            val uvDst = uvPlane.reinterpret<UInt8Var>()
            for (i in 0 until chromaSize) {
                uvDst[2 * i] = i420[ySize + i].toUByte()
                uvDst[2 * i + 1] = i420[ySize + chromaSize + i].toUByte()
            }
        } finally {
            CVPixelBufferUnlockBaseAddress(buf, 0u)
        }
        buf
    }

    private fun setBool(key: platform.CoreFoundation.CFStringRef?, value: Boolean) {
        val ref: CFBooleanRef? = if (value) kCFBooleanTrue else kCFBooleanFalse
        val s = VTSessionSetProperty(session.reinterpret(), key, ref)
        if (s != 0) error("VTSessionSetProperty(bool) failed: OSStatus=$s")
    }

    private fun setInt(key: platform.CoreFoundation.CFStringRef?, value: Int) = memScoped {
        val v = alloc<IntVar>().apply { this.value = value }
        val number = CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, v.ptr)
            ?: error("CFNumberCreate failed")
        try {
            val s = VTSessionSetProperty(session.reinterpret(), key, number)
            if (s != 0) error("VTSessionSetProperty(int) failed: OSStatus=$s")
        } finally {
            CFRelease(number)
        }
    }

    private fun setProperty(key: platform.CoreFoundation.CFStringRef?, value: CPointer<*>?) {
        val s = VTSessionSetProperty(session.reinterpret(), key, value)
        if (s != 0) error("VTSessionSetProperty(obj) failed: OSStatus=$s")
    }

    /**
     * Mutable state shared with the VT C callback via [StableRef].
     */
    internal class EncoderState(val framerate: Int) {
        val outputs: ArrayDeque<EncodedFrame> = ArrayDeque(4)
        var sentParameterSets: Boolean = false
        var lastTs90k: Int = 0
    }
}

/**
 * VT compression output callback. Static C function — receives a [StableRef] to the
 * owning encoder's state via the refCon pointer.
 */
@OptIn(ExperimentalForeignApi::class)
private val staticCompressionCallback: platform.VideoToolbox.VTCompressionOutputCallback = staticCFunction {
        outputCallbackRefCon: COpaquePointer?,
        _: COpaquePointer?,
        status: OSStatus,
        _: platform.VideoToolbox.VTEncodeInfoFlags,
        sampleBuffer: CMSampleBufferRef?,
    ->
    if (status != 0 || sampleBuffer == null || outputCallbackRefCon == null) return@staticCFunction
    val state = outputCallbackRefCon.asStableRef<IosH264Encoder.EncoderState>().get()
    handleEncoderOutput(state, sampleBuffer)
}

@OptIn(ExperimentalForeignApi::class)
private fun handleEncoderOutput(state: IosH264Encoder.EncoderState, sampleBuffer: CMSampleBufferRef) {
    val isKeyframe = sampleIsKeyframe(sampleBuffer)
    val annexB = ArrayList<ByteArray>(4)
    if (isKeyframe && !state.sentParameterSets) {
        val fmt = CMSampleBufferGetFormatDescription(sampleBuffer) ?: return
        extractParameterSets(fmt).forEach { annexB += it }
        state.sentParameterSets = true
    }
    val avcc = copyDataFromSampleBuffer(sampleBuffer) ?: return
    convertAvccToAnnexB(avcc).forEach { annexB += it }
    if (annexB.isEmpty()) return

    val totalSize = annexB.sumOf { H264_ANNEXB_START_CODE_SIZE + it.size }
    val payload = ByteArray(totalSize)
    var off = 0
    for (nalu in annexB) {
        payload[off] = 0
        payload[off + 1] = 0
        payload[off + 2] = 0
        payload[off + 3] = 1
        nalu.copyInto(payload, off + H264_ANNEXB_START_CODE_SIZE)
        off += H264_ANNEXB_START_CODE_SIZE + nalu.size
    }

    val ts = state.lastTs90k + (RTP_VIDEO_CLOCK_HZ / state.framerate)
    state.lastTs90k = ts
    state.outputs.addLast(EncodedFrame(bytes = payload, ts90k = ts, keyframe = isKeyframe))
}

@OptIn(ExperimentalForeignApi::class)
private fun sampleIsKeyframe(buffer: CMSampleBufferRef): Boolean {
    val attachments = CMSampleBufferGetSampleAttachmentsArray(buffer, createIfNecessary = false)
        ?: return true
    if (CFArrayGetCount(attachments) == 0L) return true
    @Suppress("UNCHECKED_CAST")
    val dict = CFArrayGetValueAtIndex(attachments, 0) as? CFDictionaryRef ?: return true
    val notSync = CFDictionaryGetValue(dict, kCMSampleAttachmentKey_NotSync) ?: return true
    @Suppress("UNCHECKED_CAST")
    return !CFBooleanGetValue(notSync as CFBooleanRef)
}

@OptIn(ExperimentalForeignApi::class)
private fun extractParameterSets(format: CMFormatDescriptionRef): List<ByteArray> = memScoped {
    val countVar = alloc<size_tVar>()
    val result = ArrayList<ByteArray>(2)
    // First call to read count.
    val firstPtr = alloc<CPointerVar<UInt8Var>>()
    val firstSize = alloc<size_tVar>()
    val s0 = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
        videoDesc = format,
        parameterSetIndex = 0u,
        parameterSetPointerOut = firstPtr.ptr.reinterpret(),
        parameterSetSizeOut = firstSize.ptr,
        parameterSetCountOut = countVar.ptr,
        NALUnitHeaderLengthOut = null,
    )
    if (s0 != 0) return@memScoped result
    val count = countVar.value.toInt()
    if (count <= 0) return@memScoped result
    firstPtr.value?.let { ptr ->
        val size = firstSize.value.toInt()
        val bytes = ByteArray(size)
        bytes.usePinned { p -> memcpy(p.addressOf(0), ptr, size.convert()) }
        result += bytes
    }
    for (i in 1 until count) {
        val ptrVar = alloc<CPointerVar<UInt8Var>>()
        val sizeVar = alloc<size_tVar>()
        val s = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            videoDesc = format,
            parameterSetIndex = i.convert(),
            parameterSetPointerOut = ptrVar.ptr.reinterpret(),
            parameterSetSizeOut = sizeVar.ptr,
            parameterSetCountOut = null,
            NALUnitHeaderLengthOut = null,
        )
        if (s != 0) break
        val ptr = ptrVar.value ?: break
        val size = sizeVar.value.toInt()
        val bytes = ByteArray(size)
        bytes.usePinned { p -> memcpy(p.addressOf(0), ptr, size.convert()) }
        result += bytes
    }
    result
}

@OptIn(ExperimentalForeignApi::class)
private fun copyDataFromSampleBuffer(buffer: CMSampleBufferRef): ByteArray? = memScoped {
    val block: CMBlockBufferRef = CMSampleBufferGetDataBuffer(buffer) ?: return@memScoped null
    val len = CMBlockBufferGetDataLength(block).toInt()
    if (len <= 0) return@memScoped null
    val ptrVar = alloc<CPointerVar<ByteVar>>()
    val totalLenVar = alloc<size_tVar>()
    val lengthAtOffsetVar = alloc<size_tVar>()
    val s = CMBlockBufferGetDataPointer(
        theBuffer = block,
        offset = 0u,
        lengthAtOffsetOut = lengthAtOffsetVar.ptr,
        totalLengthOut = totalLenVar.ptr,
        dataPointerOut = ptrVar.ptr,
    )
    if (s != 0 || ptrVar.value == null) return@memScoped null
    val out = ByteArray(len)
    out.usePinned { dst -> memcpy(dst.addressOf(0), ptrVar.value, len.convert()) }
    out
}

private fun convertAvccToAnnexB(avcc: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>(2)
    var off = 0
    while (off + H264_AVCC_LENGTH_PREFIX_SIZE <= avcc.size) {
        val len = ((avcc[off].toInt() and 0xFF) shl 24) or
            ((avcc[off + 1].toInt() and 0xFF) shl 16) or
            ((avcc[off + 2].toInt() and 0xFF) shl 8) or
            (avcc[off + 3].toInt() and 0xFF)
        off += H264_AVCC_LENGTH_PREFIX_SIZE
        if (len <= 0 || off + len > avcc.size) break
        out += avcc.copyOfRange(off, off + len)
        off += len
    }
    return out
}
