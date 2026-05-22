package dev.puklic.protocol.discord.rest

import dev.puklic.protocol.discord.DEFAULT_BROWSER_VERSION
import dev.puklic.protocol.discord.DEFAULT_CLIENT_BUILD_NUMBER
import dev.puklic.protocol.discord.DEFAULT_DESKTOP_USER_AGENT
import dev.puklic.protocol.discord.DiscordClientProperties
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class DiscordRestClient_HeadersTest {

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun every_rest_request_sends_full_desktop_client_identity_headers() = runTest {
        val captured = mutableListOf<io.ktor.http.Headers>()
        val engine = MockEngine { request ->
            captured += request.headers
            respond(
                content = ByteReadChannel("""{"id":"1","username":"u"}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val props = DiscordClientProperties(
            os = "Mac OS X",
            systemLocale = "en-US",
            browserUserAgent = DEFAULT_DESKTOP_USER_AGENT,
            browserVersion = DEFAULT_BROWSER_VERSION,
            osVersion = "14.5.0",
        )
        val client = DiscordRestClient(
            HttpClient(engine),
            token = "test-token",
            clientProperties = props,
            timeZoneId = "Europe/Prague",
        )
        client.getSelfUser()

        val headers = captured.single()
        assertEquals("test-token", headers[HttpHeaders.Authorization])
        assertEquals(DEFAULT_DESKTOP_USER_AGENT, headers[HttpHeaders.UserAgent])
        assertEquals("Europe/Prague", headers["X-Discord-Timezone"])
        assertEquals("en-US", headers["X-Discord-Locale"])
        assertEquals("bugReporterEnabled", headers["X-Debug-Options"])
        assertEquals("https://discord.com/channels/@me", headers[HttpHeaders.Referrer])

        val superPropsB64 = headers["X-Super-Properties"]
        assertNotNull(superPropsB64)
        val decoded = Base64.decode(superPropsB64).decodeToString()
        val obj = Json.parseToJsonElement(decoded) as JsonObject
        assertEquals("Mac OS X", obj.getValue("os").jsonPrimitive.content)
        assertEquals("Discord Client", obj.getValue("browser").jsonPrimitive.content)
        assertEquals("en-US", obj.getValue("system_locale").jsonPrimitive.content)
        assertEquals(DEFAULT_DESKTOP_USER_AGENT, obj.getValue("browser_user_agent").jsonPrimitive.content)
        assertEquals(DEFAULT_CLIENT_BUILD_NUMBER, obj.getValue("client_build_number").jsonPrimitive.int)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun super_properties_is_computed_once_and_identical_across_requests() = runTest {
        val captured = mutableListOf<String?>()
        val engine = MockEngine { request ->
            captured += request.headers["X-Super-Properties"]
            respond(
                content = ByteReadChannel("[]"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")
        client.getSelfUser()
        client.getGuilds()
        client.getDmChannels()

        assertEquals(3, captured.size)
        val first = captured.first()
        assertNotNull(first)
        assertTrue(captured.all { it == first }, "X-Super-Properties must be identical across requests")
    }
}
