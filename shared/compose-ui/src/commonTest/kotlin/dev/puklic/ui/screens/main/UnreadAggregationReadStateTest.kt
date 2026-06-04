package dev.puklic.ui.screens.main

import dev.puklic.ids.MessageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #91 read-state pure-function tests for [isChannelUnread], [shouldAck] and
 * [firstUnreadMessageId]. These functions live in [UnreadAggregation]. RED until impl lands.
 */
class UnreadAggregationReadStateTest {

    // --- isChannelUnread ---

    @Test
    fun isChannelUnread_false_when_channel_last_null() {
        assertFalse(isChannelUnread(channelLastMessageId = null, lastReadMessageId = MessageId(5L)))
    }

    @Test
    fun isChannelUnread_true_when_channel_last_with_null_last_read() {
        assertTrue(isChannelUnread(channelLastMessageId = MessageId(5L), lastReadMessageId = null))
    }

    @Test
    fun isChannelUnread_true_when_channel_last_greater_than_last_read() {
        assertTrue(isChannelUnread(channelLastMessageId = MessageId(10L), lastReadMessageId = MessageId(5L)))
    }

    @Test
    fun isChannelUnread_false_when_equal() {
        assertFalse(isChannelUnread(channelLastMessageId = MessageId(5L), lastReadMessageId = MessageId(5L)))
    }

    @Test
    fun isChannelUnread_false_when_channel_last_less_than_last_read() {
        assertFalse(isChannelUnread(channelLastMessageId = MessageId(3L), lastReadMessageId = MessageId(5L)))
    }

    // --- shouldAck ---

    @Test
    fun shouldAck_false_when_target_null() {
        assertFalse(shouldAck(target = null, lastAcked = null, lastRead = null))
    }

    @Test
    fun shouldAck_true_when_fresh_both_null() {
        assertTrue(shouldAck(target = MessageId(5L), lastAcked = null, lastRead = null))
    }

    @Test
    fun shouldAck_false_when_target_at_or_below_last_acked() {
        assertFalse(shouldAck(target = MessageId(5L), lastAcked = MessageId(5L), lastRead = null))
        assertFalse(shouldAck(target = MessageId(4L), lastAcked = MessageId(5L), lastRead = null))
    }

    @Test
    fun shouldAck_false_when_target_at_or_below_last_read() {
        assertFalse(shouldAck(target = MessageId(5L), lastAcked = null, lastRead = MessageId(5L)))
        assertFalse(shouldAck(target = MessageId(4L), lastAcked = null, lastRead = MessageId(5L)))
    }

    @Test
    fun shouldAck_true_when_target_greater_than_both() {
        assertTrue(shouldAck(target = MessageId(10L), lastAcked = MessageId(5L), lastRead = MessageId(7L)))
    }

    // --- firstUnreadMessageId ---

    @Test
    fun firstUnreadMessageId_null_when_empty() {
        assertNull(firstUnreadMessageId(messagesOldestFirst = emptyList(), lastReadMessageId = MessageId(5L)))
    }

    @Test
    fun firstUnreadMessageId_first_when_last_read_null_and_non_empty() {
        val messages = listOf(MessageId(1L), MessageId(2L), MessageId(3L))
        assertEquals(MessageId(1L), firstUnreadMessageId(messages, lastReadMessageId = null))
    }

    @Test
    fun firstUnreadMessageId_first_id_greater_than_last_read() {
        val messages = listOf(MessageId(1L), MessageId(2L), MessageId(3L), MessageId(4L))
        assertEquals(MessageId(3L), firstUnreadMessageId(messages, lastReadMessageId = MessageId(2L)))
    }

    @Test
    fun firstUnreadMessageId_null_when_all_read() {
        val messages = listOf(MessageId(1L), MessageId(2L), MessageId(3L))
        assertNull(firstUnreadMessageId(messages, lastReadMessageId = MessageId(3L)))
    }
}
