package dev.puklic.protocol.discord.rest

import dev.puklic.ids.UserId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DiscordRestClientCreateDmTest {

    @Test
    fun create_dm_posts_correct_body_and_returns_channel() = runTest {
        val recipient = UserId(123456789L)
        val captured = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            captured += request
            respond(
                content = ByteReadChannel(
                    """{"id":"99","type":1,"recipients":[{"id":"123456789","username":"alice"}]}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")
        val result = client.createDm(recipient)
        assertTrue(result.isSuccess)
        val dto = result.getOrThrow()
        assertEquals("99", dto.id)
        assertEquals(1, dto.type)

        val request = captured.single()
        assertEquals(HttpMethod.Post, request.method)
        assertTrue(request.url.toString().endsWith("/users/@me/channels"))
        val bodyText = (request.body as io.ktor.http.content.TextContent).text
        val obj = Json.parseToJsonElement(bodyText).jsonObject
        val recipients = obj.getValue("recipients").jsonArray
        assertEquals(1, recipients.size)
        assertEquals(recipient.value.toString(), recipients[0].jsonPrimitive.content)
    }

    @Test
    fun create_dm_returns_existing_channel_when_called_twice() = runTest {
        // Discord's documented behavior: POST /users/@me/channels with the same recipient
        // returns the existing channel (same id). The client must not dedupe — Discord does.
        val recipient = UserId(7L)
        val sameDmPayload =
            """{"id":"555","type":1,"recipients":[{"id":"7","username":"bob"}]}"""
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(sameDmPayload),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")
        val first = client.createDm(recipient).getOrThrow()
        val second = client.createDm(recipient).getOrThrow()
        assertEquals(first.id, second.id)
        assertEquals("555", second.id)
    }

    @Suppress("UnusedPrivateMember", "unused")
    private fun keepImports(o: JsonObject, a: JsonArray) = Unit
}
