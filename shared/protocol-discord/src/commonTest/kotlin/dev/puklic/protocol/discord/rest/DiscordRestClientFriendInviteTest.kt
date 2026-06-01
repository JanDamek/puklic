package dev.puklic.protocol.discord.rest

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * REST coverage for issue #80 — friend-request + invite-accept endpoints.
 */
class DiscordRestClientFriendInviteTest {

    @Test
    fun addRelationship_pomelo_handle_posts_username_with_null_discriminator() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            captured += request
            respond(content = ByteReadChannel(""), status = HttpStatusCode.NoContent)
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")
        val result = client.addRelationship(username = "alice", discriminator = null)
        assertTrue(result.isSuccess)
        val req = captured.single()
        assertEquals(HttpMethod.Post, req.method)
        assertTrue(req.url.toString().endsWith("/users/@me/relationships"))
        val body = (req.body as io.ktor.http.content.TextContent).text
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals("alice", obj.getValue("username").jsonPrimitive.content)
        assertTrue(obj.getValue("discriminator") is JsonNull)
    }

    @Test
    fun addRelationship_legacy_form_posts_username_and_discriminator() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            captured += request
            respond(content = ByteReadChannel(""), status = HttpStatusCode.NoContent)
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")
        val result = client.addRelationship(username = "bob", discriminator = "1234")
        assertTrue(result.isSuccess)
        val body = (captured.single().body as io.ktor.http.content.TextContent).text
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals("bob", obj.getValue("username").jsonPrimitive.content)
        assertEquals("1234", obj.getValue("discriminator").jsonPrimitive.content)
    }

    @Test
    fun addRelationship_400_returns_failure() = runTest {
        val engine = MockEngine { _ ->
            respond(content = ByteReadChannel("""{"message":"Unknown User"}"""), status = HttpStatusCode.BadRequest)
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")
        val result = client.addRelationship(username = "ghost", discriminator = null)
        assertTrue(result.isFailure)
    }

    @Test
    fun acceptInvite_posts_to_invite_code_endpoint_and_returns_guild_dto() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val payload =
            """{"code":"abc","guild":{"id":"42","name":"Cool Server"}}"""
        val engine = MockEngine { request ->
            captured += request
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")
        val result = client.acceptInvite("abc")
        assertTrue(result.isSuccess)
        val guild = result.getOrThrow()
        assertNotNull(guild)
        assertEquals("42", guild.id)
        val req = captured.single()
        assertEquals(HttpMethod.Post, req.method)
        assertTrue(req.url.toString().endsWith("/invites/abc"))
    }

    @Test
    fun acceptInvite_without_embedded_guild_returns_null() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel("""{"code":"abc"}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")
        val result = client.acceptInvite("abc")
        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
    }

    @Test
    fun acceptInvite_404_returns_failure() = runTest {
        val engine = MockEngine { _ ->
            respond(content = ByteReadChannel(""), status = HttpStatusCode.NotFound)
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")
        val result = client.acceptInvite("nope")
        assertTrue(result.isFailure)
    }

    @Suppress("UnusedPrivateMember", "unused")
    private fun keepImports(o: JsonObject) = Unit
}
