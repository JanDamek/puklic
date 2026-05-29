package dev.puklic.voice.codec.transport

import kotlinx.coroutines.flow.Flow

/**
 * KMP-clean UDP transport contract for Discord voice / screenshare RTP traffic.
 *
 * The transport is connection-oriented from the caller's perspective: it is created already
 * pointing at a remote endpoint (see [VoiceUdpTransportFactory.create]). Outgoing packets go
 * through [send]; incoming datagrams arrive on [incoming] as raw byte arrays (RTP framing is
 * the caller's concern — see `VoicePacketCodec`).
 *
 * Platform implementations:
 *
 * - **JVM desktop** — adapter over the existing `dev.puklic.voice.transport.UdpRtpTransport`
 *   backed by `java.net.DatagramSocket`, see `JvmVoiceUdpTransportFactory`.
 * - **iOS / macOS App Store** (FP-6) — `NWConnection` from `Network.framework`.
 *
 * Lifecycle: implementations own a single underlying socket. Call [close] to release it.
 *
 * Threading: [send] is safe to call from any coroutine context (implementations dispatch to
 * an appropriate IO context). [incoming] is a cold flow; collecting starts the receive loop.
 * Multi-collector semantics are NOT defined — the canonical usage is single-collector.
 */
public interface VoiceUdpTransport : AutoCloseable {
    /**
     * Send a single UDP datagram to the remote endpoint. Suspends until the underlying
     * socket has accepted the packet for transmission.
     */
    public suspend fun send(packet: ByteArray)

    /**
     * Cold flow of received UDP datagrams from the remote endpoint. Each emission is one
     * complete UDP packet payload (no truncation, no aggregation).
     */
    public val incoming: Flow<ByteArray>

    override fun close()
}

/**
 * Constructs [VoiceUdpTransport] instances. Lives in commonMain so callers can be wired
 * against the contract; the concrete factory comes from the platform module
 * (`JvmVoiceUdpTransportFactory` for JVM, an `IosVoiceUdpTransportFactory` for FP-6).
 */
public interface VoiceUdpTransportFactory {
    /**
     * Create a transport pointed at [remote].
     *
     * @param remote the Discord voice / screenshare UDP endpoint
     * @param localBind optional specific local interface to bind to. When `null`, the
     *   implementation binds to any available interface on an ephemeral port. JVM impl
     *   currently ignores this parameter and always binds `0.0.0.0:0`; iOS impl uses it to
     *   pin to a specific `NWInterface` on multi-interface devices.
     */
    public fun create(remote: Endpoint, localBind: Endpoint?): VoiceUdpTransport
}
