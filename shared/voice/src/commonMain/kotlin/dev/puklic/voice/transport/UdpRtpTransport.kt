package dev.puklic.voice.transport

import dev.puklic.voice.codec.PuklicVoiceCodec
import kotlinx.coroutines.withTimeout

/**
 * UDP RTP transport abstraction. JVM `actual` uses `java.net.DatagramSocket`.
 *
 * Single-threaded blocking I/O per direction is fine for voice — 50 packets/sec.
 *
 * Legacy type kept in `:shared:voice/commonMain` for FP-14h-2a. To be deleted in FP-14h-2b
 * once `DefaultVoiceClient`, `DefaultScreenShareClient`, `VoicePacketDispatcher`,
 * `VideoRtpSender`, `SoundshareAudioRtpSender`, and `PlaybackPipeline` are rewired to
 * `VoiceUdpTransport` (FP-3, in `:shared:voice-codec`).
 *
 * Visibility promoted from `internal` to `public @PuklicVoiceCodec` 2026-05-29 (FP-14h-2a)
 * because the `IpDiscovery` object it composes with now lives in a different module
 * (`:shared:voice-codec`).
 */
@PuklicVoiceCodec
public interface UdpRtpTransport {
    public suspend fun bind(): Int
    public suspend fun connect(host: String, port: Int)
    public suspend fun send(packet: ByteArray)
    public suspend fun receive(): ByteArray
    public fun close()
}

@PuklicVoiceCodec
public expect fun newUdpRtpTransport(): UdpRtpTransport

/**
 * Run the 74-byte IP discovery handshake against the connected voice server.
 * Sends a request and awaits the response, returning the discovered external (address, port).
 */
@PuklicVoiceCodec
public suspend fun UdpRtpTransport.discoverIp(ssrc: Int, timeoutMs: Long = 5_000): IpDiscovery.Result {
    send(IpDiscovery.buildRequest(ssrc))
    val response = withTimeout(timeoutMs) { receive() }
    return IpDiscovery.parseResponse(response)
}
