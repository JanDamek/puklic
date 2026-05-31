package dev.puklic.voice.transport

import dev.puklic.voice.codec.PuklicVoiceCodec
import dev.puklic.voice.codec.transport.VoiceUdpTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Run the 74-byte Discord voice IP discovery handshake against the connected voice server.
 *
 * Sends an [IpDiscovery.buildRequest] datagram and awaits the first incoming UDP packet,
 * parsing it as an [IpDiscovery.Result]. The FP-3 [VoiceUdpTransport] contract delivers
 * datagrams via a cold [VoiceUdpTransport.incoming] flow; `first()` collects exactly one
 * packet and cancels the collector, mirroring the legacy pull-based [`receive()`] semantics.
 *
 * Moved here from the deleted `:shared:voice/commonMain/UdpRtpTransport.kt` 2026-05-31
 * (FP-14h-2b) per architect report
 * `2026-05-29-fp14h-1-v2-voice-gateway-redesign.md` §3.2.1.
 */
@PuklicVoiceCodec
public suspend fun VoiceUdpTransport.discoverIp(
    ssrc: Int,
    timeoutMs: Long = 5_000L,
): IpDiscovery.Result {
    send(IpDiscovery.buildRequest(ssrc))
    val response = withTimeout(timeoutMs) { incoming.first() }
    return IpDiscovery.parseResponse(response)
}
