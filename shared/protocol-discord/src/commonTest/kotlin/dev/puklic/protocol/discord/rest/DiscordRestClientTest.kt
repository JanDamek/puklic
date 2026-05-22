package dev.puklic.protocol.discord.rest

import dev.puklic.ids.ChannelId
import dev.puklic.protocol.discord.DiscordError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DiscordRestClientTest {
    @Test
    fun get_self_user_success() = runTest {
        val engine = MockEngine { request ->
            assertTrue(request.url.toString().endsWith("/users/@me"))
            assertEquals("test-token", request.headers[HttpHeaders.Authorization])
            jsonResponse("""{"id":"42","username":"u"}""")
        }
        val client = DiscordRestClient(HttpClient(engine), token = "test-token")
        val result = client.getSelfUser()
        assertTrue(result.isSuccess)
        assertEquals("42", result.getOrThrow().id)
    }

    @Test
    fun unauthorized_returns_token_invalid() = runTest {
        val engine = MockEngine { _ ->
            respondError(HttpStatusCode.Unauthorized, "{\"message\":\"401: Unauthorized\"}")
        }
        val client = DiscordRestClient(HttpClient(engine), token = "bad")
        val result = client.getSelfUser()
        assertTrue(result.isFailure)
        assertIs<DiscordError.TokenInvalid>(result.exceptionOrNull())
    }

    @Test
    fun forbidden_returns_forbidden() = runTest {
        val engine = MockEngine { _ ->
            respondError(HttpStatusCode.Forbidden, "no permission")
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")
        val result = client.getMessages(ChannelId(1L))
        assertIs<DiscordError.Forbidden>(result.exceptionOrNull())
    }

    @Test
    fun server_error_retried_then_fails() = runTest {
        var attempts = 0
        val engine = MockEngine { _ ->
            attempts++
            respondError(HttpStatusCode.InternalServerError, "boom")
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")
        val result = client.getSelfUser()
        assertTrue(result.isFailure)
        assertIs<DiscordError.ServerError>(result.exceptionOrNull())
        assertEquals(4, attempts) // 1 initial + 3 retries
    }

    @Test
    fun rate_limit_with_retry_after_eventually_succeeds() = runTest {
        var attempts = 0
        val engine = MockEngine { _ ->
            attempts++
            if (attempts == 1) {
                respond(
                    content = ByteReadChannel("""{"message":"too many"}"""),
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf("Retry-After" to listOf("0"), HttpHeaders.ContentType to listOf("application/json")),
                )
            } else {
                jsonResponse("""{"id":"99","username":"u"}""")
            }
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")
        val result = client.getSelfUser()
        assertTrue(result.isSuccess)
        assertEquals("99", result.getOrThrow().id)
        assertEquals(2, attempts)
    }

    @Test
    fun delete_returns_unit_on_204() = runTest {
        val engine = MockEngine { _ -> respond(content = "", status = HttpStatusCode.NoContent) }
        val client = DiscordRestClient(HttpClient(engine), token = "t")
        val result = client.deleteMessage(ChannelId(1L), dev.puklic.ids.MessageId(2L))
        assertTrue(result.isSuccess)
    }

    @Test
    fun start_typing_returns_unit() = runTest {
        val engine = MockEngine { _ -> respondOk("") }
        val client = DiscordRestClient(HttpClient(engine), token = "t")
        val result = client.startTyping(ChannelId(1L))
        assertTrue(result.isSuccess)
    }

    private fun MockRequestHandleScope.jsonResponse(body: String): HttpResponseData =
        respond(
            content = ByteReadChannel(body),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
        )

    @Suppress("UnusedPrivateMember")
    private fun unused(req: HttpRequestData) = req // keep MockEngine import set tight
}
