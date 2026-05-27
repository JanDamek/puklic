package dev.puklic.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/**
 * Zoom + pan state for [AttachmentViewerDialog] (issue #14).
 *
 * Pure logic — no Compose layout dependencies — so the decision rules
 * (clamping, reset-on-fit, ignore-pan-when-fit, toggle behaviour) can be
 * unit-tested without a UI host. See `ImageZoomStateTest`.
 *
 * Scale bounds match the issue acceptance criteria: 25% min, 400% max.
 */
@Stable
public class ImageZoomState {

    public var scale: Float by mutableFloatStateOf(FIT)
        private set

    public var offset: Offset by mutableStateOf(Offset.Zero)
        private set

    public fun zoomIn() {
        updateScale(scale * ZOOM_STEP)
    }

    public fun zoomOut() {
        updateScale(scale / ZOOM_STEP)
    }

    public fun reset() {
        scale = FIT
        offset = Offset.Zero
    }

    /** Toggle between fit (1.0×) and actual size (typically 1.0× too in this viewer's frame —
     * see issue: maps `1` key to 100%, `0` to fit). Since AsyncImage uses ContentScale.Fit at
     * scale=1, "actual size" here means a noticeable zoom-in step to ACTUAL_SIZE. */
    public fun toggleFitActual() {
        if (scale == FIT) {
            updateScale(ACTUAL_SIZE)
        } else {
            reset()
        }
    }

    /**
     * Wheel scroll → multiplicative zoom. Positive [scrollDelta] = scroll down = zoom out;
     * negative = scroll up = zoom in. Matches mouse-wheel convention on every platform we ship.
     */
    public fun applyWheelZoom(scrollDelta: Float) {
        val factor = if (scrollDelta < 0f) WHEEL_FACTOR else 1f / WHEEL_FACTOR
        updateScale(scale * factor)
    }

    /** Pinch gesture: [zoom] is a multiplicative factor (1.0 = no change). [pan] is delta px. */
    public fun applyTransform(pan: Offset, zoom: Float) {
        updateScale(scale * zoom)
        if (scale > FIT) {
            offset += pan
        }
    }

    /** Drag pan; ignored when image fits viewport (no panning room). */
    public fun applyPan(delta: Offset) {
        if (scale > FIT) {
            offset += delta
        }
    }

    private fun updateScale(newScale: Float) {
        scale = newScale.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (scale == FIT) {
            offset = Offset.Zero
        }
    }

    public companion object {
        public const val MIN_ZOOM: Float = 0.25f
        public const val MAX_ZOOM: Float = 4.0f
        public const val FIT: Float = 1.0f
        public const val ACTUAL_SIZE: Float = 2.0f
        public const val ZOOM_STEP: Float = 1.25f
        public const val WHEEL_FACTOR: Float = 1.1f
    }
}

@Composable
public fun rememberImageZoomState(): ImageZoomState = remember { ImageZoomState() }
