package dev.puklic.voice.screenshare.encoder

import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.av_image_fill_arrays
import org.bytedeco.ffmpeg.global.swscale.SWS_FAST_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer

/**
 * BGRA → YUV420P pixel format converter, libswscale-backed. Used by the Windows DXGI
 * Output Duplication capture (FP-9) to translate the GPU-returned BGRA8 framebuffer
 * bytes into the YUV420P format libx264 expects.
 *
 * The converter is configured once at construction (fixed width × height, fast bilinear
 * scaler kept as a tone-map-free identity-resolution pass when src = dst dims). Each
 * [convert] call reuses the internal `SwsContext` and the native input / output buffers
 * to avoid per-frame allocation churn — ~30 fps × 1080p × 4 bytes/pixel = ~250 MB/s of
 * GC pressure if we re-allocated.
 *
 * Lifecycle: not thread-safe. [close] frees the `SwsContext` and both native buffers in
 * reverse allocation order. Calling [convert] after [close] is undefined.
 */
public class BgraToYuv420Converter(
    private val width: Int,
    private val height: Int,
) : AutoCloseable {

    private val sws: SwsContext
    private val srcBuf: BytePointer
    private val dstBuf: BytePointer
    private val srcPlanes: PointerPointer<BytePointer>
    private val dstPlanes: PointerPointer<BytePointer>
    private val srcStrides: IntPointer
    private val dstStrides: IntPointer
    private val yuvSize: Int
    private var closed = false

    init {
        require(width > 0 && height > 0) { "width/height must be positive, got ${width}x$height" }
        sws = sws_getContext(
            width, height, AV_PIX_FMT_BGRA,
            width, height, AV_PIX_FMT_YUV420P,
            SWS_FAST_BILINEAR, null, null, null as DoublePointer?,
        ) ?: error("sws_getContext returned null for ${width}x$height BGRA→YUV420P")

        // Source side: BGRA = 4 bytes per pixel, tightly packed.
        val srcByteCount = width.toLong() * height * BYTES_PER_BGRA_PIXEL
        srcBuf = BytePointer(srcByteCount)
        srcPlanes = PointerPointer<BytePointer>(SWS_PLANE_COUNT.toLong())
        srcStrides = IntPointer(SWS_PLANE_COUNT.toLong())
        val srcFillRc = av_image_fill_arrays(
            srcPlanes, srcStrides, srcBuf, AV_PIX_FMT_BGRA, width, height, 1,
        )
        if (srcFillRc < 0) {
            srcStrides.deallocate()
            srcPlanes.deallocate()
            srcBuf.deallocate()
            sws_freeContext(sws)
            error("av_image_fill_arrays(BGRA src) failed: $srcFillRc")
        }

        // Destination side: YUV420P = 1.5 bytes per pixel total across three planes.
        yuvSize = width * height * YUV_PIXEL_NUM / YUV_PIXEL_DEN
        dstBuf = BytePointer(yuvSize.toLong())
        dstPlanes = PointerPointer<BytePointer>(SWS_PLANE_COUNT.toLong())
        dstStrides = IntPointer(SWS_PLANE_COUNT.toLong())
        val dstFillRc = av_image_fill_arrays(
            dstPlanes, dstStrides, dstBuf, AV_PIX_FMT_YUV420P, width, height, 1,
        )
        if (dstFillRc < 0) {
            dstStrides.deallocate()
            dstPlanes.deallocate()
            dstBuf.deallocate()
            srcStrides.deallocate()
            srcPlanes.deallocate()
            srcBuf.deallocate()
            sws_freeContext(sws)
            error("av_image_fill_arrays(YUV dst) failed: $dstFillRc")
        }
    }

    /**
     * Convert one BGRA frame to YUV420P.
     *
     * @param bgra tightly packed BGRA8 bytes, size `width * height * 4`.
     * @return YUV420P bytes, size `width * height * 3 / 2`.
     */
    public fun convert(bgra: ByteArray): ByteArray {
        check(!closed) { "BgraToYuv420Converter.convert after close" }
        val expected = width * height * BYTES_PER_BGRA_PIXEL
        require(bgra.size == expected) {
            "bgra size=${bgra.size} doesn't match ${width}x$height BGRA expected=$expected"
        }
        srcBuf.position(0L).put(bgra, 0, bgra.size)
        val converted = sws_scale(sws, srcPlanes, srcStrides, 0, height, dstPlanes, dstStrides)
        if (converted <= 0) error("sws_scale returned $converted")
        val out = ByteArray(yuvSize)
        dstBuf.position(0L).limit(yuvSize.toLong()).get(out, 0, yuvSize)
        return out
    }

    override fun close() {
        if (closed) return
        closed = true
        dstStrides.deallocate()
        dstPlanes.deallocate()
        dstBuf.deallocate()
        srcStrides.deallocate()
        srcPlanes.deallocate()
        srcBuf.deallocate()
        sws_freeContext(sws)
    }

    public companion object {
        public const val BYTES_PER_BGRA_PIXEL: Int = 4
        // YUV 4:2:0 has 1.5 bytes per pixel total → 3 / 2.
        public const val YUV_PIXEL_NUM: Int = 3
        public const val YUV_PIXEL_DEN: Int = 2
        // libswscale plane arrays are conventionally sized 4 (max planes across pixel formats).
        public const val SWS_PLANE_COUNT: Int = 4
    }
}
