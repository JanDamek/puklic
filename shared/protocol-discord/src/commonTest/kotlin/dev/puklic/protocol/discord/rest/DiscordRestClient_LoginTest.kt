package dev.puklic.protocol.discord.rest

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DiscordRestClient_LoginTest {

    @Test
    fun credentials_success_returns_token_and_user_id() = runTest {
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            assertTrue(request.url.toString().endsWith("/auth/login"))
            assertEquals(null, request.headers[HttpHeaders.Authorization]) // no token on login
            capturedBody = request.body.toString()
            json(
                """{"token":"M.abc","user_id":"42","user_settings":{}}""",
            )
        }
        val client = DiscordLoginClient(HttpClient(engine))
        val result = client.loginWithCredentials("user@example.com", "secret")
        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertIs<LoginResponse.Success>(response)
        assertEquals("M.abc", response.token)
        assertEquals("42", response.userId)
        // Request body must carry `login` and `password` keys with provided values.
        assertTrue(capturedBody.orEmpty().contains("\"login\""))
    }

    @Test
    fun captcha_required_response_parsed() = runTest {
        val engine = MockEngine { _ ->
            json(
                """{"captcha_key":["captcha-required"],"captcha_sitekey":"site-1","captcha_service":"hcaptcha"}""",
            )
        }
        val client = DiscordLoginClient(HttpClient(engine))
        val response = client.loginWithCredentials("user", "pw").getOrThrow()
        assertIs<LoginResponse.CaptchaRequired>(response)
        assertEquals("site-1", response.sitekey)
        assertEquals("hcaptcha", response.service)
    }

    @Test
    fun mfa_required_response_parsed() = runTest {
        val engine = MockEngine { _ ->
            json(
                """{"mfa":true,"ticket":"tkt-xyz","totp":true,"sms":false,"backup":false,"webauthn":null}""",
            )
        }
        val client = DiscordLoginClient(HttpClient(engine))
        val response = client.loginWithCredentials("user", "pw").getOrThrow()
        assertIs<LoginResponse.MfaRequired>(response)
        assertEquals("tkt-xyz", response.ticket)
        assertTrue(response.totp)
    }

    @Test
    fun bad_credentials_400_returns_error() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(
                    """{"code":50035,"message":"Invalid Form Body","errors":{"login":{"_errors":[{"code":"INVALID_LOGIN","message":"Login or password is invalid."}]}}}""",
                ),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val client = DiscordLoginClient(HttpClient(engine))
        val response = client.loginWithCredentials("user", "pw").getOrThrow()
        assertIs<LoginResponse.Error>(response)
        assertEquals(50035, response.code)
        assertTrue(response.message.contains("Login or password is invalid", ignoreCase = true))
    }

    @Test
    fun mfa_endpoint_success_returns_token() = runTest {
        val engine = MockEngine { request ->
            assertTrue(request.url.toString().endsWith("/auth/mfa/totp"))
            json("""{"token":"M.mfa","user_id":"42"}""")
        }
        val client = DiscordLoginClient(HttpClient(engine))
        val response = client.loginWithMfa("ticket", "123456").getOrThrow()
        assertIs<LoginResponse.Success>(response)
        assertEquals("M.mfa", response.token)
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.json(body: String) =
        respond(
            content = ByteReadChannel(body),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
        )
}
