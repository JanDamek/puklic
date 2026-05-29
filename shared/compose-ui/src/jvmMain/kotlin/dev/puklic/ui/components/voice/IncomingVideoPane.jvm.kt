package dev.puklic.ui.components.voice

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import dev.puklic.voice.IncomingVideoFrame
import java.awt.image.BufferedImage

/**
 * JVM `actual` for [IncomingVideoPane] — converts RGBA bytes to a `BufferedImage` and on to
 * a Compose [ImageBitmap]. Phase 4.2 — v1 keeps the UI minimal (stacked Image elements).
 *
 * Performance note: a 1080p frame is ~8 MB and we recompose on every [IncomingVideoFrame]
 * value change. [remember] keyed on width/height/content hash keeps the bitmap stable when
 * the same frame is read twice. Conversion to `BufferedImage.TYPE_INT_ARGB` is O(w*h);
 * for 30 fps screenshare this is the bottleneck and may need a Skia direct path later.
 */
@Composable
internal actual fun IncomingVideoPane(frames: Map<Int, IncomingVideoFrame>) {
    if (frames.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((ssrc, frame) in frames) {
            val bitmap = remember(ssrc, frame.width, frame.height, frame.rgba.contentHashCode()) {
                frame.toImageBitmap()
            }
            Image(
                bitmap = bitmap,
                contentDescription = "Remote screenshare $ssrc",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(frame.width.toFloat() / frame.height.toFloat().coerceAtLeast(1f)),
            )
        }
    }
}

private fun IncomingVideoFrame.toImageBitmap(): ImageBitmap {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val pixels = IntArray(width * height)
    var srcOff = 0
    for (i in pixels.indices) {
        val r = rgba[srcOff].toInt() and 0xFF
        val g = rgba[srcOff + 1].toInt() and 0xFF
        val b = rgba[srcOff + 2].toInt() and 0xFF
        val a = rgba[srcOff + 3].toInt() and 0xFF
        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        srcOff += 4
    }
    img.setRGB(0, 0, width, height, pixels, 0, width)
    return img.toComposeImageBitmap()
}
