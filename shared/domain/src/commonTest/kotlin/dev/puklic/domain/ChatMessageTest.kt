package dev.puklic.domain

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.ids.UserId
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec: docs/02_domain/chat-model.md — "ChatMessage"
 *
 * Covers:
 *  - Construction with all required fields
 *  - Optional fields: editedTimestamp, referencedMessage (null and non-null)
 *  - rawContent preserved as single source of truth
 *  - parsedContent preserved (RichTextDocument)
 *  - attachments, embeds, reactions are immutable lists
 *  - MessageFlags enum values: PINNED, EPHEMERAL, SUPPRESS_EMBEDS, TTS
 *  - MessageReference construction with nullable fields
 *  - ReferenceType enum: REPLY, FORWARD
 *  - Structural equality and inequality
 *  - Immutability: updating a ChatMessage produces a new instance via copy()
 */
class ChatMessageTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun minimalAuthor() = UserSummary(
        id = UserId(111L),
        username = "alice",
        globalName = "Alice",
        discriminator = null,
        avatarHash = null,
        bot = false,
        system = false,
    )

    private fun emptyDocument() = RichTextDocument(blocks = emptyList())

    private val ts = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun minimalMessage(
        id: Long = 1L,
        rawContent: String = "Hello",
        flags: MessageFlags = MessageFlags.NONE,
        editedTimestamp: Instant? = null,
        referencedMessage: MessageReference? = null,
    ) = ChatMessage(
        id = MessageId(id),
        channelId = ChannelId(500L),
        author = minimalAuthor(),
        rawContent = rawContent,
        parsedContent = emptyDocument(),
        attachments = emptyList(),
        embeds = emptyList(),
        reactions = emptyList(),
        mentions = MessageMentions(emptyList(), emptyList(), emptyList(), false),
        flags = flags,
        timestamp = ts,
        editedTimestamp = editedTimestamp,
        referencedMessage = referencedMessage,
    )

    // ── Construction ──────────────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — ChatMessage fields
     */
    @Test
    fun `ChatMessage construction preserves all mandatory fields`() {
        val msg = minimalMessage(id = 99L, rawContent = "Test message")
        assertEquals(MessageId(99L), msg.id)
        assertEquals(ChannelId(500L), msg.channelId)
        assertEquals(minimalAuthor(), msg.author)
        assertEquals("Test message", msg.rawContent)
        assertEquals(emptyDocument(), msg.parsedContent)
        assertEquals(ts, msg.timestamp)
    }

    @Test
    fun `ChatMessage with empty rawContent is valid`() {
        // Edge case: Discord allows empty string on system messages
        val msg = minimalMessage(rawContent = "")
        assertEquals("", msg.rawContent)
    }

    @Test
    fun `ChatMessage rawContent of max Discord length 4000 chars is preserved`() {
        val longContent = "A".repeat(4000)
        val msg = minimalMessage(rawContent = longContent)
        assertEquals(4000, msg.rawContent.length)
    }

    // ── Optional fields ───────────────────────────────────────────────────────

    @Test
    fun `ChatMessage with null editedTimestamp is valid for non-edited message`() {
        val msg = minimalMessage(editedTimestamp = null)
        assertNull(msg.editedTimestamp)
    }

    @Test
    fun `ChatMessage with non-null editedTimestamp is preserved for edited message`() {
        val edited = Instant.fromEpochMilliseconds(1_700_000_001_000L)
        val msg = minimalMessage(editedTimestamp = edited)
        assertEquals(edited, msg.editedTimestamp)
    }

    @Test
    fun `ChatMessage with null referencedMessage is valid for standalone message`() {
        val msg = minimalMessage(referencedMessage = null)
        assertNull(msg.referencedMessage)
    }

    @Test
    fun `ChatMessage with referencedMessage is preserved for reply`() {
        val ref = MessageReference(
            messageId = MessageId(50L),
            channelId = ChannelId(500L),
            guildId = GuildId(5000L),
            type = ReferenceType.REPLY,
        )
        val msg = minimalMessage(referencedMessage = ref)
        assertEquals(ref, msg.referencedMessage)
        assertEquals(ReferenceType.REPLY, msg.referencedMessage?.type)
    }

    // ── Lists: attachments, embeds, reactions ─────────────────────────────────

    @Test
    fun `ChatMessage with empty attachment embed and reaction lists is valid`() {
        val msg = minimalMessage()
        assertTrue(msg.attachments.isEmpty())
        assertTrue(msg.embeds.isEmpty())
        assertTrue(msg.reactions.isEmpty())
    }

    @Test
    fun `ChatMessage attachments list order is preserved`() {
        val a1 = Attachment(
            id = dev.puklic.ids.AttachmentId(1L),
            filename = "first.png",
            size = 100L,
            url = "https://cdn.discordapp.com/1",
            proxyUrl = "https://media.discordapp.net/1",
            contentType = "image/png",
            width = 100,
            height = 100,
            durationSecs = null,
            description = null,
        )
        val a2 = a1.copy(id = dev.puklic.ids.AttachmentId(2L), filename = "second.png")
        val msg = ChatMessage(
            id = MessageId(1L),
            channelId = ChannelId(500L),
            author = minimalAuthor(),
            rawContent = "two attachments",
            parsedContent = emptyDocument(),
            attachments = listOf(a1, a2),
            embeds = emptyList(),
            reactions = emptyList(),
            mentions = MessageMentions(emptyList(), emptyList(), emptyList(), false),
            flags = MessageFlags.NONE,
            timestamp = ts,
            editedTimestamp = null,
            referencedMessage = null,
        )
        assertEquals(listOf(a1, a2), msg.attachments)
    }

    // ── MessageFlags enum ─────────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — "flags: MessageFlags // pinned, tts, suppress_embeds, ephemeral, ..."
     * Phase 1 flags: PINNED, EPHEMERAL, SUPPRESS_EMBEDS, TTS
     */
    @Test
    fun `MessageFlags enum provides PINNED EPHEMERAL SUPPRESS_EMBEDS TTS and NONE`() {
        // If any constant name is wrong, this line won't compile
        val pinned: MessageFlags = MessageFlags.PINNED
        val ephemeral: MessageFlags = MessageFlags.EPHEMERAL
        val suppressEmbeds: MessageFlags = MessageFlags.SUPPRESS_EMBEDS
        val tts: MessageFlags = MessageFlags.TTS
        val none: MessageFlags = MessageFlags.NONE

        // All must be distinct
        val distinct = setOf(pinned, ephemeral, suppressEmbeds, tts, none)
        assertTrue(distinct.size >= 5, "All 5 MessageFlags values must be distinct")
    }

    @Test
    fun `ChatMessage with PINNED flag preserves the flag`() {
        val msg = minimalMessage(flags = MessageFlags.PINNED)
        assertEquals(MessageFlags.PINNED, msg.flags)
    }

    @Test
    fun `ChatMessage with EPHEMERAL flag preserves the flag`() {
        val msg = minimalMessage(flags = MessageFlags.EPHEMERAL)
        assertEquals(MessageFlags.EPHEMERAL, msg.flags)
    }

    @Test
    fun `ChatMessage with TTS flag preserves the flag`() {
        val msg = minimalMessage(flags = MessageFlags.TTS)
        assertEquals(MessageFlags.TTS, msg.flags)
    }

    @Test
    fun `ChatMessage with SUPPRESS_EMBEDS flag preserves the flag`() {
        val msg = minimalMessage(flags = MessageFlags.SUPPRESS_EMBEDS)
        assertEquals(MessageFlags.SUPPRESS_EMBEDS, msg.flags)
    }

    // ── MessageReference ──────────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — MessageReference with all nullable fields
     */
    @Test
    fun `MessageReference with all non-null fields preserves each`() {
        val ref = MessageReference(
            messageId = MessageId(10L),
            channelId = ChannelId(20L),
            guildId = GuildId(30L),
            type = ReferenceType.REPLY,
        )
        assertEquals(MessageId(10L), ref.messageId)
        assertEquals(ChannelId(20L), ref.channelId)
        assertEquals(GuildId(30L), ref.guildId)
        assertEquals(ReferenceType.REPLY, ref.type)
    }

    @Test
    fun `MessageReference with all nullable fields null is valid for cross-channel forward`() {
        val ref = MessageReference(
            messageId = null,
            channelId = null,
            guildId = null,
            type = ReferenceType.FORWARD,
        )
        assertNull(ref.messageId)
        assertNull(ref.channelId)
        assertNull(ref.guildId)
        assertEquals(ReferenceType.FORWARD, ref.type)
    }

    // ── ReferenceType enum ────────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — "ReferenceType // REPLY, FORWARD"
     */
    @Test
    fun `ReferenceType enum has exactly REPLY and FORWARD values`() {
        val expected = setOf(ReferenceType.REPLY, ReferenceType.FORWARD)
        assertEquals(2, ReferenceType.entries.size, "ReferenceType must have exactly 2 entries per spec")
        assertEquals(expected, ReferenceType.entries.toSet())
    }

    // ── Immutability: updating produces new instance ──────────────────────────

    /**
     * Spec: chat-model.md — "Updating a message = new ChatMessage"
     * data class copy() must produce a new instance with the updated field.
     */
    @Test
    fun `copy with updated rawContent produces new instance and original is unchanged`() {
        val original = minimalMessage(rawContent = "original text")
        val updated = original.copy(rawContent = "edited text")

        assertEquals("original text", original.rawContent)
        assertEquals("edited text", updated.rawContent)
        assertNotEquals(original, updated)
    }

    @Test
    fun `copy with updated editedTimestamp produces new instance`() {
        val original = minimalMessage(editedTimestamp = null)
        val editTs = Instant.fromEpochMilliseconds(1_700_000_999_000L)
        val updated = original.copy(editedTimestamp = editTs)

        assertNull(original.editedTimestamp)
        assertEquals(editTs, updated.editedTimestamp)
        assertNotEquals(original, updated)
    }

    // ── Structural equality ───────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — "All data class instances have structural equality (Kotlin default)"
     */
    @Test
    fun `two ChatMessage instances with identical fields are equal`() {
        val a = minimalMessage(id = 1L, rawContent = "hello")
        val b = minimalMessage(id = 1L, rawContent = "hello")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ChatMessage instances differing only by id are not equal`() {
        val a = minimalMessage(id = 1L)
        val b = minimalMessage(id = 2L)
        assertNotEquals(a, b)
    }

    @Test
    fun `ChatMessage instances differing only by rawContent are not equal`() {
        val a = minimalMessage(rawContent = "Hello")
        val b = minimalMessage(rawContent = "Goodbye")
        assertNotEquals(a, b)
    }

    @Test
    fun `ChatMessage instances differing only by flags are not equal`() {
        val a = minimalMessage(flags = MessageFlags.NONE)
        val b = minimalMessage(flags = MessageFlags.PINNED)
        assertNotEquals(a, b)
    }
}
