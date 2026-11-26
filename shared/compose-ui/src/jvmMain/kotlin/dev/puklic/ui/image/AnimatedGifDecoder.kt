package dev.puklic.ui.image

import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.ByteString.Companion.encodeUtf8
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data

private const val GIF_SIGNATURE = "GIF8"
private const val MAX_ANIMATED_PIXELS = 2_000_000L
private const val STATIC_FRAME_COUNT = 1

/**
 * Decodes multi-frame GIFs into an animated painter-backed image. Returns `null` for single-frame
 * or oversized GIFs so Coil's static decoder handles them and memory stays bounded.
 */
class AnimatedGifDecoder internal constructor(private val source: ImageSource) : Decoder {
    override suspend fun decode(): DecodeResult? {
        val bytes = source.source().peek().readByteArray()
        val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
        if (!codec.isAnimatedWithinBudget()) return null
        return DecodeResult(image = AnimatedGifImage(codec), isSampled = false)
    }

    private fun Codec.isAnimatedWithinBudget(): Boolean =
        frameCount > STATIC_FRAME_COUNT && width.toLong() * height <= MAX_ANIMATED_PIXELS

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? =
            AnimatedGifDecoder(result.source).takeIf { result.source.isGif() }

        private fun ImageSource.isGif(): Boolean =
            source().peek().rangeEquals(0, GIF_SIGNATURE.encodeUtf8())
    }
}
