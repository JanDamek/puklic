package dev.puklic.protocol.discord.rest

import dev.puklic.domain.EmojiRef
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.protocol.discord.DiscordClientProperties
import dev.puklic.protocol.discord.DiscordError
import dev.puklic.protocol.discord.DiscordJson
import dev.puklic.protocol.discord.buildClientProperties
import dev.puklic.protocol.discord.currentTimeZoneId
import dev.puklic.protocol.discord.encodeSuperProperties
import dev.puklic.protocol.discord.dto.AttachmentUploadFileDto
import dev.puklic.protocol.discord.dto.AttachmentUploadRequestDto
import dev.puklic.protocol.discord.dto.AttachmentUploadResponseDto
import dev.puklic.protocol.discord.dto.DiscordChannelDto
import dev.puklic.protocol.discord.dto.DiscordGuildDto
import dev.puklic.protocol.discord.dto.DiscordMessageDto
import dev.puklic.protocol.discord.dto.DiscordUserDto
import dev.puklic.protocol.discord.dto.FinalizedAttachmentDto
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.encodeURLPathPart
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

    /**
     * Create a DM channel with the given recipient. Per Discord API v10 docs:
     * `POST /users/@me/channels` with body `{"recipients":["<user_id>"]}` returns either a new
     * DM channel or the existing one with that recipient — idempotent on Discord's side
     * (issue #17). No client-side dedup needed.
     */
    internal suspend fun createDm(recipientId: dev.puklic.ids.UserId): Result<DiscordChannelDto> =
        runCatching {
            executeWithRetry {
                httpClient.post("$baseUrl/users/@me/channels") {
                    applyAuth()
                    contentType(ContentType.Application.Json)
                    setBody(
                        DiscordJson.encodeToString(
                            JsonElement.serializer(),
                            buildJsonObject {
                                put(
                                    "recipients",
                                    kotlinx.serialization.json.buildJsonArray {
                                        add(kotlinx.serialization.json.JsonPrimitive(recipientId.value.toString()))
                                    },
                                )
                            },
                        ),
                    )
                }
            }
        }.fold(
            onSuccess = { response -> decodeOrError(response, serializer<DiscordChannelDto>()) },
            onFailure = { Result.failure(it) },
        )

    internal suspend fun getMessages(
        channelId: ChannelId,
        limit: Int = MESSAGE_LIMIT_DEFAULT,
        before: MessageId? = null,
        after: MessageId? = null,
        guildId: GuildId? = null,
        channelType: Int = CHANNEL_TYPE_GUILD_TEXT,
    ): Result<List<DiscordMessageDto>> = runCatching {
        val url = "$baseUrl/channels/${channelId.value}/messages"
        Logger.i(REST_TAG) {
            "getMessages url=$url limit=$limit before=${before?.value} after=${after?.value} guild=${guildId?.value} type=$channelType"
        }
        executeWithRetry {
            httpClient.get(url) {
                applyAuth()
                applyChannelContextProperties(channelId, guildId, channelType)
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
        attachments: List<FinalizedAttachmentDto> = emptyList(),
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
                            if (attachments.isNotEmpty()) {
                                put(
                                    "attachments",
                                    kotlinx.serialization.json.buildJsonArray {
                                        attachments.forEach { att ->
                                            add(
                                                buildJsonObject {
                                                    put("id", att.id)
                                                    put("filename", att.filename)
                                                    put("uploaded_filename", att.uploadedFilename)
                                                },
                                            )
                                        }
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

    /**
     * Pre-upload step of Discord's attachment flow. Requests one CDN upload slot per file.
     * The returned [AttachmentUploadResponseDto.attachments] entries are paired with the input
     * by their numeric `id` (Discord echoes the request `id` back as an Int). Per issue #23.
     */
    internal suspend fun requestUploadUrls(
        channelId: ChannelId,
        files: List<AttachmentUploadFileDto>,
    ): Result<AttachmentUploadResponseDto> = runCatching {
        executeWithRetry {
            httpClient.post("$baseUrl/channels/${channelId.value}/attachments") {
                applyAuth()
                contentType(ContentType.Application.Json)
                setBody(
                    DiscordJson.encodeToString(
                        AttachmentUploadRequestDto.serializer(),
                        AttachmentUploadRequestDto(files = files),
                    ),
                )
            }
        }
    }.fold(
        onSuccess = { response -> decodeOrError(response, serializer<AttachmentUploadResponseDto>()) },
        onFailure = { Result.failure(it) },
    )

    /**
     * Raw PUT of [bytes] to a CDN [uploadUrl] previously returned by [requestUploadUrls]. The
     * CDN host rejects the user-account `Authorization` header (Discord-S.C.U.M parity), so this
     * call deliberately bypasses [applyAuth]. Network errors surface as [DiscordError.Network];
     * non-2xx CDN responses surface as [DiscordError.ServerError].
     */
    public suspend fun uploadFile(
        uploadUrl: String,
        bytes: ByteArray,
        contentType: String?,
    ): Result<Unit> = runCatching {
        executeWithRetry {
            httpClient.put(uploadUrl) {
                // Intentionally NOT calling applyAuth() — Discord's CDN rejects auth headers
                // on these one-shot upload URLs (issue #23).
                val ct = contentType?.let { ContentType.parse(it) } ?: ContentType.Application.OctetStream
                contentType(ct)
                setBody(bytes)
            }
        }
    }.fold(
        onSuccess = { response ->
            if (response.status.isSuccess()) Result.success(Unit) else Result.failure(errorOf(response))
        },
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

    /**
     * Add the bot/user's reaction (`@me`) on [messageId]. Discord returns 204 No Content
     * on success. URL path: `/channels/{cid}/messages/{mid}/reactions/{emoji}/@me`.
     *
     * For [EmojiRef.Unicode] the codepoint is percent-encoded as raw UTF-8
     * (e.g. `👍` → `%F0%9F%91%8D`). For [EmojiRef.Custom] the path segment is
     * `name:id` — the literal `:` is preserved (Acheron parity; Discord requires it).
     */
    public suspend fun addReaction(
        channelId: ChannelId,
        messageId: MessageId,
        emoji: EmojiRef,
    ): Result<Unit> = runCatching {
        executeWithRetry {
            httpClient.put(
                "$baseUrl/channels/${channelId.value}/messages/${messageId.value}" +
                    "/reactions/${emoji.toReactionPath()}/@me",
            ) {
                applyAuth()
            }
        }
    }.fold(
        onSuccess = { response ->
            if (response.status.isSuccess()) Result.success(Unit) else Result.failure(errorOf(response))
        },
        onFailure = { Result.failure(it) },
    )

    /**
     * Remove the bot/user's own reaction (`@me`) from [messageId]. Discord returns
     * 204 No Content on success.
     */
    public suspend fun removeOwnReaction(
        channelId: ChannelId,
        messageId: MessageId,
        emoji: EmojiRef,
    ): Result<Unit> = runCatching {
        executeWithRetry {
            httpClient.delete(
                "$baseUrl/channels/${channelId.value}/messages/${messageId.value}" +
                    "/reactions/${emoji.toReactionPath()}/@me",
            ) {
                applyAuth()
            }
        }
    }.fold(
        onSuccess = { response ->
            if (response.status.isSuccess()) Result.success(Unit) else Result.failure(errorOf(response))
        },
        onFailure = { Result.failure(it) },
    )

    /**
     * Stop ringing the specified [recipients] for an in-progress DM call ring. Discord returns
     * 204 No Content on success. Per architect-report 2026-05-25-dm-incoming-voice §7, 404 and
     * 410 are treated as success (the call has already ended or been declined elsewhere).
     */
    public suspend fun stopRinging(
        channelId: ChannelId,
        recipients: List<dev.puklic.ids.UserId>,
    ): Result<Unit> = runCatching {
        executeWithRetry {
            httpClient.post("$baseUrl/channels/${channelId.value}/call/stop-ringing") {
                applyAuth()
                contentType(ContentType.Application.Json)
                setBody(
                    DiscordJson.encodeToString(
                        dev.puklic.protocol.discord.dto.StopRingingDto.serializer(),
                        dev.puklic.protocol.discord.dto.StopRingingDto(
                            recipients = recipients.map { it.value.toString() },
                        ),
                    ),
                )
            }
        }
    }.fold(
        onSuccess = { response ->
            val s = response.status.value
            when {
                response.status.isSuccess() -> Result.success(Unit)
                // Already-gone — call ended or declined elsewhere. Treat as success.
                s == HttpStatusCode.NotFound.value || s == HttpStatusCode.Gone.value -> Result.success(Unit)
                else -> Result.failure(errorOf(response))
            }
        },
        onFailure = { Result.failure(it) },
    )

    /**
     * Fetch a single message by id. Used by the voice layer's caller-id message-author fallback
     * chain when CALL_CREATE arrives with an empty `voice_states` array but a `message_id` set.
     */
    internal suspend fun getMessage(
        channelId: ChannelId,
        messageId: MessageId,
    ): Result<DiscordMessageDto> = request(
        "$baseUrl/channels/${channelId.value}/messages/${messageId.value}",
        serializer<DiscordMessageDto>(),
    )

    private fun EmojiRef.toReactionPath(): String = when (this) {
        is EmojiRef.Unicode -> codepoint.encodeURLPathPart()
        is EmojiRef.Custom -> "$name:${id.value}".encodeURLPathPart()
    }

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
        channelType: Int,
    ) {
        val json = if (guildId != null) {
            buildJsonObject {
                put("location", "Guild Channel List")
                put("location_guild_id", guildId.value.toString())
                put("location_channel_id", channelId.value.toString())
                put("location_channel_type", channelType)
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

    public companion object {
        /** Discord channel type 0 — `GUILD_TEXT`. Default fallback for [getMessages]. */
        public const val CHANNEL_TYPE_GUILD_TEXT: Int = 0
        /** Discord channel type 5 — `GUILD_ANNOUNCEMENT`. */
        public const val CHANNEL_TYPE_GUILD_ANNOUNCEMENT: Int = 5

        internal val SUCCESS_RANGE = 200..299
        internal val SERVER_ERROR_RANGE = 500..599
        internal const val BODY_PREVIEW = 256
        internal const val MILLIS_IN_SECOND = 1000.0
    }
}
