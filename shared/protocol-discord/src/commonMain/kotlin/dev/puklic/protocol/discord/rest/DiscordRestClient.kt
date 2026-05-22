package dev.puklic.protocol.discord.rest

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.protocol.discord.DiscordClientProperties
import dev.puklic.protocol.discord.DiscordError
import dev.puklic.protocol.discord.DiscordJson
import dev.puklic.protocol.discord.buildClientProperties
import dev.puklic.protocol.discord.currentTimeZoneId
import dev.puklic.protocol.discord.encodeSuperProperties
import dev.puklic.protocol.discord.dto.DiscordChannelDto
import dev.puklic.protocol.discord.dto.DiscordGuildDto
import dev.puklic.protocol.discord.dto.DiscordMessageDto
import dev.puklic.protocol.discord.dto.DiscordUserDto
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import co.touchlab.kermit.Logger
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val BASE_URL = "https://discord.com/api/v10"
private const val MAX_RETRIES = 3
private const val INITIAL_BACKOFF_MS = 1_000L
private const val MESSAGE_LIMIT_DEFAULT = 50
private const val REST_TAG = "DiscordRestClient"
private const val LOG_BODY_PREVIEW = 256

/**
 * Thin REST client over Discord API v10. Stateless wrt rate limits beyond honoring
 * `Retry-After` on 429 responses. Errors are surfaced as [Result.failure] with a
 * [DiscordError] subtype — tokens never appear in error messages.
 */
public class DiscordRestClient(
    private val httpClient: HttpClient,
    private val token: String,
    private val baseUrl: String = BASE_URL,
    clientProperties: DiscordClientProperties = buildClientProperties(),
    private val timeZoneId: String = currentTimeZoneId(),
) {
    private val clientProperties: DiscordClientProperties = clientProperties
    private val userAgent: String = clientProperties.browserUserAgent
    private val systemLocale: String = clientProperties.systemLocale
    // X-Super-Properties is identical for every request — encode once on init.
    private val superPropertiesB64: String = encodeSuperProperties(clientProperties)

    internal suspend fun getSelfUser(): Result<DiscordUserDto> =
        request("$baseUrl/users/@me", serializer<DiscordUserDto>())

    internal suspend fun getGuilds(): Result<List<DiscordGuildDto>> =
        request("$baseUrl/users/@me/guilds", ListSerializer(serializer<DiscordGuildDto>()))

    internal suspend fun getGuildChannels(guildId: GuildId): Result<List<DiscordChannelDto>> =
        request("$baseUrl/guilds/${guildId.value}/channels", ListSerializer(serializer<DiscordChannelDto>()))

    internal suspend fun getDmChannels(): Result<List<DiscordChannelDto>> =
        request("$baseUrl/users/@me/channels", ListSerializer(serializer<DiscordChannelDto>()))

    internal suspend fun getMessages(
        channelId: ChannelId,
        limit: Int = MESSAGE_LIMIT_DEFAULT,
        before: MessageId? = null,
        after: MessageId? = null,
        guildId: GuildId? = null,
    ): Result<List<DiscordMessageDto>> = runCatching {
        val url = "$baseUrl/channels/${channelId.value}/messages"
        Logger.i(REST_TAG) {
            "getMessages url=$url limit=$limit before=${before?.value} after=${after?.value} guild=${guildId?.value}"
        }
        executeWithRetry {
            httpClient.get(url) {
                applyAuth()
                applyChannelContextProperties(channelId, guildId)
                parameter("limit", limit)
                before?.let { parameter("before", it.value.toString()) }
                after?.let { parameter("after", it.value.toString()) }
            }
        }.also { response ->
            if (!response.status.isSuccess()) {
                val preview = runCatching { response.bodyAsText().take(LOG_BODY_PREVIEW) }.getOrNull()
                Logger.w(REST_TAG) {
                    "getMessages non-OK status=${response.status.value} channel=${channelId.value} body=$preview"
                }
            } else {
                Logger.i(REST_TAG) {
                    "getMessages ok status=${response.status.value} channel=${channelId.value}"
                }
            }
        }
    }.fold(
        onSuccess = { response -> decodeOrError(response, ListSerializer(serializer<DiscordMessageDto>())) },
        onFailure = { Result.failure(it) },
    )

    internal suspend fun sendMessage(
        channelId: ChannelId,
        content: String,
        nonce: String? = null,
        replyTo: MessageId? = null,
    ): Result<DiscordMessageDto> = runCatching {
        executeWithRetry {
            httpClient.post("$baseUrl/channels/${channelId.value}/messages") {
                applyAuth()
                contentType(ContentType.Application.Json)
                setBody(
                    DiscordJson.encodeToString(
                        JsonElement.serializer(),
                        buildJsonObject {
                            put("content", content)
                            // Match Acheron / official client: flags=0 and mobile_network_type
                            // on every send. Without these Discord may downgrade response.
                            put("flags", 0)
                            put("mobile_network_type", "unknown")
                            nonce?.let { put("nonce", it) }
                            replyTo?.let {
                                put(
                                    "message_reference",
                                    buildJsonObject {
                                        put("channel_id", channelId.value.toString())
                                        put("message_id", it.value.toString())
                                    },
                                )
                            }
                        },
                    ),
                )
            }
        }
    }.fold(
        onSuccess = { response -> decodeOrError(response, serializer<DiscordMessageDto>()) },
        onFailure = { Result.failure(it) },
    )

    internal suspend fun editMessage(
        channelId: ChannelId,
        messageId: MessageId,
        content: String,
    ): Result<DiscordMessageDto> = runCatching {
        executeWithRetry {
            httpClient.patch("$baseUrl/channels/${channelId.value}/messages/${messageId.value}") {
                applyAuth()
                contentType(ContentType.Application.Json)
                setBody(
                    DiscordJson.encodeToString(
                        JsonElement.serializer(),
                        buildJsonObject { put("content", content) },
                    ),
                )
            }
        }
    }.fold(
        onSuccess = { response -> decodeOrError(response, serializer<DiscordMessageDto>()) },
        onFailure = { Result.failure(it) },
    )

    internal suspend fun deleteMessage(channelId: ChannelId, messageId: MessageId): Result<Unit> = runCatching {
        executeWithRetry {
            httpClient.delete("$baseUrl/channels/${channelId.value}/messages/${messageId.value}") {
                applyAuth()
            }
        }
    }.fold(
        onSuccess = { response ->
            if (response.status.isSuccess()) Result.success(Unit) else Result.failure(errorOf(response))
        },
        onFailure = { Result.failure(it) },
    )

    internal suspend fun startTyping(channelId: ChannelId): Result<Unit> = runCatching {
        executeWithRetry {
            httpClient.post("$baseUrl/channels/${channelId.value}/typing") { applyAuth() }
        }
    }.fold(
        onSuccess = { response ->
            if (response.status.isSuccess()) Result.success(Unit) else Result.failure(errorOf(response))
        },
        onFailure = { Result.failure(it) },
    )

    // ── internals ─────────────────────────────────────────────────────────────

    private suspend fun <T> request(
        url: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): Result<T> = runCatching {
        executeWithRetry {
            httpClient.get(url) { applyAuth() }
        }
    }.fold(
        onSuccess = { response -> decodeOrError(response, deserializer) },
        onFailure = { Result.failure(it) },
    )

    private suspend fun executeWithRetry(block: suspend () -> HttpResponse): HttpResponse {
        var attempt = 0
        var backoff = INITIAL_BACKOFF_MS
        while (true) {
            val response = try {
                block()
            } catch (cause: kotlinx.coroutines.CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                throw DiscordError.Network(cause)
            }
            val status = response.status.value
            when {
                status == HttpStatusCode.TooManyRequests.value -> {
                    val retryAfterSec = response.headers["Retry-After"]?.toDoubleOrNull() ?: 1.0
                    val retryAfterMs = (retryAfterSec * MILLIS_IN_SECOND).toLong()
                    if (attempt < MAX_RETRIES) {
                        delay(retryAfterMs)
                        attempt++
                        continue
                    }
                    throw DiscordError.RateLimited(retryAfterMs)
                }
                status in SERVER_ERROR_RANGE -> {
                    if (attempt < MAX_RETRIES) {
                        delay(backoff)
                        backoff *= 2
                        attempt++
                        continue
                    }
                    throw DiscordError.ServerError(status, response.bodyAsText())
                }
                else -> return response
            }
        }
    }

    private suspend fun <T> decodeOrError(
        response: HttpResponse,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): Result<T> {
        if (!response.status.isSuccess()) return Result.failure(errorOf(response))
        val body = response.bodyAsText()
        return try {
            Result.success(DiscordJson.decodeFromString(deserializer, body))
        } catch (cause: SerializationException) {
            Result.failure(DiscordError.Deserialization(cause, body))
        }
    }

    private suspend fun errorOf(response: HttpResponse): DiscordError {
        val body = response.bodyAsText()
        return when (response.status.value) {
            HttpStatusCode.Unauthorized.value -> DiscordError.TokenInvalid
            HttpStatusCode.Forbidden.value -> DiscordError.Forbidden(body.take(BODY_PREVIEW))
            HttpStatusCode.NotFound.value -> DiscordError.NotFound
            else -> DiscordError.ServerError(response.status.value, body)
        }
    }

    /**
     * Apply the full Discord desktop-client identity header set. Sent on every REST request —
     * Discord's REST stack returns 50001 for legitimately accessible channels when the
     * caller does not look like the official client. See ADR-0002 (2026-05-22).
     *
     * Token is appended via the `Authorization` header but never logged.
     */
    private fun io.ktor.client.request.HttpRequestBuilder.applyAuth() {
        headers {
            append(HttpHeaders.Authorization, token)
            append(HttpHeaders.Accept, ContentType.Application.Json.toString())
            append(HttpHeaders.UserAgent, userAgent)
            append("X-Discord-Timezone", timeZoneId)
            append("X-Discord-Locale", systemLocale)
            append("X-Super-Properties", superPropertiesB64)
            append("X-Debug-Options", "bugReporterEnabled")
            append(HttpHeaders.Referrer, "https://discord.com/channels/@me")
        }
    }

    /**
     * Discord's official desktop client sends an `X-Context-Properties` header on channel reads
     * declaring the in-app "location" of the user. Without it, some guild text channels return
     * 50001 Missing Access even when the token has read permission. Body shape per Discord web
     * client reverse-engineering: `{ "location": "Guild Channel List", "location_guild_id":...,
     * "location_channel_id":..., "location_channel_type": 0 }`. For DM channels (no guildId) we
     * fall back to `{ "location": "Channel" }`.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun io.ktor.client.request.HttpRequestBuilder.applyChannelContextProperties(
        channelId: ChannelId,
        guildId: GuildId?,
    ) {
        val json = if (guildId != null) {
            buildJsonObject {
                put("location", "Guild Channel List")
                put("location_guild_id", guildId.value.toString())
                put("location_channel_id", channelId.value.toString())
                put("location_channel_type", 0)
            }
        } else {
            buildJsonObject { put("location", "Channel") }
        }
        val encoded = Base64.encode(
            DiscordJson.encodeToString(JsonElement.serializer(), json).encodeToByteArray(),
        )
        headers { append("X-Context-Properties", encoded) }
    }

    private fun HttpStatusCode.isSuccess(): Boolean = value in SUCCESS_RANGE

    private companion object {
        val SUCCESS_RANGE = 200..299
        val SERVER_ERROR_RANGE = 500..599
        const val BODY_PREVIEW = 256
        const val MILLIS_IN_SECOND = 1000.0
    }
}
