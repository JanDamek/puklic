package dev.puklic.screencast.macos

import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.swscale.SWS_FAST_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BGRA (8-bit per channel, 4 bytes per pixel) → planar YUV420P converter
 * backed by libswscale from the JavaCPP ffmpeg bundle. Pre-allocates a
 * sws context the first time [convert] is invoked, reuses it across calls.
 *
 * Used by [MacScreenCapture] to translate ScreenCaptureKit's BGRA
 * `CVPixelBuffer` payloads into the YUV420P input the libx264 encoder
 * (`LibavPushH264Encoder`) expects.
 *
 * Single-threaded by contract — owned by one encoder coroutine.
 */
internal class BgraToYuv420(
    private val srcWidth: Int,
    private val srcHeight: Int,
    private val dstWidth: Int,
    private val dstHeight: Int,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private var sws: SwsContext? = null

    private val ySize = dstWidth * dstHeight
    private val uSize = ySize / 4
    private val vSize = ySize / 4
    val yuvSize: Int = ySize + uSize + vSize

    /**
     * Convert one BGRA frame ([bgra] of size `srcStride * srcHeight`) into a
     * planar YUV420P frame placed in [dst] (must have capacity [yuvSize]).
     *
     * The source stride may exceed `srcWidth * 4` when the producing
     * `CVPixelBuffer` row-pads to a 16- or 64-byte boundary, which is why
     * [srcStride] is taken explicitly.
     */
    fun convert(bgra: BytePointer, srcStride: Int, dst: ByteArray) {
        check(!closed.get()) { "BgraToYuv420 is closed" }
        require(dst.size >= yuvSize) { "dst too small: ${dst.size} < $yuvSize" }
        val ctx = sws ?: sws_getContext(
            srcWidth, srcHeight, AV_PIX_FMT_BGRA,
            dstWidth, dstHeight, AV_PIX_FMT_YUV420P,
            SWS_FAST_BILINEAR, null, null, null as DoublePointer?,
        )?.also { sws = it }
            ?: error("sws_getContext returned null for ${srcWidth}x$srcHeight BGRA -> ${dstWidth}x$dstHeight YUV420P")

        // Source: 1 plane (BGRA packed).
        val srcSlices = PointerPointer<BytePointer>(1).put(0, bgra)
        val srcStrides = IntPointer(1L).put(0, srcStride)

        // Destination: 3 planes (Y, U, V) inside a single contiguous Kotlin array.
        val yPtr = BytePointer(ySize.toLong())
        val uPtr = BytePointer(uSize.toLong())
        val vPtr = BytePointer(vSize.toLong())
        val dstSlices = PointerPointer<BytePointer>(3)
            .put(0, yPtr)
            .put(1, uPtr)
            .put(2, vPtr)
        val dstStrides = IntPointer(3L)
            .put(0, dstWidth)
            .put(1, dstWidth / 2)
            .put(2, dstWidth / 2)
        try {
            sws_scale(ctx, srcSlices, srcStrides, 0, srcHeight, dstSlices, dstStrides)
            yPtr.get(dst, 0, ySize)
            uPtr.get(dst, ySize, uSize)
            vPtr.get(dst, ySize + uSize, vSize)
        } finally {
            yPtr.deallocate()
            uPtr.deallocate()
            vPtr.deallocate()
            srcSlices.deallocate()
            srcStrides.deallocate()
            dstSlices.deallocate()
            dstStrides.deallocate()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sws?.let { sws_freeContext(it) }
        sws = null
    }
}
