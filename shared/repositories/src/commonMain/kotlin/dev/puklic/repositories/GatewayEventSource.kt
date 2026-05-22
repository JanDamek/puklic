package dev.puklic.repositories

import dev.puklic.domain.ChatMessage
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
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
    public data class PresenceUpdated(val userId: UserId, val state: PresenceState) : GatewayDomainEvent
    public data class TypingStarted(
        val channelId: ChannelId,
        val userId: UserId,
        val timestampEpochSeconds: Long,
    ) : GatewayDomainEvent
}

/** Source of domain-level gateway events. Wraps the protocol-discord SharedFlow. */
public interface GatewayEventSource {
    public val events: SharedFlow<GatewayDomainEvent>
}
