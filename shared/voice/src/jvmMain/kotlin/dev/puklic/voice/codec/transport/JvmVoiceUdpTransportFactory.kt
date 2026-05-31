package dev.puklic.voice.codec.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * JVM implementation of the KMP [VoiceUdpTransportFactory] contract on top of
 * `java.net.DatagramSocket`.
 *
 * Per FP-3 architect report `docs/03_infrastructure/architect-reports/2026-05-29-fp3-voice-udp-transport.md`:
 *
 * - The `remote` endpoint is wired via the socket's [java.net.InetSocketAddress] when sending.
 * - The `localBind` parameter is accepted but ignored on JVM — `DatagramSocket` always binds
 *   to `0.0.0.0` on an ephemeral port. This matches current JVM voice/screenshare behaviour
 *   and is **not** a temporary stub. iOS Network.framework (FP-6) honours `localBind`.
 * - Bind happens lazily on first `send` or first `incoming` collect, guarded by a one-shot
 *   mutex. This defers a few microseconds of work but does not change semantics — no current
 *   JVM caller traffics packets before the first send/collect.
 *
 * Replaces the previous `BridgedUdpRtpVoiceTransport` adapter over the legacy
 * `UdpRtpTransport` interface, which was deleted in FP-14h-2b (issue #66). The
 * `DatagramSocket` implementation is now owned in-line here.
 */
public object JvmVoiceUdpTransportFactory : VoiceUdpTransportFactory {
    override fun create(remote: Endpoint, localBind: Endpoint?): VoiceUdpTransport =
        JvmVoiceUdpTransport(remote = remote)
}

private const val RECEIVE_BUFFER_BYTES: Int = 2048

private class JvmVoiceUdpTransport(
    private val remote: Endpoint,
) : VoiceUdpTransport {

    private val initMutex: Mutex = Mutex()
    private var socket: DatagramSocket? = null
    private var remoteAddress: InetSocketAddress? = null

    override suspend fun send(packet: ByteArray) {
        val (s, r) = ensureInitialised()
        withContext(Dispatchers.IO) {
            s.send(DatagramPacket(packet, packet.size, r))
        }
    }

    override val incoming: Flow<ByteArray> = flow {
        val (s, _) = ensureInitialised()
        val buf = ByteArray(RECEIVE_BUFFER_BYTES)
        while (true) {
            val dp = DatagramPacket(buf, buf.size)
            s.receive(dp)
            emit(buf.copyOf(dp.length))
        }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        socket?.close()
        socket = null
        remoteAddress = null
    }

    private suspend fun ensureInitialised(): Pair<DatagramSocket, InetSocketAddress> {
        socket?.let { s -> remoteAddress?.let { r -> return s to r } }
        return initMutex.withLock {
            socket?.let { s -> remoteAddress?.let { r -> return@withLock s to r } }
            val s = withContext(Dispatchers.IO) {
                DatagramSocket(0, InetAddress.getByName("0.0.0.0"))
            }
            val r = withContext(Dispatchers.IO) {
                InetSocketAddress(InetAddress.getByName(remote.host), remote.port)
            }
            socket = s
            remoteAddress = r
            s to r
        }
    }
}
