package dev.puklic.ui.components

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #91 date divider tests for [isNewDay], which lives in [MessageDividers].
 */
class MessageDividersTest {

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
