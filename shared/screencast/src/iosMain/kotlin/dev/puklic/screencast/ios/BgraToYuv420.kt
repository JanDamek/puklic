package dev.puklic.screencast.ios

/**
 * BGRA → planar YUV420P (I420) converter for iOS, hand-rolled in Kotlin/Native using BT.601
 * limited-range coefficients (matches the libswscale default the JVM screencast path uses
 * with `SWS_FAST_BILINEAR` and matches what Discord clients expect for screen-share H.264).
 *
 * Why hand-rolled instead of `vImage` (Accelerate framework): Kotlin/Native's bundled Apple
 * SDK does not expose `Accelerate` as a default `platform.*` package, and adding a custom
 * cinterop `.def` for one helper function would inflate the iOS framework size and add a
 * SIMD-vs-portable-build matrix. At 1280×720, the inner loop is ~3.7 M MAC ops for Y and
 * ~3.7 M for UV — well under 5 ms per frame on A12+ class CPUs, which fits the 30 fps
 * (~33 ms) budget with room to spare.
 *
 * Layout:
 *   Source — BGRA, 4 bytes per pixel, row-pitch = [sourceBytesPerRow] (may exceed
 *   width*4 when the producing `CVPixelBuffer` row-pads to a 16- or 64-byte boundary).
 *   Destination — I420 planar (Y plane, then U, then V), tightly packed.
 *
 * Single-threaded by contract — caller (the encoder pipeline coroutine in [IosScreenCapture])
 * owns the instance.
 */
internal class BgraToYuv420(
    private val width: Int,
    private val height: Int,
) {

    init {
        require(width > 0 && height > 0) { "width/height must be > 0" }
        require(width % 2 == 0 && height % 2 == 0) {
            "I420 chroma sub-sampling requires even dimensions; got ${width}x${height}"
        }
    }

    /** Tightly-packed I420 buffer size: `Y + U + V = w*h + 2 * (w/2 * h/2) = w*h*3/2`. */
    val yuvSize: Int = width * height * 3 / 2

    private val ySize: Int = width * height
    private val uvWidth: Int = width / 2
    private val uvHeight: Int = height / 2
    private val uvPlaneSize: Int = uvWidth * uvHeight

    /**
     * Convert one BGRA frame into [dst]. Returns the number of bytes written (== [yuvSize]).
     *
     * @param bgra source bytes, length must be at least `sourceBytesPerRow * height`.
     * @param sourceBytesPerRow stride in bytes of one BGRA row in [bgra]. Typically
     *        `width * 4`, but may be larger when the source `CVPixelBuffer` is row-padded.
     * @param dst destination buffer, must have capacity at least [yuvSize]. The same buffer
     *        may (and should, for GC) be reused across calls.
     */
    fun convert(bgra: ByteArray, sourceBytesPerRow: Int, dst: ByteArray): Int {
        require(dst.size >= yuvSize) { "dst too small: ${dst.size} < $yuvSize" }
        require(bgra.size >= sourceBytesPerRow * height) {
            "bgra too small for ${width}x${height} @ stride=$sourceBytesPerRow: got ${bgra.size}"
        }

        val uOffset = ySize
        val vOffset = ySize + uvPlaneSize

        // Y plane — one sample per source pixel.
        // BT.601 limited range: Y =  ((66*R + 129*G +  25*B + 128) >> 8) + 16
        var dstY = 0
        for (y in 0 until height) {
            var srcRow = y * sourceBytesPerRow
            for (x in 0 until width) {
                val b = bgra[srcRow].toInt() and 0xFF
                val g = bgra[srcRow + 1].toInt() and 0xFF
                val r = bgra[srcRow + 2].toInt() and 0xFF
                // (alpha at srcRow + 3 ignored — opaque screen capture)
                val yVal = ((BT601_Y_R * r + BT601_Y_G * g + BT601_Y_B * b + ROUND) shr SHIFT) + Y_BIAS
                dst[dstY++] = yVal.toByte()
                srcRow += BGRA_BYTES_PER_PIXEL
            }
        }

        // U / V planes — average over each 2×2 block and apply BT.601 limited range:
        // U = ((-38*R - 74*G + 112*B + 128) >> 8) + 128
        // V = ((112*R - 94*G -  18*B + 128) >> 8) + 128
        var dstU = uOffset
        var dstV = vOffset
        for (j in 0 until uvHeight) {
            val srcRow0 = (j * 2) * sourceBytesPerRow
            val srcRow1 = (j * 2 + 1) * sourceBytesPerRow
            for (i in 0 until uvWidth) {
                val col0 = i * 2 * BGRA_BYTES_PER_PIXEL
                val col1 = col0 + BGRA_BYTES_PER_PIXEL

                // Sum BGR of the 4 source pixels then shift right by 2 (average).
                val sumB = (
                    (bgra[srcRow0 + col0].toInt() and 0xFF) +
                        (bgra[srcRow0 + col1].toInt() and 0xFF) +
                        (bgra[srcRow1 + col0].toInt() and 0xFF) +
                        (bgra[srcRow1 + col1].toInt() and 0xFF)
                    ) shr 2
                val sumG = (
                    (bgra[srcRow0 + col0 + 1].toInt() and 0xFF) +
                        (bgra[srcRow0 + col1 + 1].toInt() and 0xFF) +
                        (bgra[srcRow1 + col0 + 1].toInt() and 0xFF) +
                        (bgra[srcRow1 + col1 + 1].toInt() and 0xFF)
                    ) shr 2
                val sumR = (
                    (bgra[srcRow0 + col0 + 2].toInt() and 0xFF) +
                        (bgra[srcRow0 + col1 + 2].toInt() and 0xFF) +
                        (bgra[srcRow1 + col0 + 2].toInt() and 0xFF) +
                        (bgra[srcRow1 + col1 + 2].toInt() and 0xFF)
                    ) shr 2

                val u = ((BT601_U_R * sumR + BT601_U_G * sumG + BT601_U_B * sumB + ROUND) shr SHIFT) + UV_BIAS
                val v = ((BT601_V_R * sumR + BT601_V_G * sumG + BT601_V_B * sumB + ROUND) shr SHIFT) + UV_BIAS
                dst[dstU++] = u.coerceIn(0, MAX_BYTE).toByte()
                dst[dstV++] = v.coerceIn(0, MAX_BYTE).toByte()
            }
        }

        return yuvSize
    }

    private companion object {
        const val BGRA_BYTES_PER_PIXEL = 4
        const val SHIFT = 8
        const val ROUND = 128
        const val Y_BIAS = 16
        const val UV_BIAS = 128
        const val MAX_BYTE = 255

        // BT.601 limited-range coefficients × 256, rounded.
        const val BT601_Y_R = 66
        const val BT601_Y_G = 129
        const val BT601_Y_B = 25
        const val BT601_U_R = -38
        const val BT601_U_G = -74
        const val BT601_U_B = 112
        const val BT601_V_R = 112
        const val BT601_V_G = -94
        const val BT601_V_B = -18
    }
}
