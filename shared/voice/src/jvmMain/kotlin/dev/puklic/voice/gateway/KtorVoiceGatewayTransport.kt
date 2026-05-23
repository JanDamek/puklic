package dev.puklic.voice.gateway

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * Ktor-backed actual for [VoiceGatewayTransport]. Mirrors the main-gateway transport
 * implementation in `:desktop:app` — same engine, same patterns.
 *
 * Voice gateway only speaks text JSON; binary frames are ignored.
 */
internal class KtorVoiceGatewayTransport(
    private val session: DefaultClientWebSocketSession,
) : VoiceGatewayTransport {

    override val incoming: Flow<VoiceFrameIn> = channelFlow {
        try {
            session.incoming.consumeEach { frame ->
                val mapped: VoiceFrameIn? = when (frame) {
                    is Frame.Text -> VoiceFrameIn.Text(frame.readText())
                    is Frame.Close -> {
                        val reason = frame.readReason()
                        VoiceFrameIn.Close(
                            code = reason?.code?.toInt() ?: VoiceGatewayTransport.NORMAL_CLOSURE,
                            reason = reason?.message.orEmpty(),
                        )
                    }
                    else -> null
                }
                if (mapped != null) send(mapped)
            }
        } catch (cause: kotlinx.coroutines.CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            send(VoiceFrameIn.Close(code = ABNORMAL_CLOSURE, reason = cause.message.orEmpty()))
        }
    }

    override suspend fun sendText(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun close(code: Int, reason: String) {
        session.close(CloseReason(code.toShort(), reason))
    }

    private companion object {
        const val ABNORMAL_CLOSURE = 1006
    }
}

/**
 * Public entry point for the wiring layer (`:desktop:app`) — returns an opaque token that
 * [dev.puklic.voice.DefaultVoiceClient] knows how to use internally.
 */
public fun ktorVoiceGatewayTransportFactory(httpClient: HttpClient): VoiceWsTransportFactory {
    val inner: VoiceGatewayTransportFactory = { url ->
        val session = httpClient.webSocketSession(urlString = url)
        KtorVoiceGatewayTransport(session)
    }
    return VoiceWsTransportFactory(inner)
}

/**
 * Opaque wrapper around the internal [VoiceGatewayTransportFactory] so the wiring layer
 * can pass it into [dev.puklic.voice.DefaultVoiceClient] without seeing the internal types.
 */
public class VoiceWsTransportFactory internal constructor(
    internal val delegate: VoiceGatewayTransportFactory,
)
