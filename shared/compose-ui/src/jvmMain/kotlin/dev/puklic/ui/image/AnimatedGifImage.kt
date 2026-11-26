package dev.puklic.ui.image

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil3.Canvas
import coil3.Image
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Image as SkiaImage
import java.util.concurrent.atomic.AtomicBoolean

private const val DEFAULT_FRAME_MILLIS = 100L
private const val MIN_FRAME_MILLIS = 20
private const val CODEC_REPEAT_FOREVER = -1
private const val BYTES_PER_PIXEL = 4L
private const val IDLE_AFTER_NANOS = 1_000_000_000L

/**
 * Animated GIF as a Coil [Image]. Memory stays at one decoded frame plus the encoded bytes
 * regardless of the frame count.
 *
 * Playback is demand driven: the decode loop runs only while the image keeps being drawn, so an
 * image scrolled out of view or evicted from the memory cache stops consuming CPU within
 * [IDLE_AFTER_NANOS] without any lifecycle wiring.
 */
internal class AnimatedGifImage(private val codec: Codec) : Image {
    private val frameBitmap = Bitmap().apply { allocPixels(codec.imageInfo) }
    private val playing = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var lastDrawNanos = 0L
    private var frame: SkiaImage by mutableStateOf(frameAt(0))

    override val size: Long = codec.width.toLong() * codec.height * BYTES_PER_PIXEL
    override val width: Int = codec.width
    override val height: Int = codec.height
    override val shareable: Boolean = true

    override fun draw(canvas: Canvas) {
        lastDrawNanos = System.nanoTime()
        canvas.drawImage(frame, 0f, 0f)
        if (playing.compareAndSet(false, true)) scope.launch { play() }
    }

    private suspend fun play() {
        var remainingLoops = codec.repetitionCount
        try {
            while (isBeingDrawn()) {
                for (index in 0 until codec.frameCount) {
                    if (!isBeingDrawn()) return
                    frame = frameAt(index)
                    delay(frameDelayMillis(index))
                }
                if (remainingLoops != CODEC_REPEAT_FOREVER && remainingLoops-- <= 0) return
            }
        } finally {
            playing.set(false)
        }
    }

    private fun isBeingDrawn() = System.nanoTime() - lastDrawNanos < IDLE_AFTER_NANOS

    private fun frameDelayMillis(index: Int): Long =
        codec.getFrameInfo(index).duration
            .takeIf { it > 0 }
            ?.coerceAtLeast(MIN_FRAME_MILLIS)
            ?.toLong()
            ?: DEFAULT_FRAME_MILLIS

    private fun frameAt(index: Int): SkiaImage {
        codec.readPixels(frameBitmap, index)
        return SkiaImage.makeFromBitmap(frameBitmap)
    }
}
