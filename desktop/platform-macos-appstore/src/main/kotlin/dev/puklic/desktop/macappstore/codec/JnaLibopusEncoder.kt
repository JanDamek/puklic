package dev.puklic.desktop.macappstore.codec

import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import dev.puklic.desktop.macappstore.bridge.Libopus
import dev.puklic.voice.AudioConstants
import dev.puklic.voice.codec.OpusApplication
import dev.puklic.voice.codec.OpusEncoder
import dev.puklic.voice.codec.OpusEncoderConfig
import dev.puklic.voice.codec.OpusException

/**
 * Mac App Store variant of [OpusEncoder]. Backed by the bundled
 * `libopus.dylib` (BSD-3-Clause) via JNA. Behaviour mirrors the iOS impl
 * [`IosOpusEncoder`][1] and the JVM impl [`LibavOpusEncoder`][2]: mono/stereo
 * 48 kHz 20 ms frames, configurable bitrate / complexity / FEC / DTX /
 * application via libopus CTL macros.
 *
 * NOT thread-safe — one instance per capture stream.
 *
 * [1]: shared/voice-codec/src/iosMain/kotlin/dev/puklic/voice/codec/IosOpusCodec.kt
 * [2]: shared/voice-codec/src/jvmMain/kotlin/dev/puklic/voice/codec/OpusCodec.jvm.kt
 */
public class JnaLibopusEncoder(config: OpusEncoderConfig = OpusEncoderConfig()) : OpusEncoder {

    public override val channels: Int = config.channels
    private val expectedPcmSize: Int = AudioConstants.SAMPLES_PER_FRAME * channels
    private val frameSize: Int = AudioConstants.SAMPLES_PER_FRAME

    private val opus: Libopus = Libopus.INSTANCE
    private val state: Pointer
    private var closed: Boolean = false

    init {
        val errRef = IntByReference(0)
        val st = opus.opus_encoder_create(
            AudioConstants.SAMPLE_RATE_HZ,
            channels,
            config.application.toNative(),
            errRef,
        )
        if (st == null || errRef.value != 0) {
            throw OpusException("opus_encoder_create failed", errRef.value)
        }
        state = st

        checkCtl("OPUS_SET_BITRATE", Libopus.OPUS_SET_BITRATE_REQUEST, config.bitrate)
        checkCtl("OPUS_SET_COMPLEXITY", Libopus.OPUS_SET_COMPLEXITY_REQUEST, config.complexity)
        checkCtl(
            "OPUS_SET_INBAND_FEC",
            Libopus.OPUS_SET_INBAND_FEC_REQUEST,
            if (config.inbandFec) 1 else 0,
        )
        if (config.inbandFec) {
            // Match the iOS + JVM impls (Discord voice-channel tolerance baseline).
            checkCtl(
                "OPUS_SET_PACKET_LOSS_PERC",
                Libopus.OPUS_SET_PACKET_LOSS_PERC_REQUEST,
                DEFAULT_EXPECTED_PACKET_LOSS_PCT,
            )
        }
        checkCtl("OPUS_SET_DTX", Libopus.OPUS_SET_DTX_REQUEST, if (config.dtx) 1 else 0)
    }

    public override fun encode(pcm: ShortArray): ByteArray {
        check(!closed) { "JnaLibopusEncoder is closed" }
        require(pcm.size == expectedPcmSize) {
            "Frame must be $expectedPcmSize samples " +
                "(${AudioConstants.SAMPLES_PER_FRAME} per channel * $channels channels), got ${pcm.size}"
        }
        val out = ByteArray(MAX_OPUS_PACKET_BYTES)
        val written = opus.opus_encode(state, pcm, frameSize, out, MAX_OPUS_PACKET_BYTES)
        if (written < 0) throw OpusException("opus_encode failed", written)
        return out.copyOf(written)
    }

    public override fun close() {
        if (!closed) {
            closed = true
            opus.opus_encoder_destroy(state)
        }
    }

    private fun checkCtl(name: String, request: Int, value: Int) {
        val rc = opus.opus_encoder_ctl(state, request, value)
        if (rc < 0) throw OpusException("opus_encoder_ctl($name) failed", rc)
    }

    private fun OpusApplication.toNative(): Int = when (this) {
        OpusApplication.VoIp -> Libopus.OPUS_APPLICATION_VOIP
        OpusApplication.Audio -> Libopus.OPUS_APPLICATION_AUDIO
        OpusApplication.LowDelay -> Libopus.OPUS_APPLICATION_RESTRICTED_LOWDELAY
    }

    private companion object {
        const val DEFAULT_EXPECTED_PACKET_LOSS_PCT: Int = 10
        const val MAX_OPUS_PACKET_BYTES: Int = 4000
    }
}
