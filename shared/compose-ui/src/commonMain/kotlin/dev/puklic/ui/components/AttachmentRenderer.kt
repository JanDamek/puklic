package dev.puklic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.puklic.domain.Attachment
import dev.puklic.ui.theme.LocalPuklicSpacing
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class AttachmentKind { Image, Video, File }

private val ImageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp")
private val VideoExtensions = setOf("mp4", "webm", "mov", "mkv")

internal fun Attachment.kind(): AttachmentKind {
    val ct = contentType
    return when {
        ct != null && ct.startsWith("image/") -> AttachmentKind.Image
        ct != null && ct.startsWith("video/") -> AttachmentKind.Video
        ct != null -> AttachmentKind.File
        else -> when (filename.substringAfterLast('.', "").lowercase()) {
            in ImageExtensions -> AttachmentKind.Image
            in VideoExtensions -> AttachmentKind.Video
            else -> AttachmentKind.File
        }
    }
}

private val MaxImageWidth = 400.dp
private val MaxImageHeight = 300.dp
private const val BYTES_KB = 1024L
private const val BYTES_MB = BYTES_KB * 1024L
private const val BYTES_GB = BYTES_MB * 1024L

internal fun formatFileSize(bytes: Long): String = when {
    bytes >= BYTES_GB -> "${oneDecimal(bytes.toDouble() / BYTES_GB)} GB"
    bytes >= BYTES_MB -> "${oneDecimal(bytes.toDouble() / BYTES_MB)} MB"
    bytes >= BYTES_KB -> "${oneDecimal(bytes.toDouble() / BYTES_KB)} KB"
    else -> "$bytes B"
}

private fun oneDecimal(value: Double): String {
    val scaled = (value * 10.0).toLong()
    val whole = scaled / 10
    val frac = scaled % 10
    return "$whole.$frac"
}

/**
 * Bounded image dimensions preserving aspect ratio. Never upscales: if intrinsic dims are smaller
 * than the caps, the image is rendered at intrinsic size. If dims are unknown, caps via widthIn/
 * heightIn so the layout cannot blow up.
 */
private fun imageSizeModifier(width: Int?, height: Int?): Modifier {
    if (width == null || height == null || width <= 0 || height <= 0) {
        return Modifier.widthIn(max = MaxImageWidth).heightIn(max = MaxImageHeight)
    }
    val w0 = width.toFloat()
    val h0 = height.toFloat()
    val maxW = MaxImageWidth.value
    val maxH = MaxImageHeight.value
    val scale = min(min(maxW / w0, maxH / h0), 1f)
    val w: Dp = (w0 * scale).roundToInt().dp
    val h: Dp = (h0 * scale).roundToInt().dp
    return Modifier.size(w, h)
}

@Composable
internal fun ImageAttachment(attachment: Attachment, modifier: Modifier = Modifier) {
    var viewing by remember { mutableStateOf<Attachment?>(null) }
    AsyncImage(
        model = attachment.proxyUrl,
        contentDescription = attachment.description ?: attachment.filename,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .then(imageSizeModifier(attachment.width, attachment.height))
            .clip(RoundedCornerShape(8.dp))
            .clickable { viewing = attachment },
    )
    val current = viewing
    if (current != null) {
        AttachmentViewerDialog(attachment = current, onDismiss = { viewing = null })
    }
}

@Composable
internal fun VideoAttachment(attachment: Attachment, onOpen: () -> Unit) {
    val spacing = LocalPuklicSpacing.current
    Column(
        modifier = Modifier
            .then(imageSizeModifier(attachment.width, attachment.height))
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = MaxImageHeight),
            contentAlignment = Alignment.Center,
        ) {
            // Discord's proxyUrl for video is the video itself — Coil cannot decode it as an image.
            // v1 fallback: solid surface; the play icon and metadata communicate that it is a video.
            // Real frame posters require server-side ?format=jpeg which is not guaranteed (see §8).
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                )
            }
            val duration = attachment.durationSecs
            if (duration != null && duration > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(spacing.space2)
                        .background(
                            Color.Black.copy(alpha = 0.65f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = spacing.space2, vertical = 2.dp),
                ) {
                    Text(
                        text = formatDuration(duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }
        }
        Text(
            text = attachment.filename,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = spacing.space2, vertical = spacing.space1),
        )
    }
}

private fun formatDuration(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return if (s < 10) "$m:0$s" else "$m:$s"
}

@Composable
internal fun FileAttachment(attachment: Attachment, onOpen: () -> Unit) {
    val spacing = LocalPuklicSpacing.current
    val extension = attachment.filename.substringAfterLast('.', "").uppercase().take(4)
    Card(
        modifier = Modifier
            .widthIn(max = MaxImageWidth)
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(spacing.space3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space3),
        ) {
            // Material icon substitute: a tinted rounded card showing the file extension. Core
            // Compose Material icon set doesn't ship a file-document glyph, and pulling in
            // `material-icons-extended` (~3 MB) only for this is wasteful per minimum-complexity.
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = extension.ifEmpty { "FILE" },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = attachment.filename,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatFileSize(attachment.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Dispatcher composable: routes by [Attachment.kind] to the kind-specific renderer. */
@Composable
public fun AttachmentRenderer(attachment: Attachment, onOpen: (Attachment) -> Unit) {
    when (attachment.kind()) {
        AttachmentKind.Image -> ImageAttachment(attachment)
        AttachmentKind.Video -> VideoAttachment(attachment, onOpen = { onOpen(attachment) })
        AttachmentKind.File -> FileAttachment(attachment, onOpen = { onOpen(attachment) })
    }
}

