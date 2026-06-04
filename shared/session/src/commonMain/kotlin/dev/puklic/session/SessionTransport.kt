package dev.puklic.session

import dev.puklic.domain.UserSummary
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import kotlinx.coroutines.flow.SharedFlow

/**
 * Outcome of a token-validation REST call.
 */
public sealed interface TokenValidation {
    public data class Ok(val selfUser: UserSummary) : TokenValidation
    public data class Unauthorized(val detail: String = "") : TokenValidation
    public data class TransportError(val message: String) : TokenValidation
}

/**
 * Outcome of a gateway connect attempt.
 */
public sealed interface GatewayLifecycleEvent {
    public data class Connected(val sessionId: String) : GatewayLifecycleEvent
    public data object Disconnected : GatewayLifecycleEvent
    public data object TokenInvalid : GatewayLifecycleEvent
    public data class Failed(val reason: String) : GatewayLifecycleEvent
    public data class Reconnecting(val secondsUntilRetry: Int) : GatewayLifecycleEvent
}

/**
 * Transport abstraction over `:shared:protocol-discord`. The protocol module's `DiscordRestClient`
 * and `GatewayConnection` use `internal` DTOs; the wiring layer (Step 15-16) implements this
 * interface against those types. Tests use in-memory fakes.
 */
public interface SessionTransport {
    /** Validate [token] via REST GET /users/@me. */
    public suspend fun validateToken(token: String): TokenValidation

    /** Start the gateway. Lifecycle events are emitted on [lifecycle]. */
    public suspend fun connectGateway(token: String)

    /** Stop the gateway. */
    public suspend fun disconnectGateway()

    /** Gateway lifecycle stream. */
    public val lifecycle: SharedFlow<GatewayLifecycleEvent>

    /**
     * Send OP 14 `lazy_guild_subscribe`. Used to unlock REST access (50001) for member-list-gated
     * channels and to subscribe to GUILD_MEMBER_LIST_UPDATE events.
     *
     * Default no-op so test/fake implementations don't need to override.
     */
    public suspend fun lazyRequestGuild(guildId: GuildId, channelIds: List<ChannelId>) { Unit }

    /**
     * Mark [messageId] as the last-read message in [channelId] (Discord MESSAGE_ACK, issue #91).
     * Best-effort; failures are swallowed by the implementation. Default no-op so test/fake
     * implementations don't need to override.
     */
    public suspend fun markChannelRead(channelId: ChannelId, messageId: MessageId) { Unit }
}
