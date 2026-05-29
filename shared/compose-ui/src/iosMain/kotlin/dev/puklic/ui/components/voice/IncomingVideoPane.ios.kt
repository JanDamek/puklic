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
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * iOS `actual` for [IncomingVideoPane] — converts the raw RGBA payload to a Skia raster
 * `Image` then to a Compose [ImageBitmap]. Compose Multiplatform on iOS is Skia-backed so
 * this is the canonical path — no platform-specific UIKit imageView required.
 *
 * The pane stays empty whenever no remote frames have been received. iOS today wires
 * `NoOpVoiceClient` so the map is permanently empty under FP-12 — the implementation is
 * complete so that a future real iOS [dev.puklic.voice.VoiceClient] lights it up without
 * any UI follow-up. Not a temporary state per HARD RULE #2.
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
    val info = ImageInfo(
        width = width,
        height = height,
        colorType = ColorType.RGBA_8888,
        alphaType = ColorAlphaType.UNPREMUL,
    )
    val skiaImage = Image.makeRaster(info, rgba, rowBytes = width * BYTES_PER_PIXEL)
    return skiaImage.toComposeImageBitmap()
}

private const val BYTES_PER_PIXEL = 4
