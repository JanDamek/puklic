@file:OptIn(dev.puklic.voice.codec.PuklicVoiceCodec::class)

package dev.puklic.voice.screenshare

import dev.puklic.screencast.ScreenSourceEnumerator
import dev.puklic.voice.codec.transport.VoiceUdpTransport
import dev.puklic.voice.crypto.AeadCipher
import dev.puklic.voice.crypto.NonceGenerator
import dev.puklic.voice.gateway.VoiceGatewayConnection
import dev.puklic.voice.screenshare.encoder.VideoCodec
import kotlinx.coroutines.CoroutineScope

/**
 * SPI for building a real [ScreenShareClient] tied to an active voice session.
 *
 * Concrete factory: `dev.puklic.voice.screenshare.JvmScreenShareClientFactory` in
 * `:shared:voice/jvmMain` (wraps `DefaultScreenShareClient`). App Store wiring layers
 * (`:ios:app`, `:desktop:app` macAppStore) pass null factories to
 * [dev.puklic.voice.DefaultVoiceClient] — the client then keeps using
 * [NoOpScreenShareClient] for the lifetime of the call.
 *
 * Per architect plan `docs/03_infrastructure/architect-reports/2026-06-02-fp14h-6-blocked.md`
 * (SPI definition for FP-14h-7 promotion of DefaultVoiceClient to commonMain).
 */
public interface ScreenShareClientFactory {
    /**
     * Build a screen-share client bound to the live voice session.
     *
     * @param voiceGateway active voice WS for OP 12 video state + OP 13 / OP 14.
     * @param packetEncryptor AEAD cipher seeded with the SessionDescription secret key.
     * @param nonceGen nonce sequence dedicated to video (independent of audio counters).
     * @param udpTransport the shared UDP socket the audio pipeline is using.
     * @param getAudioSsrc supplier of the audio SSRC negotiated in Ready.
     * @param getVideoSsrc supplier of the video SSRC negotiated in Ready.
     * @param enumerator platform screen-source enumerator (windows + monitors).
     * @param scope session-scoped coroutine scope owning the encode + send loops.
     * @param videoCodec codec the server picked in SessionDescription.video_codec.
     */
    @Suppress("LongParameterList")
    public fun create(
        voiceGateway: VoiceGatewayConnection,
        packetEncryptor: AeadCipher,
        nonceGen: NonceGenerator,
        udpTransport: VoiceUdpTransport,
        getAudioSsrc: () -> Int,
        getVideoSsrc: () -> Int,
        enumerator: ScreenSourceEnumerator,
        scope: CoroutineScope,
        videoCodec: VideoCodec,
    ): ScreenShareClient
}
