package dev.puklic.voice.gateway

import dev.puklic.voice.codec.PuklicVoiceCodec

import kotlinx.coroutines.flow.Flow

/**
 * A frame received from the voice gateway socket. Mirrors the structure used by the main
 * Discord gateway transport (`shared/protocol-discord` `GatewayFrameIn`).
 */
@PuklicVoiceCodec
public sealed interface VoiceFrameIn {
    data class Text(val text: String) : VoiceFrameIn
    data class Binary(val bytes: ByteArray) : VoiceFrameIn
    data class Close(val code: Int, val reason: String) : VoiceFrameIn
}

/**
 * Abstraction over the voice gateway websocket so tests can drive a fake transport without
 * any real network. Same pattern as `GatewayTransport` in `:shared:protocol-discord`.
 */
@PuklicVoiceCodec
public interface VoiceGatewayTransport {
    val incoming: Flow<VoiceFrameIn>
    suspend fun sendText(text: String)
    suspend fun sendBinary(bytes: ByteArray)
    suspend fun close(code: Int = NORMAL_CLOSURE, reason: String = "")

    companion object {
        const val NORMAL_CLOSURE: Int = 1000
    }
}

@PuklicVoiceCodec
public typealias VoiceGatewayTransportFactory = suspend (url: String) -> VoiceGatewayTransport
