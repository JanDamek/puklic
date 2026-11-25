package dev.puklic.voice.client

import dev.puklic.ids.UserId
import dev.puklic.voice.AudioDevice
import dev.puklic.voice.MainGatewayBridge
import dev.puklic.voice.audio.AudioCapture
import dev.puklic.voice.audio.AudioPlayback
import dev.puklic.voice.audio.audioCapture
import dev.puklic.voice.audio.audioPlayback
import dev.puklic.voice.audio.listAudioDevices
import dev.puklic.voice.codec.PuklicVoiceCodec
import dev.puklic.voice.codec.transport.VoiceUdpTransportFactory
import dev.puklic.voice.gateway.VoiceWsTransportFactory

/**
 * The platform-specific building blocks [SharedVoiceClient] runs on: the UDP socket, the voice
 * WebSocket transport and the audio endpoints. Audio defaults to the `expect` factories of this
 * module, so a platform only has to supply its UDP and WebSocket transports.
 */
@PuklicVoiceCodec
public class VoicePlatform(
    public val udpTransportFactory: VoiceUdpTransportFactory,
    public val wsTransportFactory: VoiceWsTransportFactory,
    public val audio: AudioEndpoints = AudioEndpoints(),
)

/** Factories for microphone capture, speaker playback and device enumeration. */
@PuklicVoiceCodec
public class AudioEndpoints(
    public val captureFactory: () -> AudioCapture = ::audioCapture,
    public val playbackFactory: () -> AudioPlayback = ::audioPlayback,
    public val deviceLister: (AudioDevice.Direction) -> List<AudioDevice> = ::listAudioDevices,
)

/** How the client talks to the main Discord gateway and learns who the local user is. */
public class VoiceSignalling(
    public val mainGateway: MainGatewayBridge,
    public val selfUserIdProvider: () -> UserId?,
)
