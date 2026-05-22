package dev.puklic.platform

/**
 * Phase 4 screenshare / window capture types. Defined here so service signatures compile early.
 */

enum class MediaSourceKind { Monitor, Window, Region }

data class MediaSource(
    val id: String,
    val kind: MediaSourceKind,
    val title: String,
    val widthPx: Int,
    val heightPx: Int,
)

data class VideoFrame(
    val pixels: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    val stridePx: Int,
    val timestampUs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VideoFrame) return false
        return widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            stridePx == other.stridePx &&
            timestampUs == other.timestampUs &&
            pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = pixels.contentHashCode()
        result = 31 * result + widthPx
        result = 31 * result + heightPx
        result = 31 * result + stridePx
        result = 31 * result + timestampUs.hashCode()
        return result
    }
}
