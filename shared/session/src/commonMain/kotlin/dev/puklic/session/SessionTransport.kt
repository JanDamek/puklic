package dev.puklic.session

import dev.puklic.domain.UserSummary
import kotlinx.coroutines.flow.SharedFlow

/**
 * Outcome of a token-validation REST call.
 */
public sealed interface TokenValidation {
    public data class Ok(val selfUser: UserSummary) : TokenValidation
    public data object Unauthorized : TokenValidation
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
}
