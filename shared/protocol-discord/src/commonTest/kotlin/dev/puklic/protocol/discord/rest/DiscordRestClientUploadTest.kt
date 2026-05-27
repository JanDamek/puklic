package dev.puklic.protocol.discord.rest

import dev.puklic.ids.ChannelId
import dev.puklic.protocol.discord.dto.AttachmentUploadFileDto
import dev.puklic.protocol.discord.dto.FinalizedAttachmentDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #23 — `DiscordRestClient.requestUploadUrls`, `uploadFile`, and `sendMessage`
 * attachments-extension behaviour.
 */
class DiscordRestClientUploadTest {

    @Test
    fun requestUploadUrls_posts_files_array_to_channel_attachments_endpoint() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req ->
            captured = req
            respond(
                content = ByteReadChannel(
                    """{"attachments":[{"id":0,"upload_url":"https://cdn/upload?x=1","upload_filename":"abc/file.png"}]}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")

        val result = client.requestUploadUrls(
            ChannelId(42L),
            files = listOf(AttachmentUploadFileDto("file.png", 1234L, "0")),
        )

        assertTrue(result.isSuccess, "request must succeed; failure=${result.exceptionOrNull()}")
        val slots = result.getOrThrow().attachments
        assertEquals(1, slots.size)
        assertEquals("https://cdn/upload?x=1", slots[0].uploadUrl)
        assertEquals("abc/file.png", slots[0].uploadFilename)

        val req = captured ?: error("no request captured")
        assertEquals(HttpMethod.Post, req.method)
        assertTrue(req.url.toString().endsWith("/channels/42/attachments"), "wrong URL: ${req.url}")
        val body = (req.body as io.ktor.http.content.TextContent).text
        assertTrue("\"filename\":\"file.png\"" in body, body)
        assertTrue("\"file_size\":1234" in body, body)
        assertTrue("\"id\":\"0\"" in body, body)
    }

    @Test
    fun uploadFile_sends_PUT_without_authorization_header() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req ->
            captured = req
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.OK,
            )
        }
        val client = DiscordRestClient(HttpClient(engine), token = "secret-token")

        val result = client.uploadFile(
            uploadUrl = "https://cdn.discord.com/upload/abc",
            bytes = byteArrayOf(1, 2, 3, 4),
            contentType = "image/png",
        )

        assertTrue(result.isSuccess, "upload must succeed; failure=${result.exceptionOrNull()}")
        val req = captured ?: error("no request captured")
        assertEquals(HttpMethod.Put, req.method)
        assertFalse(
            req.headers.contains(HttpHeaders.Authorization),
            "CDN PUT must NOT carry Authorization header (Discord-S.C.U.M parity)",
        )
        assertEquals("https://cdn.discord.com/upload/abc", req.url.toString())
    }

    @Test
    fun sendMessage_includes_attachments_array_when_present() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req ->
            captured = req
            respond(
                content = ByteReadChannel(
                    """{"id":"5","channel_id":"1","content":"hi","author":{"id":"42","username":"u"},"timestamp":"2026-01-01T00:00:00Z","mentions":[],"mention_roles":[],"tts":false,"pinned":false,"type":0}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")

        client.sendMessage(
            ChannelId(1L),
            "hi",
            nonce = "n",
            replyTo = null,
            attachments = listOf(
                FinalizedAttachmentDto(id = "0", filename = "file.png", uploadedFilename = "abc/file.png"),
            ),
        )

        val body = (captured?.body as io.ktor.http.content.TextContent).text
        assertTrue("\"attachments\":[" in body, "attachments array missing: $body")
        assertTrue("\"id\":\"0\"" in body, body)
        assertTrue("\"filename\":\"file.png\"" in body, body)
        assertTrue("\"uploaded_filename\":\"abc/file.png\"" in body, body)
    }

    @Test
    fun sendMessage_omits_attachments_array_when_empty() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req ->
            captured = req
            respond(
                content = ByteReadChannel(
                    """{"id":"5","channel_id":"1","content":"hi","author":{"id":"42","username":"u"},"timestamp":"2026-01-01T00:00:00Z","mentions":[],"mention_roles":[],"tts":false,"pinned":false,"type":0}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")

        client.sendMessage(ChannelId(1L), "hi", nonce = "n", replyTo = null)

        val body = (captured?.body as io.ktor.http.content.TextContent).text
        assertFalse("\"attachments\":" in body, "attachments array must not be present: $body")
    }
}
