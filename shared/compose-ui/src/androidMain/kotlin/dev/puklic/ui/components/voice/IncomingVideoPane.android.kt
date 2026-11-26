package dev.puklic.ui.components.voice

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import dev.puklic.voice.IncomingVideoFrame
import dev.puklic.ui.resources.Res
import dev.puklic.ui.resources.voice_remote_screenshare
import org.jetbrains.compose.resources.stringResource

private const val BYTES_PER_PIXEL = 4
private const val PANE_PADDING_DP = 8
private const val MIN_FRAME_HEIGHT = 1f

/**
 * Android `actual` for [IncomingVideoPane] — converts RGBA bytes to an `android.graphics.Bitmap`
 * and on to a Compose [ImageBitmap].
 */
@Composable
internal actual fun IncomingVideoPane(frames: Map<Int, IncomingVideoFrame>) {
    if (frames.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(PANE_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(PANE_PADDING_DP.dp),
    ) {
        frames.forEach { (ssrc, frame) ->
            val bitmap = remember(ssrc, frame.width, frame.height, frame.rgba.contentHashCode()) {
                frame.toImageBitmap()
            }
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(Res.string.voice_remote_screenshare, ssrc.toString()),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(frame.width.toFloat() / frame.height.toFloat().coerceAtLeast(MIN_FRAME_HEIGHT)),
            )
        }
    }
}

private fun IncomingVideoFrame.toImageBitmap(): ImageBitmap {
    val pixels = IntArray(width * height) { index ->
        val offset = index * BYTES_PER_PIXEL
        val r = rgba[offset].toInt() and 0xFF
        val g = rgba[offset + 1].toInt() and 0xFF
        val b = rgba[offset + 2].toInt() and 0xFF
        val a = rgba[offset + 3].toInt() and 0xFF
        (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
