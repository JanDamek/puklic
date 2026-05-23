package dev.puklic.protocol.discord.rest

import dev.puklic.domain.EmojiRef
import dev.puklic.ids.ChannelId
import dev.puklic.ids.EmojiId
import dev.puklic.ids.MessageId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests for [DiscordRestClient.addReaction] / [DiscordRestClient.removeOwnReaction].
 *
 * Discord REST surface:
 *   PUT    /channels/{cid}/messages/{mid}/reactions/{emoji}/@me
 *   DELETE /channels/{cid}/messages/{mid}/reactions/{emoji}/@me
 *
 * Unicode emoji are percent-encoded raw UTF-8 (👍 → %F0%9F%91%8D).
 * Custom emoji are `name:id` with the `:` literal (Acheron parity).
 */
class DiscordRestClientReactionsTest {

    private val channelId = ChannelId(111L)
    private val messageId = MessageId(222L)

    @Test
    fun add_reaction_unicode_thumbs_up_puts_percent_encoded_path() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedPath: String? = null
        val engine = MockEngine { request ->
            capturedMethod = request.method
            capturedPath = request.url.encodedPath
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")

        val result = client.addReaction(channelId, messageId, EmojiRef.Unicode("👍"))

        assertTrue(result.isSuccess, "addReaction must succeed on 204; was $result")
        assertEquals(HttpMethod.Put, capturedMethod)
        assertEquals(
            "/api/v10/channels/111/messages/222/reactions/%F0%9F%91%8D/@me",
            capturedPath,
        )
    }

    @Test
    fun add_reaction_custom_emoji_puts_name_colon_id_path() = runTest {
        var capturedPath: String? = null
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")

        val result = client.addReaction(
            channelId,
            messageId,
            EmojiRef.Custom(id = EmojiId(123L), name = "partyparrot", animated = false),
        )

        assertTrue(result.isSuccess)
        assertEquals(
            "/api/v10/channels/111/messages/222/reactions/partyparrot:123/@me",
            capturedPath,
        )
    }

    @Test
    fun remove_own_reaction_unicode_deletes_percent_encoded_path() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedPath: String? = null
        val engine = MockEngine { request ->
            capturedMethod = request.method
            capturedPath = request.url.encodedPath
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        val client = DiscordRestClient(HttpClient(engine), token = "t")

        val result = client.removeOwnReaction(channelId, messageId, EmojiRef.Unicode("👍"))

        assertTrue(result.isSuccess)
        assertEquals(HttpMethod.Delete, capturedMethod)
        assertEquals(
            "/api/v10/channels/111/messages/222/reactions/%F0%9F%91%8D/@me",
            capturedPath,
        )
    }

    @Test
    fun add_reaction_includes_authorization_header() = runTest {
        var auth: String? = null
        val engine = MockEngine { request ->
            auth = request.headers[HttpHeaders.Authorization]
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.NoContent,
                headers = headersOf(),
            )
        }
        val client = DiscordRestClient(HttpClient(engine), token = "abc")
        client.addReaction(channelId, messageId, EmojiRef.Unicode("👍"))
        assertEquals("abc", auth)
    }
}
