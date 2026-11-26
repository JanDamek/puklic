package dev.puklic.voice.client

import dev.puklic.voice.codec.OpusEncoder
import dev.puklic.voice.codec.PuklicVoiceCodec
import dev.puklic.voice.codec.transport.Endpoint
import dev.puklic.voice.codec.transport.VoiceUdpTransport
import dev.puklic.voice.crypto.xchacha20Poly1305
import dev.puklic.voice.pipeline.CapturePipeline
import dev.puklic.voice.pipeline.PlaybackPipeline
import dev.puklic.voice.transport.IpDiscovery
import dev.puklic.voice.transport.VoicePacketCodec
import dev.puklic.voice.transport.VoicePacketDispatcher
import dev.puklic.voice.transport.discoverIp
import kotlinx.coroutines.CoroutineScope

/** Events the media path reports back to the owning client. */
@PuklicVoiceCodec
internal class MediaCallbacks(
    val onSpeakingChange: suspend (Boolean) -> Unit,
    val onSpeakers: suspend (Set<Int>) -> Unit,
)

/** Audio devices chosen by the user; `null` means the system default. */
internal data class DeviceSelection(val captureId: String?, val playbackId: String?)

private class PlaybackWiring(
    val transport: VoiceUdpTransport,
    val codec: VoicePacketCodec,
    val packets: VoicePacketDispatcher,
)

/**
 * The UDP half of one voice session: IP discovery, then capture and playback pipelines keyed by the
 * session key. Owns every media resource and releases them together in [close].
 */
@PuklicVoiceCodec
internal class VoiceMediaSession(
    private val platform: VoicePlatform,
    private val scope: CoroutineScope,
    private val callbacks: MediaCallbacks,
) {
    private var udp: VoiceUdpTransport? = null
    private var dispatcher: VoicePacketDispatcher? = null
    private var capture: CapturePipeline? = null
    private var playback: PlaybackPipeline? = null
    private var speakerOutput: DeafenableAudioPlayback? = null
    private var encoder: OpusEncoder? = null

    val hasTransport: Boolean get() = udp != null

    suspend fun discover(endpoint: Endpoint, ssrc: Int): IpDiscovery.Result {
        val transport = platform.udpTransportFactory.create(endpoint, localBind = null)
        udp = transport
        return transport.discoverIp(ssrc)
    }

    fun start(ssrc: Int, secretKey: ByteArray, devices: DeviceSelection) {
        val transport = checkNotNull(udp) { "UDP transport not opened" }
        val codec = VoicePacketCodec(aead = xchacha20Poly1305(secretKey), ssrc = ssrc)
        val packets = VoicePacketDispatcher(transport).also { it.start(scope) }
        dispatcher = packets
        startPlayback(PlaybackWiring(transport, codec, packets), devices.playbackId)
        startCapture(transport, codec, devices.captureId)
    }

    private fun startPlayback(wiring: PlaybackWiring, deviceId: String?) {
        val output = DeafenableAudioPlayback(platform.audio.playbackFactory())
        speakerOutput = output
        val pipeline = PlaybackPipeline(
            transport = wiring.transport,
            packetCodec = wiring.codec,
            decoderFactory = platform.opus.decoderFactory,
            playback = output,
            onSpeakers = callbacks.onSpeakers,
            packetSource = { wiring.packets.audioPackets() },
        )
        playback = pipeline
        pipeline.start(scope, deviceId)
    }

    private fun startCapture(transport: VoiceUdpTransport, codec: VoicePacketCodec, deviceId: String?) {
        val opus = platform.opus.encoderFactory().also { encoder = it }
        val pipeline = CapturePipeline(
            capture = platform.audio.captureFactory(),
            encoder = opus,
            encodeAndSend = { opus -> transport.send(codec.encode(opus)) },
            onSpeakingChange = callbacks.onSpeakingChange,
        )
        capture = pipeline
        pipeline.start(scope, deviceId)
    }

    fun applyAudioState(muted: Boolean, deafened: Boolean) {
        capture?.setMuted(muted)
        speakerOutput?.deafened = deafened
    }

    fun close() {
        runCatching { capture?.stop() }
        runCatching { playback?.stop() }
        runCatching { dispatcher?.stop() }
        runCatching { encoder?.close() }
        runCatching { udp?.close() }
        capture = null
        playback = null
        dispatcher = null
        speakerOutput = null
        encoder = null
        udp = null
    }
}
