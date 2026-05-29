package dev.puklic.voice.codec.transport

import dev.puklic.voice.transport.UdpRtpTransport
import dev.puklic.voice.transport.newUdpRtpTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * JVM bridge: implements the KMP [VoiceUdpTransportFactory] contract on top of the existing
 * internal [UdpRtpTransport] (`java.net.DatagramSocket` actual).
 *
 * Per FP-3 architect report `docs/03_infrastructure/architect-reports/2026-05-29-fp3-voice-udp-transport.md`:
 *
 * - The `remote` endpoint is wired via the existing transport's `connect(host, port)` call.
 * - The `localBind` parameter is accepted but ignored on JVM — `DatagramSocket` always binds
 *   to `0.0.0.0` on an ephemeral port. This matches current JVM voice/screenshare behaviour
 *   and is **not** a temporary stub. iOS Network.framework (FP-6) honours `localBind`.
 * - Bind/connect happen lazily on first `send` or first `incoming` collect, guarded by a
 *   one-shot mutex. This defers a few microseconds of work but does not change semantics —
 *   no current JVM caller traffics packets before bind/connect.
 *
 * Pure adapter — does NOT replace the existing `UdpRtpTransport` interface or any of its
 * callers (`DefaultVoiceClient`, `DefaultScreenShareClient`, `PlaybackPipeline`,
 * `VoicePacketDispatcher`, `SoundshareAudioRtpSender`, `VideoRtpSender`). Caller migration
 * to the public surface lands when iOS `actual` (FP-6) is in place and
 * `DependencyGraph` can pick a factory per platform.
 */
public object JvmVoiceUdpTransportFactory : VoiceUdpTransportFactory {
    override fun create(remote: Endpoint, localBind: Endpoint?): VoiceUdpTransport =
        BridgedUdpRtpVoiceTransport(remote = remote)
}

private class BridgedUdpRtpVoiceTransport(
    private val remote: Endpoint,
) : VoiceUdpTransport {

    private val delegate: UdpRtpTransport = newUdpRtpTransport()
    private val initMutex: Mutex = Mutex()
    private var initialised: Boolean = false

    override suspend fun send(packet: ByteArray) {
        ensureInitialised()
        delegate.send(packet)
    }

    override val incoming: Flow<ByteArray> = flow {
        ensureInitialised()
        while (true) {
            emit(delegate.receive())
        }
    }

    override fun close() {
        delegate.close()
    }

    private suspend fun ensureInitialised() {
        if (initialised) return
        initMutex.withLock {
            if (initialised) return
            delegate.bind()
            delegate.connect(remote.host, remote.port)
            initialised = true
        }
    }
}
