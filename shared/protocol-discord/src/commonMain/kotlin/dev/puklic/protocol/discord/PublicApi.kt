package dev.puklic.protocol.discord

import dev.puklic.domain.ChatMessage
import dev.puklic.domain.Channel
import dev.puklic.domain.Guild
import dev.puklic.domain.UserSummary
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.ids.UserId
import dev.puklic.protocol.discord.dto.DiscordMessageDto
import dev.puklic.protocol.discord.dto.DiscordUserDto
import dev.puklic.protocol.discord.gateway.GatewayConnection
import dev.puklic.protocol.discord.gateway.GatewayDispatchEvent
import dev.puklic.protocol.discord.mapper.toDomain
import dev.puklic.protocol.discord.mapper.toDomainOrNull
import dev.puklic.protocol.discord.rest.DiscordRestClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Public API surface for the wiring layer (`:shared:session` / `:desktop:app`).
 *
 * The protocol-discord module keeps its DTOs `internal` (per ADR-0006). This file exposes
 * domain-typed bridges that translate Discord DTOs to the `:shared:domain` types without
 * leaking the DTOs themselves. Downstream modules consume these bridges and adapt them to
 * the repository / session interfaces defined elsewhere.
 */

/** Provides the Discord-tolerant Json instance for callers wiring Ktor ContentNegotiation. */
public fun discordJson(): Json = DiscordJson

/**
 * Domain-typed mirror of the Discord gateway dispatch events the wiring layer cares about.
 * Mirrors `:shared:repositories::GatewayDomainEvent` in shape but uses only `:shared:domain`
 * types — keeping protocol-discord free of a dependency on repositories.
 */
public sealed interface DiscordDomainEvent {
    public data class MessageCreated(val message: ChatMessage) : DiscordDomainEvent
    public data class MessageUpdated(val message: ChatMessage) : DiscordDomainEvent
    public data class MessageDeleted(val channelId: ChannelId, val messageId: MessageId) : DiscordDomainEvent

    /** Coarse presence state: ONLINE / IDLE / DND / OFFLINE / INVISIBLE as raw strings from Discord. */
    public data class PresenceUpdated(val userId: UserId, val rawStatus: String) : DiscordDomainEvent
    public data class TypingStarted(
        val channelId: ChannelId,
        val userId: UserId,
        val timestampEpochSeconds: Long,
    ) : DiscordDomainEvent
    public data class GuildCreated(val guild: Guild) : DiscordDomainEvent
    public data class GuildUpdated(val guild: Guild) : DiscordDomainEvent
    public data class GuildDeleted(val guildId: GuildId) : DiscordDomainEvent
    public data class ChannelCreated(val channel: Channel) : DiscordDomainEvent
    public data class ChannelUpdated(val channel: Channel) : DiscordDomainEvent
    public data class ChannelDeleted(val channelId: ChannelId) : DiscordDomainEvent
    public data class UserUpdated(val user: UserSummary) : DiscordDomainEvent
    public data class Ready(val selfUser: UserSummary, val sessionId: String) : DiscordDomainEvent
}

private const val EVENT_BUFFER = 64

/**
 * Bridges a [GatewayConnection]'s raw dispatch stream to a domain-typed [SharedFlow]. Unknown
 * event types are dropped silently — the wiring layer is free to log them via [onUnknown].
 */
public class DiscordGatewayBridge(
    gateway: GatewayConnection,
    scope: CoroutineScope,
    onUnknown: (type: String) -> Unit = {},
) {
    private val _events = MutableSharedFlow<DiscordDomainEvent>(extraBufferCapacity = EVENT_BUFFER)
    public val events: SharedFlow<DiscordDomainEvent> = _events.asSharedFlow()

    init {
        scope.launch {
            gateway.events.collect { dispatch ->
                val mapped = mapDispatch(dispatch, onUnknown)
                mapped.forEach { _events.tryEmit(it) }
            }
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun mapDispatch(event: GatewayDispatchEvent, onUnknown: (String) -> Unit): List<DiscordDomainEvent> {
        val payload = event.payload
        return runCatching {
            when (event.type) {
                "MESSAGE_CREATE" -> listOf(DiscordDomainEvent.MessageCreated(
                    DiscordJson.decodeFromJsonElement(DiscordMessageDto.serializer(), payload).toDomain(),
                ))
                "MESSAGE_UPDATE" -> listOf(DiscordDomainEvent.MessageUpdated(
                    DiscordJson.decodeFromJsonElement(DiscordMessageDto.serializer(), payload).toDomain(),
                ))
                "MESSAGE_DELETE" -> {
                    val obj = payload.jsonObject
                    listOf(DiscordDomainEvent.MessageDeleted(
                        channelId = ChannelId(obj.getValue("channel_id").jsonPrimitive.content.toLong()),
                        messageId = MessageId(obj.getValue("id").jsonPrimitive.content.toLong()),
                    ))
                }
                "PRESENCE_UPDATE" -> {
                    val obj = payload.jsonObject
                    val userObj = obj.getValue("user").jsonObject
                    val userId = UserId(userObj.getValue("id").jsonPrimitive.content.toLong())
                    val status = obj["status"]?.jsonPrimitive?.content ?: "offline"
                    listOf(DiscordDomainEvent.PresenceUpdated(userId, status))
                }
                "TYPING_START" -> {
                    val obj = payload.jsonObject
                    listOf(DiscordDomainEvent.TypingStarted(
                        channelId = ChannelId(obj.getValue("channel_id").jsonPrimitive.content.toLong()),
                        userId = UserId(obj.getValue("user_id").jsonPrimitive.content.toLong()),
                        timestampEpochSeconds = obj["timestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    ))
                }
                "GUILD_CREATE", "GUILD_UPDATE" -> {
                    val dto = DiscordJson.decodeFromJsonElement(
                        dev.puklic.protocol.discord.dto.DiscordGuildDto.serializer(),
                        payload,
                    )
                    val guild = dto.toDomainOrNull() ?: return@runCatching emptyList()
                    val guildEvent = if (event.type == "GUILD_CREATE") DiscordDomainEvent.GuildCreated(guild)
                    else DiscordDomainEvent.GuildUpdated(guild)
                    listOf<DiscordDomainEvent>(guildEvent) + extractGuildChannels(payload, guild.id.value)
                }
                "GUILD_DELETE" -> {
                    val id = payload.jsonObject.getValue("id").jsonPrimitive.content.toLong()
                    listOf(DiscordDomainEvent.GuildDeleted(GuildId(id)))
                }
                "CHANNEL_CREATE", "CHANNEL_UPDATE" -> {
                    val channel = DiscordJson.decodeFromJsonElement(
                        dev.puklic.protocol.discord.dto.DiscordChannelDto.serializer(),
                        payload,
                    ).toDomain() ?: return@runCatching emptyList()
                    val ev = if (event.type == "CHANNEL_CREATE") DiscordDomainEvent.ChannelCreated(channel)
                    else DiscordDomainEvent.ChannelUpdated(channel)
                    listOf(ev)
                }
                "CHANNEL_DELETE" -> {
                    val id = payload.jsonObject.getValue("id").jsonPrimitive.content.toLong()
                    listOf(DiscordDomainEvent.ChannelDeleted(ChannelId(id)))
                }
                "USER_UPDATE" -> listOf(DiscordDomainEvent.UserUpdated(
                    DiscordJson.decodeFromJsonElement(DiscordUserDto.serializer(), payload).toDomain(),
                ))
                "READY" -> mapReady(payload)
                else -> {
                    onUnknown(event.type)
                    emptyList()
                }
            }
        }.onFailure {
            // Malformed payload — drop silently so the gateway keeps flowing.
            onUnknown("${event.type}:decode-failed")
        }.getOrDefault(emptyList())
    }

    /**
     * READY for user accounts ships the full initial guild list inline (per Discord's user-mode
     * gateway). Bots receive empty/unavailable stubs in READY and full guild data in subsequent
     * GUILD_CREATE events. To support both, we emit Ready + a GuildCreated for every guild with
     * a non-null name (full payload) and skip unavailable stubs (name == null).
     */
    private fun mapReady(payload: JsonElement): List<DiscordDomainEvent> {
        val obj = payload.jsonObject
        val sessionId = obj.getValue("session_id").jsonPrimitive.content
        val self = DiscordJson.decodeFromJsonElement(
            DiscordUserDto.serializer(),
            obj.getValue("user"),
        ).toDomain()
        val events = mutableListOf<DiscordDomainEvent>(DiscordDomainEvent.Ready(self, sessionId))
        val guildsArray = obj["guilds"]?.let { it as? kotlinx.serialization.json.JsonArray } ?: return events
        for (guildElement in guildsArray) {
            val dto = runCatching {
                DiscordJson.decodeFromJsonElement(
                    dev.puklic.protocol.discord.dto.DiscordGuildDto.serializer(),
                    guildElement,
                )
            }.getOrNull() ?: continue
            val guild = dto.toDomainOrNull() ?: continue
            events += DiscordDomainEvent.GuildCreated(guild)
            events += extractGuildChannels(guildElement, guild.id.value)
        }
        return events
    }

    /**
     * Extracts the `channels` array embedded in a GUILD_CREATE / READY-guild payload and maps
     * each to a [DiscordDomainEvent.ChannelCreated]. Channels inside a guild payload do NOT have
     * a `guild_id` field set; we inject it from the parent guild before decoding.
     */
    private fun extractGuildChannels(guildPayload: JsonElement, guildId: Long): List<DiscordDomainEvent> {
        val channelsArray = (guildPayload as? kotlinx.serialization.json.JsonObject)
            ?.get("channels") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return channelsArray.mapNotNull { channelElement ->
            val channelObj = channelElement as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val withGuild = kotlinx.serialization.json.buildJsonObject {
                channelObj.forEach { (k, v) -> put(k, v) }
                put("guild_id", JsonPrimitive(guildId.toString()))
            }
            val channel = runCatching {
                DiscordJson.decodeFromJsonElement(
                    dev.puklic.protocol.discord.dto.DiscordChannelDto.serializer(),
                    withGuild,
                ).toDomain()
            }.getOrNull() ?: return@mapNotNull null
            DiscordDomainEvent.ChannelCreated(channel)
        }
    }

    @Suppress("unused")
    private fun JsonElement.asPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
}

/**
 * Public, domain-typed wrapper around [DiscordRestClient]. Translates DTOs to `:shared:domain`
 * types so the wiring layer never touches `internal` DTOs.
 */
public class DiscordMessageBridge(private val rest: DiscordRestClient) {

    public suspend fun sendMessage(
        channelId: ChannelId,
        content: String,
        nonce: String,
        replyTo: MessageId?,
    ): Result<ChatMessage> = rest.sendMessage(channelId, content, nonce, replyTo).map { it.toDomain() }

    public suspend fun editMessage(
        channelId: ChannelId,
        messageId: MessageId,
        newContent: String,
    ): Result<ChatMessage> = rest.editMessage(channelId, messageId, newContent).map { it.toDomain() }

    public suspend fun deleteMessage(channelId: ChannelId, messageId: MessageId): Result<Unit> =
        rest.deleteMessage(channelId, messageId)

    public suspend fun loadOlder(
        channelId: ChannelId,
        beforeId: MessageId,
        limit: Int,
    ): Result<List<ChatMessage>> =
        rest.getMessages(channelId, limit = limit, before = beforeId).map { list -> list.map { it.toDomain() } }
}

/**
 * Public, domain-typed wrapper exposing the REST calls the session lifecycle needs:
 * token validation (REST GET /users/@me) returning a [UserSummary].
 */
public class DiscordSessionBridge(private val rest: DiscordRestClient) {

    public suspend fun fetchSelfUser(): Result<UserSummary> = rest.getSelfUser().map { it.toDomain() }
}
