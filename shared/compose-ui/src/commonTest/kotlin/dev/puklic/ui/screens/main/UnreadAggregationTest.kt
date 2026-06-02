package dev.puklic.ui.screens.main

import dev.puklic.domain.DmChannel
import dev.puklic.domain.GuildTextChannel
import dev.puklic.domain.UserSummary
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import dev.puklic.repositories.ReadStateView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnreadAggregationTest {

    private fun user(id: Long) = UserSummary(
        id = UserId(id),
        username = "u$id",
        globalName = null,
        discriminator = null,
        avatarHash = null,
        bot = false,
        system = false,
    )

    private fun dm(id: Long) = DmChannel(
        id = ChannelId(id),
        recipients = listOf(user(id + 1000)),
    )

    private fun text(channelId: Long, guildId: Long, position: Int = 0) = GuildTextChannel(
        id = ChannelId(channelId),
        guildId = GuildId(guildId),
        name = "ch-$channelId",
        position = position,
        topic = null,
        parentId = null,
        rateLimitPerUser = 0,
        nsfw = false,
    )

    @Test
    fun computeDmUnread_empty_when_no_read_state() {
        val dms = listOf(dm(1L), dm(2L))
        assertEquals(emptyMap(), computeDmUnread(dms, emptyMap()))
    }

    @Test
    fun computeDmUnread_returns_entry_for_acked_dm_only() {
        val dms = listOf(dm(1L), dm(2L))
        val readState = mapOf(
            ChannelId(1L) to ReadStateView(mentionCount = 3, hasUnread = true),
            ChannelId(999L) to ReadStateView(mentionCount = 1, hasUnread = true),
        )
        val result = computeDmUnread(dms, readState)
        assertEquals(1, result.size)
        assertEquals(3, result[ChannelId(1L)]?.mentionCount)
    }

    @Test
    fun guildHasMention_false_when_no_read_state() {
        val channels = listOf(text(10L, 42L), text(11L, 42L))
        assertFalse(guildHasMention(GuildId(42L), channels, emptyMap()))
    }

    @Test
    fun guildHasMention_true_when_channel_has_mention() {
        val channels = listOf(text(10L, 42L), text(11L, 42L))
        val readState = mapOf(
            ChannelId(11L) to ReadStateView(mentionCount = 2, hasUnread = true),
        )
        assertTrue(guildHasMention(GuildId(42L), channels, readState))
    }

    @Test
    fun guildHasMention_ignores_channels_from_other_guilds() {
        val channels = listOf(text(10L, 42L))
        val readState = mapOf(
            ChannelId(99L) to ReadStateView(mentionCount = 5, hasUnread = true),
        )
        assertFalse(guildHasMention(GuildId(42L), channels, readState))
    }

    @Test
    fun guildHasMention_false_for_unread_without_mention() {
        val channels = listOf(text(10L, 42L))
        val readState = mapOf(
            ChannelId(10L) to ReadStateView(mentionCount = 0, hasUnread = true),
        )
        assertFalse(guildHasMention(GuildId(42L), channels, readState))
    }
}
