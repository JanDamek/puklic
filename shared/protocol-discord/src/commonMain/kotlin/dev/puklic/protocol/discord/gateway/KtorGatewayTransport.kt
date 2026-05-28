package dev.puklic.protocol.discord.gateway

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
 * Ktor-backed [GatewayTransport]. Opens a WebSocket session to [url] and surfaces frames as
 * [GatewayFrameIn]. The Discord gateway speaks text JSON; binary frames are pass-through for
 * future zlib-stream support.
 */
private class KtorGatewayTransport(private val session: DefaultClientWebSocketSession) : GatewayTransport {
    override val incoming: Flow<GatewayFrameIn> = channelFlow {
        try {
            session.incoming.consumeEach { frame ->
                val mapped: GatewayFrameIn = when (frame) {
                    is Frame.Text -> GatewayFrameIn.Text(frame.readText())
                    is Frame.Binary -> GatewayFrameIn.Binary(frame.data)
                    is Frame.Close -> {
                        val reason = frame.readReason()
                        GatewayFrameIn.Close(
                            code = reason?.code?.toInt() ?: GatewayTransport.NORMAL_CLOSURE,
                            reason = reason?.message.orEmpty(),
                        )
                    }
                    else -> return@consumeEach
                }
                send(mapped)
            }
        } catch (cause: kotlinx.coroutines.CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            send(GatewayFrameIn.Close(code = ABNORMAL_CLOSURE, reason = cause.message.orEmpty()))
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

/** Build a [GatewayTransportFactory] backed by [httpClient]. */
public fun ktorGatewayTransportFactory(httpClient: HttpClient): GatewayTransportFactory = { url ->
    val session = httpClient.webSocketSession(urlString = url)
    KtorGatewayTransport(session) as GatewayTransport
}
