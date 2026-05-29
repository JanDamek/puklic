@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.puklic.voice.codec.video

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreMedia.CMBlockBufferCreateWithMemoryBlock
import platform.CoreMedia.CMBlockBufferRef
import platform.CoreMedia.CMBlockBufferRefVar
import platform.CoreMedia.CMFormatDescriptionRef
import platform.CoreMedia.CMSampleBufferCreateReady
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMSampleBufferRefVar
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMVideoFormatDescriptionCreateFromH264ParameterSets
import platform.CoreMedia.CMVideoFormatDescriptionRefVar
import platform.CoreVideo.CVImageBufferRef
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.VideoToolbox.VTDecodeInfoFlags
import platform.VideoToolbox.VTDecompressionOutputCallbackRecord
import platform.VideoToolbox.VTDecompressionSessionCreate
import platform.VideoToolbox.VTDecompressionSessionDecodeFrame
import platform.VideoToolbox.VTDecompressionSessionInvalidate
import platform.VideoToolbox.VTDecompressionSessionRef
import platform.VideoToolbox.VTDecompressionSessionRefVar
import platform.darwin.OSStatus
import platform.darwin.UInt8Var
import kotlinx.cinterop.ULongVar
import platform.posix.calloc
import platform.posix.free
import platform.posix.memcpy

private const val H264_AVCC_LENGTH_PREFIX_SIZE: Int = 4
private const val H264_NAL_HEADER_LENGTH: Int = 4
private const val H264_NALU_TYPE_MASK: Int = 0x1F
private const val H264_NALU_TYPE_SLICE_NON_IDR: Int = 1
private const val H264_NALU_TYPE_SLICE_IDR: Int = 5
private const val H264_NALU_TYPE_SPS: Int = 7
private const val H264_NALU_TYPE_PPS: Int = 8
private const val CV_PIXEL_BUFFER_LOCK_READ_ONLY: ULong = 1u

/**
 * iOS `actual` for [H264DecoderFactory] backed by Apple VideoToolbox `VTDecompressionSession`.
 *
 * - Accepts Annex-B NAL units one at a time (with or without 3-/4-byte start code).
 * - Buffers SPS + PPS until both are available, then creates the `CMVideoFormatDescription`
 *   and the `VTDecompressionSession`. Slices arriving before both parameter sets are
 *   observed return `null`.
 * - Output: 32 BGRA pixel buffer, repacked to ARGB `Int` array of size `width * height`.
 *
 * Threading: VT calls the decompression output callback on its internal serial queue. The
 * decoded array is stashed on the per-instance state via [StableRef]; `decode(...)` returns
 * it on the same call because the session is invoked synchronously without
 * `_EnableAsynchronousDecompression`.
 */
internal class IosH264Decoder(
    private val width: Int,
    private val height: Int,
) : H264Decoder {

    private val state = DecoderState(width, height)
    private val stateRef: StableRef<DecoderState> = StableRef.create(state)
    private var session: VTDecompressionSessionRef? = null
    private var format: CMFormatDescriptionRef? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var closed: Boolean = false

    init {
        require(width > 0 && height > 0) { "width/height must be > 0" }
    }

    override fun decode(annexBNalUnit: ByteArray): IntArray? {
        check(!closed) { "IosH264Decoder is closed" }
        val nalu = stripStartCode(annexBNalUnit) ?: return null
        if (nalu.isEmpty()) return null
        val type = nalu[0].toInt() and H264_NALU_TYPE_MASK
        return when (type) {
            H264_NALU_TYPE_SPS -> {
                sps = nalu; tryInitSession(); null
            }
            H264_NALU_TYPE_PPS -> {
                pps = nalu; tryInitSession(); null
            }
            H264_NALU_TYPE_SLICE_IDR, H264_NALU_TYPE_SLICE_NON_IDR -> {
                val s = session ?: return null
                decodeSlice(s, nalu)
            }
            else -> null
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        session?.let {
            VTDecompressionSessionInvalidate(it)
            CFRelease(it)
        }
        session = null
        format?.let { CFRelease(it) }
        format = null
        stateRef.dispose()
    }

    private fun tryInitSession() {
        if (session != null) return
        val spsBytes = sps ?: return
        val ppsBytes = pps ?: return
        val fmt = createFormatDescription(spsBytes, ppsBytes) ?: return
        format = fmt
        session = createSession(fmt) ?: run {
            CFRelease(fmt); format = null; null
        }
    }

    private fun createFormatDescription(sps: ByteArray, pps: ByteArray): CMFormatDescriptionRef? = memScoped {
        val out = alloc<CMVideoFormatDescriptionRefVar>()
        val paramArr = allocArray<CPointerVar<UInt8Var>>(2)
        val sizeArr = allocArray<ULongVar>(2)
        sps.usePinned { spsPinned ->
            pps.usePinned { ppsPinned ->
                paramArr[0] = spsPinned.addressOf(0).reinterpret()
                paramArr[1] = ppsPinned.addressOf(0).reinterpret()
                sizeArr[0] = sps.size.toULong()
                sizeArr[1] = pps.size.toULong()
                val s = CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator = kCFAllocatorDefault,
                    parameterSetCount = 2u,
                    parameterSetPointers = paramArr.reinterpret(),
                    parameterSetSizes = sizeArr.reinterpret(),
                    NALUnitHeaderLength = H264_NAL_HEADER_LENGTH,
                    formatDescriptionOut = out.ptr,
                )
                if (s != 0) null else out.value
            }
        }
    }

    private fun createSession(format: CMFormatDescriptionRef): VTDecompressionSessionRef? = memScoped {
        val pixelFormatVal = alloc<IntVar>().apply {
            value = kCVPixelFormatType_32BGRA.toInt()
        }
        val pixelFormatNumber = CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, pixelFormatVal.ptr)
            ?: return@memScoped null
        val keys = allocArray<COpaquePointerVar>(1)
        val vals = allocArray<COpaquePointerVar>(1)
        keys[0] = kCVPixelBufferPixelFormatTypeKey
        vals[0] = pixelFormatNumber
        val attrs = CFDictionaryCreate(
            kCFAllocatorDefault,
            keys.reinterpret(),
            vals.reinterpret(),
            1,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFRelease(pixelFormatNumber)

        val callbacks = alloc<VTDecompressionOutputCallbackRecord>().apply {
            decompressionOutputCallback = staticDecompressionCallback
            decompressionOutputRefCon = stateRef.asCPointer()
        }
        val out = alloc<VTDecompressionSessionRefVar>()
        val s = VTDecompressionSessionCreate(
            allocator = kCFAllocatorDefault,
            videoFormatDescription = format,
            videoDecoderSpecification = null,
            destinationImageBufferAttributes = attrs,
            outputCallback = callbacks.ptr,
            decompressionSessionOut = out.ptr,
        )
        attrs?.let { CFRelease(it) }
        if (s != 0) null else out.value
    }

    private fun decodeSlice(sess: VTDecompressionSessionRef, nalu: ByteArray): IntArray? {
        val avccSize = H264_AVCC_LENGTH_PREFIX_SIZE + nalu.size
        val raw = calloc(avccSize.convert(), 1u) ?: return null
        val rawBytes = raw.reinterpret<ByteVar>()
        val len = nalu.size
        rawBytes[0] = ((len ushr 24) and 0xFF).toByte()
        rawBytes[1] = ((len ushr 16) and 0xFF).toByte()
        rawBytes[2] = ((len ushr 8) and 0xFF).toByte()
        rawBytes[3] = (len and 0xFF).toByte()
        nalu.usePinned { p ->
            // Copy NALU body after the 4-byte AVCC length prefix.
            val dst = (rawBytes.toLong() + H264_AVCC_LENGTH_PREFIX_SIZE)
                .toCPointer<ByteVar>()
            memcpy(dst, p.addressOf(0), nalu.size.convert())
        }

        val blockBuffer: CMBlockBufferRef = memScoped {
            val out = alloc<CMBlockBufferRefVar>()
            val s = CMBlockBufferCreateWithMemoryBlock(
                structureAllocator = kCFAllocatorDefault,
                memoryBlock = raw,
                blockLength = avccSize.convert(),
                blockAllocator = kCFAllocatorDefault,
                customBlockSource = null,
                offsetToData = 0u,
                dataLength = avccSize.convert(),
                flags = 0u,
                blockBufferOut = out.ptr,
            )
            if (s != 0) null else out.value
        } ?: run { free(raw); return null }

        try {
            val sampleBuffer: CMSampleBufferRef = memScoped {
                val out = alloc<CMSampleBufferRefVar>()
                val sizes = allocArray<ULongVar>(1)
                sizes[0] = avccSize.toULong()
                val s = CMSampleBufferCreateReady(
                    allocator = kCFAllocatorDefault,
                    dataBuffer = blockBuffer,
                    formatDescription = format,
                    numSamples = 1,
                    numSampleTimingEntries = 0,
                    sampleTimingArray = null,
                    numSampleSizeEntries = 1,
                    sampleSizeArray = sizes.reinterpret(),
                    sampleBufferOut = out.ptr,
                )
                if (s != 0) null else out.value
            } ?: return null

            try {
                state.lastDecoded = null
                val s = VTDecompressionSessionDecodeFrame(
                    session = sess,
                    sampleBuffer = sampleBuffer,
                    decodeFlags = 0u,
                    sourceFrameRefCon = null,
                    infoFlagsOut = null,
                )
                if (s != 0) return null
                return state.lastDecoded.also { state.lastDecoded = null }
            } finally {
                CFRelease(sampleBuffer)
            }
        } finally {
            CFRelease(blockBuffer)
        }
    }

    private fun stripStartCode(input: ByteArray): ByteArray? {
        if (input.isEmpty()) return null
        if (input.size >= 4 &&
            input[0].toInt() == 0 && input[1].toInt() == 0 &&
            input[2].toInt() == 0 && input[3].toInt() == 1
        ) return input.copyOfRange(4, input.size)
        if (input.size >= 3 &&
            input[0].toInt() == 0 && input[1].toInt() == 0 && input[2].toInt() == 1
        ) return input.copyOfRange(3, input.size)
        return input
    }

    internal class DecoderState(val width: Int, val height: Int) {
        var lastDecoded: IntArray? = null
    }
}

@OptIn(ExperimentalForeignApi::class)
private val staticDecompressionCallback: CPointer<CFunction<
    (COpaquePointer?, COpaquePointer?, OSStatus, VTDecodeInfoFlags, CVImageBufferRef?, CValue<CMTime>, CValue<CMTime>) -> Unit>> = staticCFunction {
        decompressionOutputRefCon: COpaquePointer?,
        _: COpaquePointer?,
        status: OSStatus,
        _: VTDecodeInfoFlags,
        imageBuffer: CVImageBufferRef?,
        _: CValue<CMTime>,
        _: CValue<CMTime>,
    ->
    if (status != 0 || imageBuffer == null || decompressionOutputRefCon == null) return@staticCFunction
    val state = decompressionOutputRefCon.asStableRef<IosH264Decoder.DecoderState>().get()
    state.lastDecoded = bgraPixelBufferToArgb(imageBuffer, state.width, state.height)
}

@OptIn(ExperimentalForeignApi::class)
private fun bgraPixelBufferToArgb(buffer: CVPixelBufferRef, expectedWidth: Int, expectedHeight: Int): IntArray? {
    CVPixelBufferLockBaseAddress(buffer, CV_PIXEL_BUFFER_LOCK_READ_ONLY)
    try {
        val w = CVPixelBufferGetWidth(buffer).toInt()
        val h = CVPixelBufferGetHeight(buffer).toInt()
        val stride = CVPixelBufferGetBytesPerRow(buffer).toInt()
        val base = CVPixelBufferGetBaseAddress(buffer)?.reinterpret<UInt8Var>() ?: return null

        val outW = if (expectedWidth in 1..w) expectedWidth else w
        val outH = if (expectedHeight in 1..h) expectedHeight else h
        val out = IntArray(outW * outH)
        var dstIdx = 0
        for (y in 0 until outH) {
            val rowStart = y * stride
            for (x in 0 until outW) {
                val px = rowStart + x * 4
                val b = base[px].toInt() and 0xFF
                val g = base[px + 1].toInt() and 0xFF
                val r = base[px + 2].toInt() and 0xFF
                val a = base[px + 3].toInt() and 0xFF
                out[dstIdx++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    } finally {
        CVPixelBufferUnlockBaseAddress(buffer, CV_PIXEL_BUFFER_LOCK_READ_ONLY)
    }
}
