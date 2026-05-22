package dev.puklic.protocol.discord.gateway

/**
 * Observable gateway state. Transitions per `docs/02_domain/discord-protocol.md` §Lifecycle.
 */
public sealed interface GatewayState {
    public data object Disconnected : GatewayState
    public data class Connecting(val attemptCount: Int) : GatewayState
    public data class HelloReceived(val heartbeatInterval: Long) : GatewayState
    public data class Identifying(val heartbeatInterval: Long) : GatewayState
    public data class Ready(
        val sessionId: String,
        val resumeGatewayUrl: String,
        val sequence: Int,
    ) : GatewayState
    public data class Resuming(val sessionId: String, val sequence: Int) : GatewayState
    public data object TokenInvalid : GatewayState
}

/** A single Gateway DISPATCH event surfaced to subscribers. */
public data class GatewayDispatchEvent(
    val type: String,
    val sequence: Int,
    val payload: kotlinx.serialization.json.JsonElement,
)
