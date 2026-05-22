package dev.puklic.protocol.discord

import co.touchlab.kermit.Logger
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
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

/**
 * Discord's READY event for user accounts can synthesize 200+ events in a single emission burst
 * (one Ready + N GuildCreated + M ChannelCreated across all guilds + DMs). The previous buffer
 * of 64 caused `tryEmit` to silently drop the tail — root cause of the live bug where
 * ChannelOrchestrator stopped persisting after ~50 channels. Size the buffer well above any
 * realistic burst, and use suspending `emit` to honor back-pressure end-to-end.
 */
private const val EVENT_BUFFER = 1024
private const val BRIDGE_TAG = "DiscordGatewayBridge"

/**
 * Allow-listed top-level READY payload keys safe to log by NAME. Never logs values.
 * The `token` field is deliberately excluded.
 */
private val SAFE_READY_KEYS: Set<String> = setOf(
    "v", "session_id", "session_type", "resume_gateway_url", "shard",
    "guilds", "private_channels", "relationships", "presences",
    "user_settings", "user_guild_settings", "read_state",
    "application", "geo_ordered_rtc_regions", "country_code",
)

/** Allow-listed guild-payload keys safe to log by NAME. */
private val SAFE_GUILD_KEYS: Set<String> = setOf(
    "id", "name", "unavailable", "owner_id", "icon", "features",
    "member_count", "channels", "threads", "roles", "joined_at",
    // User-mode (web/desktop client) gateway nests metadata under `properties`. We log only the
    // KEY NAME, never its contents, so token-safety is preserved.
    "properties",
)

/**
 * Bridges a [GatewayConnection]'s raw dispatch stream to a domain-typed [SharedFlow]. Unknown
 * event types are dropped silently — the wiring layer is free to log them via [onUnknown].
 */
public class DiscordGatewayBridge(
    gateway: GatewayConnection,
    scope: CoroutineScope,
    onUnknown: (type: String) -> Unit = {},
) {
    private val _events = MutableSharedFlow<DiscordDomainEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    public val events: SharedFlow<DiscordDomainEvent> = _events.asSharedFlow()

    init {
        gateway.events
            .onEach { dispatch ->
                Logger.i(BRIDGE_TAG) { "bridge: received raw event type=${dispatch.type}" }
                val mapped = mapDispatch(dispatch, onUnknown)
                Logger.i(BRIDGE_TAG) {
                    "bridge: ${dispatch.type} mapped -> emitting ${mapped.size} events: " +
                        mapped.joinToString(",") { it::class.simpleName ?: "?" }
                }
                // Use suspending emit (not tryEmit) so a slow subscriber back-pressures the
                // bridge instead of silently dropping events. The buffer is sized large enough
                // that READY bursts complete without suspending; this is the safety net.
                mapped.forEach { _events.emit(it) }
            }
            .catch { t ->
                Logger.w(BRIDGE_TAG) {
                    "bridge: flow error caught cause=${t::class.simpleName} msg=${t.message?.take(200)}"
                }
            }
            .launchIn(scope)
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
        }.onFailure { t ->
            // Malformed payload — drop silently so the gateway keeps flowing.
            Logger.i(BRIDGE_TAG) {
                "bridge: decode-failed type=${event.type} cause=${t::class.simpleName} msg=${t.message}"
            }
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
        // Token-safe diagnostic: only top-level KEY NAMES (never values) + sizes.
        val safeKeys = obj.keys.filter { it in SAFE_READY_KEYS }
        Logger.i(BRIDGE_TAG) {
            "bridge: READY top-level keys (allow-listed)=[${safeKeys.joinToString(",")}] " +
                "total_key_count=${obj.size}"
        }
        val guildsField = obj["guilds"] as? kotlinx.serialization.json.JsonArray
        Logger.i(BRIDGE_TAG) {
            "bridge: READY guilds array size=${guildsField?.size ?: "null/absent"}"
        }
        val sessionId = obj.getValue("session_id").jsonPrimitive.content
        val self = DiscordJson.decodeFromJsonElement(
            DiscordUserDto.serializer(),
            obj.getValue("user"),
        ).toDomain()
        val events = mutableListOf<DiscordDomainEvent>(DiscordDomainEvent.Ready(self, sessionId))
        val guildsArray = obj["guilds"]?.let { it as? kotlinx.serialization.json.JsonArray } ?: return events
        for ((index, guildElement) in guildsArray.withIndex()) {
            val dto = runCatching {
                DiscordJson.decodeFromJsonElement(
                    dev.puklic.protocol.discord.dto.DiscordGuildDto.serializer(),
                    guildElement,
                )
            }.onFailure { t ->
                Logger.i(BRIDGE_TAG) {
                    "bridge: READY guild[$index] decode-failed cause=${t::class.simpleName} msg=${t.message}"
                }
            }.getOrNull() ?: continue
            val guild = dto.toDomainOrNull()
            if (guild == null) {
                val keys = (guildElement as? kotlinx.serialization.json.JsonObject)?.keys
                    ?.filter { it in SAFE_GUILD_KEYS }
                    ?.joinToString(",")
                Logger.i(BRIDGE_TAG) {
                    "bridge: READY guild[$index] toDomainOrNull=null (likely unavailable stub) " +
                        "safe_keys=[$keys]"
                }
                continue
            }
            Logger.i(BRIDGE_TAG) {
                "bridge: READY guild[$index] decoded id=${guild.id.value} name=${guild.name}"
            }
            events += DiscordDomainEvent.GuildCreated(guild)
            events += extractGuildChannels(guildElement, guild.id.value)
        }
        // DM + Group-DM channels live under `private_channels`. They have no guild_id; the
        // DiscordChannelDto -> DmChannel mapper already handles this when type ∈ {1, 3}.
        //
        // User-mode READY puts only `recipient_ids` on each private_channel; the actual user
        // records live in a top-level `users` array. Decode that into a lookup map first,
        // then for each private_channel rewrite the JSON to inject the resolved `recipients`
        // before decoding the DTO (so the existing mapper continues to work uniformly).
        val usersById: Map<String, JsonElement> = (obj["users"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { el ->
                val userObj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val uid = userObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                uid to el
            }?.toMap().orEmpty()
        Logger.i(BRIDGE_TAG) { "bridge: READY top-level users.size=${usersById.size}" }

        val privateChannels = obj["private_channels"] as? kotlinx.serialization.json.JsonArray
        if (privateChannels != null) {
            Logger.i(BRIDGE_TAG) { "bridge: READY private_channels size=${privateChannels.size}" }
            for ((index, dmElement) in privateChannels.withIndex()) {
                val enriched = enrichPrivateChannelWithRecipients(dmElement, usersById)
                val dto = runCatching {
                    DiscordJson.decodeFromJsonElement(
                        dev.puklic.protocol.discord.dto.DiscordChannelDto.serializer(),
                        enriched,
                    )
                }.onFailure { t ->
                    Logger.i(BRIDGE_TAG) {
                        "bridge: READY private_channels[$index] decode-failed " +
                            "cause=${t::class.simpleName} msg=${t.message}"
                    }
                }.getOrNull() ?: continue
                Logger.i(BRIDGE_TAG) {
                    "bridge: READY private_channels[$index] decoded type=${dto.type} " +
                        "id=${dto.id} recipients=${dto.recipients.size} " +
                        "recipient_ids=${dto.recipientIds.size}"
                }
                val channel = dto.toDomain()
                if (channel == null) {
                    Logger.i(BRIDGE_TAG) {
                        "bridge: READY private_channels[$index] toDomain=null (unsupported type=${dto.type})"
                    }
                    continue
                }
                events += DiscordDomainEvent.ChannelCreated(channel)
            }
        }
        return events
    }

    /**
     * If a private-channel JSON object exposes `recipient_ids` but an empty `recipients` array
     * (user-mode READY shape), rewrite it to inject the resolved full user records from the
     * top-level `users` lookup. Channels that already carry `recipients` are returned as-is.
     */
    private fun enrichPrivateChannelWithRecipients(
        dmElement: JsonElement,
        usersById: Map<String, JsonElement>,
    ): JsonElement {
        val dmObj = dmElement as? kotlinx.serialization.json.JsonObject ?: return dmElement
        val existingRecipients = dmObj["recipients"] as? kotlinx.serialization.json.JsonArray
        if (existingRecipients != null && existingRecipients.isNotEmpty()) return dmElement
        val recipientIds = dmObj["recipient_ids"] as? kotlinx.serialization.json.JsonArray
            ?: return dmElement
        if (recipientIds.isEmpty()) return dmElement
        if (usersById.isEmpty()) return dmElement
        val resolved = kotlinx.serialization.json.buildJsonArray {
            for (idEl in recipientIds) {
                val uid = (idEl as? JsonPrimitive)?.content ?: continue
                val userEl = usersById[uid] ?: continue
                add(userEl)
            }
        }
        if (resolved.isEmpty()) return dmElement
        return kotlinx.serialization.json.buildJsonObject {
            dmObj.forEach { (k, v) -> put(k, v) }
            put("recipients", resolved)
        }
    }

    /**
     * Extracts the `channels` array embedded in a GUILD_CREATE / READY-guild payload and maps
     * each to a [DiscordDomainEvent.ChannelCreated]. Channels inside a guild payload do NOT have
     * a `guild_id` field set; we inject it from the parent guild before decoding.
     */
    private fun extractGuildChannels(guildPayload: JsonElement, guildId: Long): List<DiscordDomainEvent> {
        val channelsArray = (guildPayload as? kotlinx.serialization.json.JsonObject)
            ?.get("channels") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        val rawTypeCounts = mutableMapOf<Int, Int>()
        val mappedTypeCounts = mutableMapOf<String, Int>()
        var droppedCount = 0
        val result = channelsArray.mapNotNull { channelElement ->
            val channelObj = channelElement as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val withGuild = kotlinx.serialization.json.buildJsonObject {
                channelObj.forEach { (k, v) -> put(k, v) }
                put("guild_id", JsonPrimitive(guildId.toString()))
            }
            val dto = runCatching {
                DiscordJson.decodeFromJsonElement(
                    dev.puklic.protocol.discord.dto.DiscordChannelDto.serializer(),
                    withGuild,
                )
            }.getOrNull() ?: run {
                droppedCount += 1
                return@mapNotNull null
            }
            rawTypeCounts[dto.type] = (rawTypeCounts[dto.type] ?: 0) + 1
            val channel = dto.toDomain()
            if (channel == null) {
                droppedCount += 1
                return@mapNotNull null
            }
            val kind = channel::class.simpleName ?: "?"
            mappedTypeCounts[kind] = (mappedTypeCounts[kind] ?: 0) + 1
            DiscordDomainEvent.ChannelCreated(channel)
        }
        Logger.i(BRIDGE_TAG) {
            "bridge: extractGuildChannels guild=$guildId rawCount=${channelsArray.size} " +
                "rawTypes=$rawTypeCounts mapped=$mappedTypeCounts dropped=$droppedCount " +
                "emitted=${result.size}"
        }
        return result
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

    public suspend fun loadInitial(
        channelId: ChannelId,
        limit: Int,
    ): Result<List<ChatMessage>> =
        rest.getMessages(channelId, limit = limit, before = null).map { list -> list.map { it.toDomain() } }
}

/**
 * Public, domain-typed wrapper exposing the REST calls the session lifecycle needs:
 * token validation (REST GET /users/@me) returning a [UserSummary].
 */
public class DiscordSessionBridge(private val rest: DiscordRestClient) {

    public suspend fun fetchSelfUser(): Result<UserSummary> = rest.getSelfUser().map { it.toDomain() }
}
