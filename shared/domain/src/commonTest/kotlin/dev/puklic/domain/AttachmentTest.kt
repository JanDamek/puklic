package dev.puklic.domain

import dev.puklic.ids.AttachmentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Spec: docs/02_domain/chat-model.md — "Attachment"
 *
 * Covers:
 *  - Construction with all mandatory fields
 *  - Optional nullable fields: contentType, width, height, durationSecs, description
 *  - Image attachment (width + height non-null)
 *  - Voice message attachment (durationSecs non-null)
 *  - Plain file attachment (all optionals null)
 *  - Structural equality and inequality
 */
class AttachmentTest {

    // ── Helper fixture ────────────────────────────────────────────────────────

    private fun imageAttachment(
        id: Long = 1L,
        width: Int? = 1920,
        height: Int? = 1080,
        contentType: String? = "image/png",
        durationSecs: Float? = null,
        description: String? = null,
    ) = Attachment(
        id = AttachmentId(id),
        filename = "screenshot.png",
        size = 204800L,
        url = "https://cdn.discordapp.com/attachments/123/456/screenshot.png",
        proxyUrl = "https://media.discordapp.net/attachments/123/456/screenshot.png",
        contentType = contentType,
        width = width,
        height = height,
        durationSecs = durationSecs,
        description = description,
    )

    // ── Mandatory fields ──────────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — Attachment fields: id, filename, size, url, proxyUrl
     */
    @Test
    fun `Attachment mandatory fields are preserved exactly`() {
        val a = Attachment(
            id = AttachmentId(111222333444555666L),
            filename = "report.pdf",
            size = 1_048_576L,    // 1 MiB
            url = "https://cdn.discordapp.com/attachments/1/2/report.pdf",
            proxyUrl = "https://media.discordapp.net/attachments/1/2/report.pdf",
            contentType = "application/pdf",
            width = null,
            height = null,
            durationSecs = null,
            description = null,
        )
        assertEquals(AttachmentId(111222333444555666L), a.id)
        assertEquals("report.pdf", a.filename)
        assertEquals(1_048_576L, a.size)
        assertEquals("https://cdn.discordapp.com/attachments/1/2/report.pdf", a.url)
        assertEquals("https://media.discordapp.net/attachments/1/2/report.pdf", a.proxyUrl)
    }

    // ── Optional nullable fields ──────────────────────────────────────────────

    @Test
    fun `Attachment with all optional fields null is valid for plain file`() {
        val a = Attachment(
            id = AttachmentId(1L),
            filename = "data.csv",
            size = 512L,
            url = "https://cdn.discordapp.com/attachments/1/2/data.csv",
            proxyUrl = "https://media.discordapp.net/attachments/1/2/data.csv",
            contentType = null,
            width = null,
            height = null,
            durationSecs = null,
            description = null,
        )
        assertNull(a.contentType)
        assertNull(a.width)
        assertNull(a.height)
        assertNull(a.durationSecs)
        assertNull(a.description)
    }

    /**
     * Spec: chat-model.md — "width: Int?, height: Int? — image / video"
     */
    @Test
    fun `image Attachment preserves width and height`() {
        val a = imageAttachment(width = 1280, height = 720)
        assertEquals(1280, a.width)
        assertEquals(720, a.height)
    }

    @Test
    fun `image Attachment size is stored in bytes as Long`() {
        // Edge case: large attachment (Discord max is 500 MB for boosted servers)
        val a = Attachment(
            id = AttachmentId(2L),
            filename = "huge-video.mp4",
            size = 524_288_000L, // 500 MiB
            url = "https://cdn.discordapp.com/attachments/1/2/huge-video.mp4",
            proxyUrl = "https://media.discordapp.net/attachments/1/2/huge-video.mp4",
            contentType = "video/mp4",
            width = 3840,
            height = 2160,
            durationSecs = null,
            description = null,
        )
        assertEquals(524_288_000L, a.size)
        assertEquals(3840, a.width)
        assertEquals(2160, a.height)
    }

    /**
     * Spec: chat-model.md — "durationSecs: Float? — voice message"
     */
    @Test
    fun `voice message Attachment has durationSecs and no width or height`() {
        val a = Attachment(
            id = AttachmentId(3L),
            filename = "voice-message.ogg",
            size = 32768L,
            url = "https://cdn.discordapp.com/attachments/1/2/voice-message.ogg",
            proxyUrl = "https://media.discordapp.net/attachments/1/2/voice-message.ogg",
            contentType = "audio/ogg",
            width = null,
            height = null,
            durationSecs = 4.7f,
            description = null,
        )
        assertEquals(4.7f, a.durationSecs)
        assertNull(a.width)
        assertNull(a.height)
    }

    @Test
    fun `Attachment durationSecs of zero is preserved (silence voice message)`() {
        val a = Attachment(
            id = AttachmentId(4L),
            filename = "empty.ogg",
            size = 100L,
            url = "https://cdn.discordapp.com/attachments/1/2/empty.ogg",
            proxyUrl = "https://media.discordapp.net/attachments/1/2/empty.ogg",
            contentType = "audio/ogg",
            width = null,
            height = null,
            durationSecs = 0.0f,
            description = null,
        )
        assertEquals(0.0f, a.durationSecs)
    }

    /**
     * Spec: chat-model.md — "description: String? — alt text"
     */
    @Test
    fun `Attachment with alt text description is preserved`() {
        val a = imageAttachment(description = "A screenshot of the Puklic client")
        assertEquals("A screenshot of the Puklic client", a.description)
    }

    @Test
    fun `Attachment description with empty string is preserved`() {
        val a = imageAttachment(description = "")
        assertEquals("", a.description)
    }

    // ── Structural equality ───────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — "All data class instances have structural equality (Kotlin default)"
     */
    @Test
    fun `two Attachment instances with identical fields are equal`() {
        val a = imageAttachment(id = 10L)
        val b = imageAttachment(id = 10L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Attachment instances differing only by filename are not equal`() {
        val a = imageAttachment(id = 20L)
        val b = a.copy(filename = "different.png")
        assertNotEquals(a, b)
    }

    @Test
    fun `Attachment instances differing only by size are not equal`() {
        val a = imageAttachment(id = 30L)
        val b = a.copy(size = a.size + 1L)
        assertNotEquals(a, b)
    }

    @Test
    fun `Attachment instances differing only by contentType are not equal`() {
        val a = imageAttachment(id = 40L, contentType = "image/png")
        val b = a.copy(contentType = "image/jpeg")
        assertNotEquals(a, b)
    }
}
