package dev.puklic.protocol.discord.gateway

import kotlinx.coroutines.flow.Flow

/** A single frame received from the gateway socket — either text JSON or binary (zlib-compressed). */
public sealed interface GatewayFrameIn {
    public data class Text(val text: String) : GatewayFrameIn
    public data class Binary(val bytes: ByteArray) : GatewayFrameIn {
        override fun equals(other: Any?): Boolean = other is Binary && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }
    public data class Close(val code: Int, val reason: String) : GatewayFrameIn
}

/** Abstraction over the websocket session so tests can drive a fake one. */
public interface GatewayTransport {
    public val incoming: Flow<GatewayFrameIn>
    public suspend fun sendText(text: String)
    public suspend fun close(code: Int = NORMAL_CLOSURE, reason: String = "")

    public companion object {
        public const val NORMAL_CLOSURE: Int = 1000
    }
}

public typealias GatewayTransportFactory = suspend (url: String) -> GatewayTransport
