package dev.puklic.ui.components

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #91 Czech date divider tests for [czechLongDate] and [isNewDay], which live in
 * [MessageDividers]. RED until impl lands.
 */
class MessageDividersTest {

    // --- czechLongDate ---

    @Test
    fun czechLongDate_march() {
        assertEquals("21. března 2026", czechLongDate(LocalDate(2026, 3, 21)))
    }

    @Test
    fun czechLongDate_january_no_leading_zero() {
        assertEquals("1. ledna 2026", czechLongDate(LocalDate(2026, 1, 1)))
    }

    @Test
    fun czechLongDate_december() {
        assertEquals("9. prosince 2025", czechLongDate(LocalDate(2025, 12, 9)))
    }

    @Test
    fun czechLongDate_september() {
        assertEquals("30. září 2026", czechLongDate(LocalDate(2026, 9, 30)))
    }

    // --- isNewDay ---

    @Test
    fun isNewDay_true_when_previous_null() {
        val current = LocalDateTime(2026, 3, 21, 10, 0).toInstant(TimeZone.UTC)
        assertTrue(isNewDay(current = current, previousOlder = null, zone = TimeZone.UTC))
    }

    @Test
    fun isNewDay_false_same_day_different_time() {
        val current = LocalDateTime(2026, 3, 21, 23, 0).toInstant(TimeZone.UTC)
        val previous = LocalDateTime(2026, 3, 21, 1, 0).toInstant(TimeZone.UTC)
        assertFalse(isNewDay(current = current, previousOlder = previous, zone = TimeZone.UTC))
    }

    @Test
    fun isNewDay_true_consecutive_days() {
        val current = LocalDateTime(2026, 3, 22, 0, 30).toInstant(TimeZone.UTC)
        val previous = LocalDateTime(2026, 3, 21, 23, 30).toInstant(TimeZone.UTC)
        assertTrue(isNewDay(current = current, previousOlder = previous, zone = TimeZone.UTC))
    }
}
