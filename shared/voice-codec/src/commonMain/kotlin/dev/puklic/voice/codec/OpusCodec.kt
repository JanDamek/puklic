package dev.puklic.voice.codec

import dev.puklic.voice.AudioConstants

/**
 * Opus encoder / decoder facade for Puklic voice.
 *
 * JVM backend (self-contained phase 1, 2026-05-23): libavcodec via JavaCPP FFmpeg GPL
 * (`org.bytedeco:ffmpeg-platform-gpl:7.1-1.5.11`). The libopus wrapper inside that build
 * provides Opus encoder/decoder — no system libopus install needed. See
 * `shared/voice/src/jvmMain/.../codec/OpusCodec.jvm.kt`.
 *
 * History: pure-JVM `concentus` was blocked (jitpack-only, internal Gradle init script
 * strips non-mavenCentral repos). The restcomm `opus-java` JNI bundle ships native libs
 * only for Linux. JNA to system libopus was the bridge in Phase 3.0; we now bundle
 * natives via JavaCPP to make installers self-contained.
 *
 * All frames are mono 48 kHz 20 ms (960 samples). Sample-rate / channel / frame-size
 * variants are out of scope for Phase 3.0 — see `AudioConstants` in `PublicApi.kt`.
 */

/**
 * Opus application mode. Maps 1:1 to libopus `OPUS_APPLICATION_*`.
 *  - [VoIp]: mic capture (voice, lower latency)
 *  - [Audio]: screencast audio (music / general fidelity)
 *  - [LowDelay]: restricted low-delay
 */
public enum class OpusApplication(internal val libopusName: String) {
    VoIp("voip"),
    Audio("audio"),
    LowDelay("lowdelay"),
}

/** Opus encoder. NOT thread-safe; one instance per capture stream. Must be closed. */
public interface OpusEncoder : AutoCloseable {

    /** Channel count this encoder was created for ([AudioConstants.CHANNELS_MONO] or [AudioConstants.CHANNELS_STEREO]). */
    public val channels: Int

    /**
     * Encode one frame of interleaved 48 kHz signed-16 PCM into an Opus packet.
     *
     * @param pcm ShortArray of `SAMPLES_PER_FRAME * channels` samples
     *  (mono: 960; stereo: 1920 interleaved L,R).
     * @return Opus packet bytes (typically 80–200 B at 64 kbps for mic; never > 4000 B).
     * @throws OpusException on libopus failure.
     */
    public fun encode(pcm: ShortArray): ByteArray
}

/** Opus decoder. NOT thread-safe; one instance per remote SSRC. Must be closed. */
public interface OpusDecoder : AutoCloseable {

    /** Channel count this decoder was created for. */
    public val channels: Int

    /**
     * Decode one Opus packet into interleaved 48 kHz signed-16 PCM.
     *
     * @param opus Opus packet bytes, or `null` to invoke packet-loss concealment (PLC).
     * @param fec When `true` AND `opus != null`, decode the FEC payload (in-band redundant
     *        data) of the next packet as if it were the previous packet.
     * @return ShortArray of `SAMPLES_PER_FRAME * channels` samples. libopus auto-remixes
     *   between the packet's channel count and this decoder's configured [channels].
     * @throws OpusException on libopus failure.
     */
    public fun decode(opus: ByteArray?, fec: Boolean = false): ShortArray
}

/**
 * Bitrate / complexity / FEC / DTX configuration for an [OpusEncoder].
 *
 * Defaults match Discord recommendations for voice chat:
 *  - 64 kbps CBR-ish (Opus is internally VBR but bitrate caps the average)
 *  - complexity 10 (max quality, CPU is irrelevant at one stream)
 *  - in-band FEC on (one packet of forward error correction)
 *  - DTX off (Discord prefers silence frames over comfort noise to drive speaking indicator)
 */
public data class OpusEncoderConfig(
    val bitrate: Int = 64_000,
    val complexity: Int = 10,
    val inbandFec: Boolean = true,
    val dtx: Boolean = false,
    val channels: Int = AudioConstants.CHANNELS_MONO,
    val application: OpusApplication = OpusApplication.VoIp,
) {
    init {
        require(bitrate in 6_000..510_000) { "Opus bitrate out of range: $bitrate" }
        require(complexity in 0..10) { "Opus complexity out of range: $complexity" }
        require(channels == AudioConstants.CHANNELS_MONO || channels == AudioConstants.CHANNELS_STEREO) {
            "Opus channels must be 1 (mono) or 2 (stereo), got $channels"
        }
    }
}

/** Thrown by [OpusEncoder] / [OpusDecoder] on libopus failure. */
public class OpusException(message: String, public val errorCode: Int = 0) :
    RuntimeException("$message (opus error $errorCode)")

/**
 * Platform factory for Opus codec instances. The jvm `actual` lives in
 * `OpusCodec.jvm.kt` and uses JNA + system libopus.
 */
public expect object OpusCodecFactory {

    public fun createEncoder(config: OpusEncoderConfig = OpusEncoderConfig()): OpusEncoder

    /**
     * @param channels [AudioConstants.CHANNELS_MONO] for mic playback or
     *  [AudioConstants.CHANNELS_STEREO] for screencast audio playback.
     */
    public fun createDecoder(channels: Int = AudioConstants.CHANNELS_MONO): OpusDecoder
}
