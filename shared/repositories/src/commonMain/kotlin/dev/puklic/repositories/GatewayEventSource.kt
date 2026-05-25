package dev.puklic.repositories

import dev.puklic.domain.Channel
import dev.puklic.domain.ChatMessage
import dev.puklic.domain.EmojiRef
import dev.puklic.domain.Guild
import dev.puklic.domain.Member
import dev.puklic.domain.Role
import dev.puklic.domain.UserSummary
import dev.puklic.domain.VoiceMember
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.ids.RoleId
import dev.puklic.ids.UserId
import kotlinx.coroutines.flow.SharedFlow

/**
 * User presence as surfaced by PRESENCE_UPDATE gateway events. Ephemeral — never persisted
 * to SQLite per ADR-0003 (cache strategy).
 */
public enum class PresenceState { ONLINE, IDLE, DO_NOT_DISTURB, OFFLINE, INVISIBLE }

/**
 * Domain-level gateway events consumed by the orchestrators in this module.
 *
 * The raw `GatewayDispatchEvent`s produced by `:shared:protocol-discord` use `internal` DTOs;
 * the wiring layer (session/app composition) translates those into the events below so this
 * module never touches protocol DTOs directly.
 */
public sealed interface GatewayDomainEvent {
    public data class MessageCreated(val message: ChatMessage) : GatewayDomainEvent
    public data class MessageUpdated(val message: ChatMessage) : GatewayDomainEvent
    public data class MessageDeleted(val channelId: ChannelId, val messageId: MessageId) : GatewayDomainEvent
    public data class MessageDeletedBulk(
        val channelId: ChannelId,
        val messageIds: List<MessageId>,
    ) : GatewayDomainEvent
    public data class PresenceUpdated(val userId: UserId, val state: PresenceState) : GatewayDomainEvent
    public data class TypingStarted(
        val channelId: ChannelId,
        val userId: UserId,
        val timestampEpochSeconds: Long,
    ) : GatewayDomainEvent
    public data class GuildCreated(val guild: Guild) : GatewayDomainEvent
    public data class GuildUpdated(val guild: Guild) : GatewayDomainEvent
    public data class GuildDeleted(val guildId: GuildId) : GatewayDomainEvent
    public data class ChannelCreated(val channel: Channel) : GatewayDomainEvent
    public data class ChannelUpdated(val channel: Channel) : GatewayDomainEvent
    public data class ChannelDeleted(val channelId: ChannelId) : GatewayDomainEvent
    public data class UserUpdated(val user: UserSummary) : GatewayDomainEvent
    public data class Ready(
        val selfUser: UserSummary,
        val sessionId: String,
        val users: List<UserSummary> = emptyList(),
    ) : GatewayDomainEvent

    public data class ReactionAdded(
        val channelId: ChannelId,
        val messageId: MessageId,
        val userId: UserId,
        val emoji: EmojiRef,
    ) : GatewayDomainEvent

    public data class ReactionRemoved(
        val channelId: ChannelId,
        val messageId: MessageId,
        val userId: UserId,
        val emoji: EmojiRef,
    ) : GatewayDomainEvent

    public data class ReactionsClearedAll(
        val channelId: ChannelId,
        val messageId: MessageId,
    ) : GatewayDomainEvent

    public data class ReactionsClearedEmoji(
        val channelId: ChannelId,
        val messageId: MessageId,
        val emoji: EmojiRef,
    ) : GatewayDomainEvent

    /**
     * Voice trigger flow step 2a — main-gateway VOICE_STATE_UPDATE dispatch. Mirrored from
     * `DiscordDomainEvent.VoiceStateUpdated` by the wiring adapter. See architect report
     * 2026-05-23-voice §5.
     */
    public data class VoiceStateUpdated(
        val guildId: GuildId?,
        val channelId: ChannelId?,
        val userId: UserId,
        val sessionId: String,
        val selfMute: Boolean,
        val selfDeaf: Boolean,
        val mute: Boolean,
        val deaf: Boolean,
    ) : GatewayDomainEvent

    /**
     * Voice trigger flow step 2b — main-gateway VOICE_SERVER_UPDATE dispatch. `endpoint` may be
     * null during region migration.
     */
    public data class VoiceServerUpdated(
        val guildId: GuildId?,
        val token: String,
        val endpoint: String?,
    ) : GatewayDomainEvent

    /**
     * Initial set of voice-channel occupants carried inside a `GUILD_CREATE` dispatch's
     * `voice_states` array. Consumed by `VoiceStateRepository` to seed its in-memory map before
     * any incremental [VoiceStateUpdated] events arrive.
     */
    public data class VoiceStatesBootstrap(
        val guildId: GuildId,
        val states: List<VoiceMember>,
    ) : GatewayDomainEvent

    // Issue #18 — role + self-member events for the channel visibility filter. See
    // architect-report 2026-05-24-channel-permission-design.md §10.
    public data class GuildRolesSnapshot(val guildId: GuildId, val roles: List<Role>) : GatewayDomainEvent
    public data class RoleCreated(val role: Role) : GatewayDomainEvent
    public data class RoleUpdated(val role: Role) : GatewayDomainEvent
    public data class RoleDeleted(val guildId: GuildId, val roleId: RoleId) : GatewayDomainEvent
    public data class SelfMemberUpdated(val member: Member) : GatewayDomainEvent
}

/** Source of domain-level gateway events. Wraps the protocol-discord SharedFlow. */
public interface GatewayEventSource {
    public val events: SharedFlow<GatewayDomainEvent>
}
