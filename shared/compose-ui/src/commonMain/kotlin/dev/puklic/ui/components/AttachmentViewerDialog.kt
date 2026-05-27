package dev.puklic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.LaunchedEffect
import coil3.compose.AsyncImage
import dev.puklic.domain.Attachment

/**
 * Full-size modal viewer for an image attachment.
 *
 * Dismiss: close icon, outside click (when not zoomed), ESC, platform back gesture.
 * Zoom (issue #14):
 *   - Mouse wheel → zoom in/out (no modifier needed; the viewer captures all scroll)
 *   - Pinch gesture (trackpad/touch) → zoom + pan
 *   - Keyboard `+` / `=` zoom in, `-` zoom out, `0` reset to fit, `1` toggles 100%/fit
 *   - Drag when zoomed → pan
 *   - Double-tap → toggle fit ↔ actual
 *   - Bounded 25%–400%
 */
@Composable
public fun AttachmentViewerDialog(attachment: Attachment, onDismiss: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val zoom = rememberImageZoomState()
    val focusRequester = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
        ),
    ) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> { onDismiss(); true }
                        Key.Plus, Key.Equals, Key.NumPadAdd -> { zoom.zoomIn(); true }
                        Key.Minus, Key.NumPadSubtract -> { zoom.zoomOut(); true }
                        Key.Zero, Key.NumPad0 -> { zoom.reset(); true }
                        Key.One, Key.NumPad1 -> { zoom.toggleFitActual(); true }
                        else -> false
                    }
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { if (zoom.scale == ImageZoomState.FIT) onDismiss() },
                ),
        ) {
            AsyncImage(
                model = attachment.url,
                contentDescription = attachment.description ?: attachment.filename,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(IMAGE_PADDING_DP.dp)
                    .graphicsLayer(
                        scaleX = zoom.scale,
                        scaleY = zoom.scale,
                        translationX = zoom.offset.x,
                        translationY = zoom.offset.y,
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { zoom.toggleFitActual() })
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoomDelta, _ ->
                            zoom.applyTransform(pan, zoomDelta)
                        }
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Scroll) {
                                    val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                    if (dy != 0f) {
                                        zoom.applyWheelZoom(dy)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        }
                    },
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(CLOSE_BUTTON_PADDING_DP.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                )
            }
        }
    }
}

private const val IMAGE_PADDING_DP = 32
private const val CLOSE_BUTTON_PADDING_DP = 16
