package dev.puklic.domain

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec: docs/02_domain/chat-model.md — "MessageEmbed" and supporting nested types:
 *   EmbedFooter, EmbedMedia, EmbedProvider, EmbedAuthor, EmbedField
 *
 * Covers:
 *  - MessageEmbed construction with all-null optional fields (valid minimum)
 *  - MessageEmbed with fully-populated fields
 *  - Each nested type construction and field preservation
 *  - fields: List<EmbedField> — empty and non-empty
 *  - Structural equality and inequality for MessageEmbed and nested types
 */
class EmbedTest {

    // ── Minimal embed (all optionals null) ────────────────────────────────────

    /**
     * Spec: chat-model.md — "MessageEmbed" — all fields are Optional (nullable or list)
     * A minimal embed where Discord sends only the type is valid.
     */
    @Test
    fun `MessageEmbed with all null optional fields and empty fields list is valid`() {
        val embed = MessageEmbed(
            title = null,
            type = null,
            description = null,
            url = null,
            timestamp = null,
            color = null,
            footer = null,
            image = null,
            thumbnail = null,
            video = null,
            provider = null,
            author = null,
            fields = emptyList(),
        )
        assertNull(embed.title)
        assertNull(embed.type)
        assertNull(embed.description)
        assertNull(embed.url)
        assertNull(embed.timestamp)
        assertNull(embed.color)
        assertNull(embed.footer)
        assertNull(embed.image)
        assertNull(embed.thumbnail)
        assertNull(embed.video)
        assertNull(embed.provider)
        assertNull(embed.author)
        assertTrue(embed.fields.isEmpty())
    }

    // ── Fully-populated embed ─────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — "type: String? — "rich", "image", "video", "link", ..."
     */
    @Test
    fun `MessageEmbed rich type with all fields populated preserves each value`() {
        val ts = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val embed = MessageEmbed(
            title = "Release v1.0",
            type = "rich",
            description = "Puklic 1.0 is out!",
            url = "https://example.com/release",
            timestamp = ts,
            color = 0x5865F2, // Blurple
            footer = EmbedFooter(text = "Puklic Project", iconUrl = "https://example.com/icon.png"),
            image = EmbedMedia(url = "https://example.com/banner.png", proxyUrl = null, width = 1200, height = 630),
            thumbnail = EmbedMedia(url = "https://example.com/thumb.png", proxyUrl = null, width = 80, height = 80),
            video = null,
            provider = EmbedProvider(name = "GitHub", url = "https://github.com"),
            author = EmbedAuthor(name = "Jan Damek", url = "https://github.com/jandam", iconUrl = null),
            fields = listOf(
                EmbedField(name = "Version", value = "1.0.0", inline = true),
                EmbedField(name = "Platform", value = "Linux", inline = true),
            ),
        )

        assertEquals("Release v1.0", embed.title)
        assertEquals("rich", embed.type)
        assertEquals("Puklic 1.0 is out!", embed.description)
        assertEquals("https://example.com/release", embed.url)
        assertEquals(ts, embed.timestamp)
        assertEquals(0x5865F2, embed.color)
        assertEquals(2, embed.fields.size)
    }

    // ── EmbedFooter ───────────────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — "EmbedFooter" (detail per Discord API docs)
     */
    @Test
    fun `EmbedFooter with text and iconUrl preserves both fields`() {
        val footer = EmbedFooter(text = "Footer text", iconUrl = "https://example.com/icon.png")
        assertEquals("Footer text", footer.text)
        assertEquals("https://example.com/icon.png", footer.iconUrl)
    }

    @Test
    fun `EmbedFooter with null iconUrl is valid`() {
        val footer = EmbedFooter(text = "No icon footer", iconUrl = null)
        assertEquals("No icon footer", footer.text)
        assertNull(footer.iconUrl)
    }

    @Test
    fun `two EmbedFooter instances with identical fields are equal`() {
        val a = EmbedFooter(text = "Same", iconUrl = "https://example.com/icon.png")
        val b = EmbedFooter(text = "Same", iconUrl = "https://example.com/icon.png")
        assertEquals(a, b)
    }

    // ── EmbedMedia ────────────────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — "EmbedMedia" (image, thumbnail, video sub-fields)
     */
    @Test
    fun `EmbedMedia with all fields preserves url, proxyUrl, width, height`() {
        val media = EmbedMedia(
            url = "https://example.com/image.png",
            proxyUrl = "https://media.example.com/image.png",
            width = 800,
            height = 600,
        )
        assertEquals("https://example.com/image.png", media.url)
        assertEquals("https://media.example.com/image.png", media.proxyUrl)
        assertEquals(800, media.width)
        assertEquals(600, media.height)
    }

    @Test
    fun `EmbedMedia with null optional fields is valid`() {
        val media = EmbedMedia(url = "https://example.com/image.png", proxyUrl = null, width = null, height = null)
        assertNull(media.proxyUrl)
        assertNull(media.width)
        assertNull(media.height)
    }

    @Test
    fun `two EmbedMedia instances with identical fields are equal`() {
        val a = EmbedMedia("https://x.com/a.png", null, 100, 200)
        val b = EmbedMedia("https://x.com/a.png", null, 100, 200)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ── EmbedProvider ─────────────────────────────────────────────────────────

    @Test
    fun `EmbedProvider with name and url preserves both`() {
        val provider = EmbedProvider(name = "YouTube", url = "https://youtube.com")
        assertEquals("YouTube", provider.name)
        assertEquals("https://youtube.com", provider.url)
    }

    @Test
    fun `EmbedProvider with null name and null url is valid`() {
        val provider = EmbedProvider(name = null, url = null)
        assertNull(provider.name)
        assertNull(provider.url)
    }

    // ── EmbedAuthor ───────────────────────────────────────────────────────────

    @Test
    fun `EmbedAuthor with all fields preserves name, url, iconUrl`() {
        val author = EmbedAuthor(
            name = "Jan Damek",
            url = "https://github.com/jandam",
            iconUrl = "https://example.com/avatar.png",
        )
        assertEquals("Jan Damek", author.name)
        assertEquals("https://github.com/jandam", author.url)
        assertEquals("https://example.com/avatar.png", author.iconUrl)
    }

    @Test
    fun `EmbedAuthor with null optional fields is valid`() {
        val author = EmbedAuthor(name = "Anonymous", url = null, iconUrl = null)
        assertEquals("Anonymous", author.name)
        assertNull(author.url)
        assertNull(author.iconUrl)
    }

    // ── EmbedField ────────────────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — "EmbedField" with inline flag
     */
    @Test
    fun `EmbedField with inline true preserves name value and inline flag`() {
        val field = EmbedField(name = "Stars", value = "⭐ 1,234", inline = true)
        assertEquals("Stars", field.name)
        assertEquals("⭐ 1,234", field.value)
        assertEquals(true, field.inline)
    }

    @Test
    fun `EmbedField with inline false preserves all fields`() {
        val field = EmbedField(name = "Description", value = "A long description text", inline = false)
        assertEquals(false, field.inline)
    }

    @Test
    fun `two EmbedField instances with identical fields are equal`() {
        val a = EmbedField(name = "F", value = "V", inline = true)
        val b = EmbedField(name = "F", value = "V", inline = true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `EmbedField instances differing only by inline flag are not equal`() {
        val a = EmbedField(name = "F", value = "V", inline = true)
        val b = EmbedField(name = "F", value = "V", inline = false)
        assertNotEquals(a, b)
    }

    // ── MessageEmbed structural equality ─────────────────────────────────────

    @Test
    fun `two MessageEmbed instances with identical fields are equal`() {
        val a = MessageEmbed(
            title = "Same",
            type = "rich",
            description = null,
            url = null,
            timestamp = null,
            color = 0xFF0000,
            footer = null,
            image = null,
            thumbnail = null,
            video = null,
            provider = null,
            author = null,
            fields = listOf(EmbedField("F", "V", false)),
        )
        val b = MessageEmbed(
            title = "Same",
            type = "rich",
            description = null,
            url = null,
            timestamp = null,
            color = 0xFF0000,
            footer = null,
            image = null,
            thumbnail = null,
            video = null,
            provider = null,
            author = null,
            fields = listOf(EmbedField("F", "V", false)),
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `MessageEmbed instances differing only by title are not equal`() {
        val base = MessageEmbed(
            title = "Alpha",
            type = "rich",
            description = null,
            url = null,
            timestamp = null,
            color = null,
            footer = null,
            image = null,
            thumbnail = null,
            video = null,
            provider = null,
            author = null,
            fields = emptyList(),
        )
        val other = base.copy(title = "Beta")
        assertNotEquals(base, other)
    }

    @Test
    fun `MessageEmbed fields list order matters for equality`() {
        val f1 = EmbedField("A", "1", false)
        val f2 = EmbedField("B", "2", false)
        val embed1 = MessageEmbed(null, null, null, null, null, null, null, null, null, null, null, null, listOf(f1, f2))
        val embed2 = MessageEmbed(null, null, null, null, null, null, null, null, null, null, null, null, listOf(f2, f1))
        assertNotEquals(embed1, embed2)
    }
}
