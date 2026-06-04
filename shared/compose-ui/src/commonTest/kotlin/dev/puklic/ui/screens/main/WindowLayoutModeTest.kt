package dev.puklic.ui.screens.main

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowLayoutModeTest {

    @Test
    fun width_below_600_is_compact() {
        assertEquals(WindowLayoutMode.COMPACT, windowLayoutModeFor(0))
        assertEquals(WindowLayoutMode.COMPACT, windowLayoutModeFor(320))
        assertEquals(WindowLayoutMode.COMPACT, windowLayoutModeFor(390))
        assertEquals(WindowLayoutMode.COMPACT, windowLayoutModeFor(599))
    }

    @Test
    fun width_600_to_839_is_medium() {
        assertEquals(WindowLayoutMode.MEDIUM, windowLayoutModeFor(600))
        assertEquals(WindowLayoutMode.MEDIUM, windowLayoutModeFor(700))
        assertEquals(WindowLayoutMode.MEDIUM, windowLayoutModeFor(839))
    }

    @Test
    fun width_840_and_above_is_expanded() {
        assertEquals(WindowLayoutMode.EXPANDED, windowLayoutModeFor(840))
        assertEquals(WindowLayoutMode.EXPANDED, windowLayoutModeFor(1024))
        assertEquals(WindowLayoutMode.EXPANDED, windowLayoutModeFor(2000))
    }

    @Test
    fun compact_to_medium_boundary_at_600() {
        assertEquals(WindowLayoutMode.COMPACT, windowLayoutModeFor(599))
        assertEquals(WindowLayoutMode.MEDIUM, windowLayoutModeFor(600))
    }

    @Test
    fun medium_to_expanded_boundary_at_840() {
        assertEquals(WindowLayoutMode.MEDIUM, windowLayoutModeFor(839))
        assertEquals(WindowLayoutMode.EXPANDED, windowLayoutModeFor(840))
    }
}
