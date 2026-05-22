package dev.puklic.domain

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec: docs/02_domain/chat-model.md — "Channel", "GuildTextChannel", "DmChannel", "ChannelType"
 *
 * Covers:
 *  - GuildTextChannel construction and field preservation
 *  - DmChannel construction and field preservation
 *  - Channel sealed hierarchy exhaustiveness via when() — all branches handled
 *  - GuildTextChannel.type == ChannelType.GUILD_TEXT (hard-coded override in spec)
 *  - DmChannel.type == ChannelType.DM (hard-coded override in spec)
 *  - DmChannel.name == null (spec: "override val name: String? = null")
 *  - ChannelType enum has all 13 values per spec
 *  - Optional fields (parentId, topic, nsfw, etc.)
 *  - Structural equality
 */
class ChannelHierarchyTest {

    // ── GuildTextChannel construction ─────────────────────────────────────────

    /**
     * Spec: chat-model.md — GuildTextChannel field list
     */
    @Test
    fun `GuildTextChannel construction preserves all fields`() {
        val channel = GuildTextChannel(
            id = ChannelId(111L),
            name = "general",
            guildId = GuildId(222L),
            parentId = ChannelId(333L),
            topic = "Talk about everything",
            position = 1,
            rateLimitPerUser = 5,
            nsfw = false,
        )
        assertEquals(ChannelId(111L), channel.id)
        assertEquals("general", channel.name)
        assertEquals(GuildId(222L), channel.guildId)
        assertEquals(ChannelId(333L), channel.parentId)
        assertEquals("Talk about everything", channel.topic)
        assertEquals(1, channel.position)
        assertEquals(5, channel.rateLimitPerUser)
        assertEquals(false, channel.nsfw)
    }

    /**
     * Spec: chat-model.md — "override val type = ChannelType.GUILD_TEXT"
     * The type field must be GUILD_TEXT; it is set by the class, not the caller.
     */
    @Test
    fun `GuildTextChannel type is always GUILD_TEXT`() {
        val channel = GuildTextChannel(
            id = ChannelId(1L),
            name = "test",
            guildId = GuildId(1L),
            parentId = null,
            topic = null,
            position = 0,
            rateLimitPerUser = 0,
            nsfw = false,
        )
        assertEquals(ChannelType.GUILD_TEXT, channel.type)
    }

    @Test
    fun `GuildTextChannel with null optional fields is valid`() {
        val channel = GuildTextChannel(
            id = ChannelId(2L),
            name = null,           // name is nullable per Channel interface
            guildId = GuildId(2L),
            parentId = null,       // no parent category
            topic = null,          // no topic
            position = 0,
            rateLimitPerUser = 0,
            nsfw = false,
        )
        assertNull(channel.name)
        assertNull(channel.parentId)
        assertNull(channel.topic)
    }

    @Test
    fun `GuildTextChannel nsfw true is preserved`() {
        val channel = GuildTextChannel(
            id = ChannelId(3L),
            name = "nsfw-channel",
            guildId = GuildId(3L),
            parentId = null,
            topic = null,
            position = 5,
            rateLimitPerUser = 0,
            nsfw = true,
        )
        assertTrue(channel.nsfw)
    }

    // ── DmChannel construction ────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — DmChannel with recipients list
     */
    @Test
    fun `DmChannel construction preserves id and recipients`() {
        val recipient = UserSummary(
            id = UserId(777L),
            username = "friend",
            globalName = "My Friend",
            discriminator = null,
            avatarHash = null,
            bot = false,
            system = false,
        )
        val dm = DmChannel(
            id = ChannelId(888L),
            recipients = listOf(recipient),
        )
        assertEquals(ChannelId(888L), dm.id)
        assertEquals(1, dm.recipients.size)
        assertEquals(recipient, dm.recipients[0])
    }

    /**
     * Spec: chat-model.md — "override val name: String? = null"
     * DmChannel never has a name — it is always null.
     */
    @Test
    fun `DmChannel name is always null`() {
        val dm = DmChannel(
            id = ChannelId(10L),
            recipients = emptyList(),
        )
        assertNull(dm.name)
    }

    /**
     * Spec: chat-model.md — "override val type = ChannelType.DM"
     */
    @Test
    fun `DmChannel type is always DM`() {
        val dm = DmChannel(
            id = ChannelId(20L),
            recipients = emptyList(),
        )
        assertEquals(ChannelType.DM, dm.type)
    }

    @Test
    fun `DmChannel with empty recipients list is valid`() {
        val dm = DmChannel(
            id = ChannelId(30L),
            recipients = emptyList(),
        )
        assertTrue(dm.recipients.isEmpty())
    }

    @Test
    fun `DmChannel with multiple recipients is valid`() {
        fun user(id: Long) = UserSummary(
            id = UserId(id),
            username = "user$id",
            globalName = null,
            discriminator = "0001",
            avatarHash = null,
            bot = false,
            system = false,
        )
        val dm = DmChannel(
            id = ChannelId(40L),
            recipients = listOf(user(1L), user(2L)),
        )
        assertEquals(2, dm.recipients.size)
    }

    // ── Sealed hierarchy exhaustiveness ───────────────────────────────────────

    /**
     * Spec: chat-model.md — "Sealed hierarchy → exhaustive when in UI / repository,
     *   no runtime casts."
     *
     * A when() with no else branch must cover both GuildTextChannel and DmChannel.
     * If a new subtype is added to the sealed interface without updating this test
     * the compiler will error — which is the intended spec-enforcement mechanism.
     */
    @Test
    fun `sealed Channel when expression covers GuildTextChannel and DmChannel without else`() {
        val channels: List<Channel> = listOf(
            GuildTextChannel(
                id = ChannelId(100L),
                name = "general",
                guildId = GuildId(100L),
                parentId = null,
                topic = null,
                position = 0,
                rateLimitPerUser = 0,
                nsfw = false,
            ),
            DmChannel(
                id = ChannelId(200L),
                recipients = emptyList(),
            ),
        )

        val labels = channels.map { channel ->
            // No `else` branch — compiler enforces exhaustiveness
            when (channel) {
                is GuildTextChannel -> "guild_text"
                is DmChannel        -> "dm"
            }
        }

        assertEquals(listOf("guild_text", "dm"), labels)
    }

    @Test
    fun `Channel interface id and type properties accessible without cast`() {
        // Verify that the Channel interface contract (id, name, type) is accessible
        // on a raw Channel reference — no downcast needed for shared fields.
        val channel: Channel = GuildTextChannel(
            id = ChannelId(999L),
            name = "shared-fields",
            guildId = GuildId(999L),
            parentId = null,
            topic = null,
            position = 0,
            rateLimitPerUser = 0,
            nsfw = false,
        )
        assertEquals(ChannelId(999L), channel.id)
        assertEquals("shared-fields", channel.name)
        assertEquals(ChannelType.GUILD_TEXT, channel.type)
    }

    // ── ChannelType enum ──────────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — "enum class ChannelType { GUILD_TEXT, DM, GUILD_VOICE,
     *   GROUP_DM, GUILD_CATEGORY, GUILD_ANNOUNCEMENT, ANNOUNCEMENT_THREAD,
     *   PUBLIC_THREAD, PRIVATE_THREAD, GUILD_STAGE_VOICE, GUILD_DIRECTORY,
     *   GUILD_FORUM, GUILD_MEDIA }"
     * That is exactly 13 values.
     */
    @Test
    fun `ChannelType has exactly 13 values matching the spec`() {
        val expected = setOf(
            ChannelType.GUILD_TEXT,
            ChannelType.DM,
            ChannelType.GUILD_VOICE,
            ChannelType.GROUP_DM,
            ChannelType.GUILD_CATEGORY,
            ChannelType.GUILD_ANNOUNCEMENT,
            ChannelType.ANNOUNCEMENT_THREAD,
            ChannelType.PUBLIC_THREAD,
            ChannelType.PRIVATE_THREAD,
            ChannelType.GUILD_STAGE_VOICE,
            ChannelType.GUILD_DIRECTORY,
            ChannelType.GUILD_FORUM,
            ChannelType.GUILD_MEDIA,
        )
        assertEquals(13, ChannelType.entries.size, "ChannelType must have exactly 13 entries per spec")
        assertEquals(expected, ChannelType.entries.toSet())
    }

    // ── Structural equality ───────────────────────────────────────────────────

    @Test
    fun `two GuildTextChannel instances with identical fields are equal`() {
        val a = GuildTextChannel(
            id = ChannelId(50L),
            name = "eq-channel",
            guildId = GuildId(50L),
            parentId = null,
            topic = "Same topic",
            position = 3,
            rateLimitPerUser = 10,
            nsfw = false,
        )
        val b = GuildTextChannel(
            id = ChannelId(50L),
            name = "eq-channel",
            guildId = GuildId(50L),
            parentId = null,
            topic = "Same topic",
            position = 3,
            rateLimitPerUser = 10,
            nsfw = false,
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `GuildTextChannel and DmChannel with same ChannelId are not equal`() {
        val gtc = GuildTextChannel(
            id = ChannelId(77L),
            name = "text",
            guildId = GuildId(77L),
            parentId = null,
            topic = null,
            position = 0,
            rateLimitPerUser = 0,
            nsfw = false,
        )
        val dm = DmChannel(
            id = ChannelId(77L),
            recipients = emptyList(),
        )
        // Different runtime types — must not be equal even with same id
        assertNotEquals<Any>(gtc, dm)
    }
}
