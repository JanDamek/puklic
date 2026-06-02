package dev.puklic.voice.screenshare

import dev.puklic.screencast.ScreenSourceEnumerator
import dev.puklic.voice.codec.transport.VoiceUdpTransport
import dev.puklic.voice.crypto.AeadCipher
import dev.puklic.voice.crypto.NonceGenerator
import dev.puklic.voice.gateway.VoiceGatewayConnection
import dev.puklic.voice.screenshare.encoder.VideoCodec
import kotlinx.coroutines.CoroutineScope

/**
 * JVM concrete factory for [ScreenShareClientFactory] — builds a [DefaultScreenShareClient].
 *
 * Constructed by `:desktop:app` `DependencyGraph` (the GPL desktop build). App Store
 * wiring layers pass null instead, which keeps voice working with a [NoOpScreenShareClient].
 *
 * Per architect plan `docs/03_infrastructure/architect-reports/2026-06-02-fp14h-6-blocked.md`.
 */
public class JvmScreenShareClientFactory : ScreenShareClientFactory {

    @Suppress("LongParameterList")
    override fun create(
        voiceGateway: VoiceGatewayConnection,
        packetEncryptor: AeadCipher,
        nonceGen: NonceGenerator,
        udpTransport: VoiceUdpTransport,
        getAudioSsrc: () -> Int,
        getVideoSsrc: () -> Int,
        enumerator: ScreenSourceEnumerator,
        scope: CoroutineScope,
        videoCodec: VideoCodec,
    ): ScreenShareClient = DefaultScreenShareClient(
        voiceGateway = voiceGateway,
        packetEncryptor = packetEncryptor,
        nonceGen = nonceGen,
        udpTransport = udpTransport,
        getAudioSsrc = getAudioSsrc,
        getVideoSsrc = getVideoSsrc,
        enumerator = enumerator,
        scope = scope,
        videoCodec = videoCodec,
    )
}
