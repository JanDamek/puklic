package dev.puklic.protocol.discord.rest

import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies the outbound sendMessage JSON payload matches Acheron (and the official client):
 *  - always sends `flags`
 *  - always sends `mobile_network_type`
 *  - reply payload sends `channel_id` + `message_id` inside `message_reference`.
 */
class SendMessagePayloadTest {

    @Test
    fun send_message_includes_flags_and_mobile_network_type() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req ->
            captured = req
            respond(
                content = ByteReadChannel("""{"id":"123","channel_id":"1","content":"hi","author":{"id":"42","username":"u"},"timestamp":"2026-01-01T00:00:00Z","mentions":[],"mention_roles":[],"tts":false,"pinned":false,"type":0}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")
        val result = client.sendMessage(ChannelId(1L), "hi", nonce = "n-1", replyTo = null)
        assertTrue(result.isSuccess, "send must succeed; failure=${result.exceptionOrNull()}")

        val body = (captured?.body as io.ktor.http.content.TextContent).text
        assertTrue("\"flags\":0" in body, "expected flags=0 in body: $body")
        assertTrue("\"mobile_network_type\":\"unknown\"" in body, "mobile_network_type missing: $body")
        assertTrue("\"content\":\"hi\"" in body, body)
        assertTrue("\"nonce\":\"n-1\"" in body, body)
    }

    @Test
    fun reply_includes_channel_id_in_message_reference() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req ->
            captured = req
            respond(
                content = ByteReadChannel("""{"id":"123","channel_id":"55","content":"hi","author":{"id":"42","username":"u"},"timestamp":"2026-01-01T00:00:00Z","mentions":[],"mention_roles":[],"tts":false,"pinned":false,"type":0}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")
        val result = client.sendMessage(ChannelId(55L), "hi", nonce = null, replyTo = MessageId(999L))
        assertTrue(result.isSuccess)
        val body = (captured?.body as io.ktor.http.content.TextContent).text
        assertTrue("\"message_reference\"" in body, body)
        assertTrue("\"channel_id\":\"55\"" in body, body)
        assertTrue("\"message_id\":\"999\"" in body, body)
    }
}
