package dev.puklic.ui.components

import androidx.compose.ui.geometry.Offset
import io.kotest.matchers.floats.shouldBeWithinPercentageOf
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Unit tests for [ImageZoomState] pure logic.
 *
 * Covers wheel/keyboard/pinch zoom inputs, pan-when-zoomed, bounded scale,
 * and reset behaviour for the image attachment viewer (issue #14).
 */
class ImageZoomStateTest {

    @Test
    fun initialIsFit() {
        val s = ImageZoomState()
        s.scale shouldBe 1f
        s.offset shouldBe Offset.Zero
    }

    @Test
    fun zoomInClampsToMax() {
        val s = ImageZoomState()
        repeat(20) { s.zoomIn() }
        s.scale shouldBe ImageZoomState.MAX_ZOOM
    }

    @Test
    fun zoomOutClampsToMin() {
        val s = ImageZoomState()
        repeat(20) { s.zoomOut() }
        s.scale shouldBe ImageZoomState.MIN_ZOOM
    }

    @Test
    fun zoomInThenOutReturnsToOne() {
        val s = ImageZoomState()
        s.zoomIn()
        s.zoomOut()
        s.scale.shouldBeWithinPercentageOf(1f, 0.01)
    }

    @Test
    fun resetRestoresFit() {
        val s = ImageZoomState()
        s.zoomIn()
        s.zoomIn()
        s.applyPan(Offset(50f, 50f))
        s.reset()
        s.scale shouldBe 1f
        s.offset shouldBe Offset.Zero
    }

    @Test
    fun toggleFitActualFromFitGoesToActual() {
        val s = ImageZoomState()
        s.toggleFitActual()
        s.scale shouldBe ImageZoomState.ACTUAL_SIZE
    }

    @Test
    fun toggleFitActualFromActualGoesToFit() {
        val s = ImageZoomState()
        s.toggleFitActual()
        s.toggleFitActual()
        s.scale shouldBe 1f
        s.offset shouldBe Offset.Zero
    }

    @Test
    fun toggleFitActualFromCustomZoomGoesToFit() {
        val s = ImageZoomState()
        s.zoomIn()
        s.toggleFitActual()
        s.scale shouldBe 1f
        s.offset shouldBe Offset.Zero
    }

    @Test
    fun wheelZoomInIncreasesScale() {
        val s = ImageZoomState()
        s.applyWheelZoom(scrollDelta = -1f)
        (s.scale > 1f) shouldBe true
    }

    @Test
    fun wheelZoomOutDecreasesScale() {
        val s = ImageZoomState()
        s.applyWheelZoom(scrollDelta = 1f)
        (s.scale < 1f) shouldBe true
    }

    @Test
    fun wheelZoomRespectsBounds() {
        val s = ImageZoomState()
        repeat(100) { s.applyWheelZoom(scrollDelta = -1f) }
        s.scale shouldBe ImageZoomState.MAX_ZOOM
        repeat(200) { s.applyWheelZoom(scrollDelta = 1f) }
        s.scale shouldBe ImageZoomState.MIN_ZOOM
    }

    @Test
    fun applyTransformZoomMultiplies() {
        val s = ImageZoomState()
        s.applyTransform(pan = Offset.Zero, zoom = 2f)
        s.scale shouldBe 2f
    }

    @Test
    fun applyTransformZoomClamps() {
        val s = ImageZoomState()
        s.applyTransform(pan = Offset.Zero, zoom = 100f)
        s.scale shouldBe ImageZoomState.MAX_ZOOM
        s.applyTransform(pan = Offset.Zero, zoom = 0.0001f)
        s.scale shouldBe ImageZoomState.MIN_ZOOM
    }

    @Test
    fun applyPanWhenZoomedMovesOffset() {
        val s = ImageZoomState()
        s.zoomIn()
        s.applyPan(Offset(10f, 20f))
        s.offset shouldBe Offset(10f, 20f)
    }

    @Test
    fun applyPanWhenNotZoomedIgnored() {
        val s = ImageZoomState()
        s.applyPan(Offset(10f, 20f))
        s.offset shouldBe Offset.Zero
    }

    @Test
    fun scaleReturningToOneResetsOffset() {
        val s = ImageZoomState()
        s.zoomIn()
        s.applyPan(Offset(10f, 20f))
        s.zoomOut()
        s.offset shouldBe Offset.Zero
    }
}
