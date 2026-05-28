package dev.puklic.voice.transport

import dev.puklic.voice.crypto.AeadCipher

/**
 * RTP sender for the soundshare (screencast audio) SSRC.
 *
 * Architecture: separate-SSRC model per architect report
 * `docs/03_infrastructure/architect-reports/2026-05-28-screencast-audio-ssrc.md` §5.
 * Soundshare audio is published on its own Discord audio SSRC (= videoSsrc + 1), independent
 * Opus encoder (stereo, `Application.Audio`), with packetisation and AEAD identical to the
 * mic audio path — same UDP socket, same secret key, same `xchacha20_poly1305_rtpsize` mode,
 * Opus payload type 120, RTP timestamps at 48 kHz.
 *
 * Stereo handling: the RTP timestamp ticks at the 48 kHz sample-rate clock and advances by
 * [VoicePacketCodec.AUDIO_SAMPLES_PER_FRAME] = 960 per 20 ms frame regardless of channel
 * count (RFC 7587 §4.2 — Opus RTP timestamps are channel-independent).
 *
 * State machine: this class owns a private [VoicePacketCodec] so its sequence / timestamp /
 * nonce-counter triplet is independent from the mic SSRC's triplet, as required by RFC 3550
 * (each SSRC has its own monotonic clock).
 *
 * Thread-safety: callers must serialise [send] invocations (typically by collecting from a
 * single upstream `Flow<ByteArray>` on a single dispatcher) — the underlying [VoicePacketCodec]
 * uses atomics for counters but does not coordinate batches across concurrent senders.
 *
 * Lifecycle: this sender does not own the UDP transport or the AEAD cipher. The owner
 * ([dev.puklic.voice.screenshare.DefaultScreenShareClient]) constructs one instance per
 * share-with-audio session and discards it on stop.
 */
internal class SoundshareAudioRtpSender(
    private val udp: UdpRtpTransport,
    aead: AeadCipher,
    ssrc: Int,
) {

    private val codec = VoicePacketCodec(aead = aead, ssrc = ssrc)

    /**
     * Encrypt and dispatch one Opus-encoded soundshare frame.
     *
     * @param opusFrame Opus packet bytes from the stereo `Application.Audio` encoder
     *  (20 ms / 960 samples per channel, interleaved L,R at the encoder input).
     */
    suspend fun send(opusFrame: ByteArray) {
        udp.send(codec.encode(opusFrame))
    }
}
