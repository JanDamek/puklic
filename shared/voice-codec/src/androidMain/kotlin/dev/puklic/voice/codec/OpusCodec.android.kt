package dev.puklic.voice.codec

import dev.puklic.voice.AudioConstants
import io.github.jaredmdobson.concentus.OpusDecoder as ConcentusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder as ConcentusEncoder
import io.github.jaredmdobson.concentus.OpusException as ConcentusException
import io.github.jaredmdobson.concentus.OpusSignal
import io.github.jaredmdobson.concentus.OpusApplication as ConcentusApplication

/**
 * Android `actual` for [OpusCodecFactory] backed by Concentus, a pure-Java Opus implementation
 * (BSD-3-Clause), so the Android build needs no NDK and no per-ABI native libraries.
 */
public actual object OpusCodecFactory {

    public actual fun createEncoder(config: OpusEncoderConfig): OpusEncoder = ConcentusOpusEncoder(config)

    public actual fun createDecoder(channels: Int): OpusDecoder = ConcentusOpusDecoder(channels)
}

private const val EXPECTED_LOSS_PERCENT = 5

private class ConcentusOpusEncoder(config: OpusEncoderConfig) : OpusEncoder {

    override val channels: Int = config.channels

    private val encoder = wrap {
        ConcentusEncoder(AudioConstants.SAMPLE_RATE_HZ, channels, config.application.toConcentus())
    }
        .apply {
            bitrate = config.bitrate
            complexity = config.complexity
            useInbandFEC = config.inbandFec
            useDTX = config.dtx
            packetLossPercent = if (config.inbandFec) EXPECTED_LOSS_PERCENT else 0
            signalType = config.application.toSignalType()
        }

    override fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == AudioConstants.SAMPLES_PER_FRAME * channels) {
            "Opus frame must be ${AudioConstants.SAMPLES_PER_FRAME * channels} samples, got ${pcm.size}"
        }
        val out = ByteArray(AudioConstants.MAX_OPUS_FRAME_BYTES)
        val length = wrap { encoder.encode(pcm, 0, AudioConstants.SAMPLES_PER_FRAME, out, 0, out.size) }
        return out.copyOf(length)
    }

    override fun close() = Unit
}

private class ConcentusOpusDecoder(override val channels: Int) : OpusDecoder {

    private val decoder = wrap { ConcentusDecoder(AudioConstants.SAMPLE_RATE_HZ, channels) }

    override fun decode(opus: ByteArray?, fec: Boolean): ShortArray {
        val pcm = ShortArray(AudioConstants.SAMPLES_PER_FRAME * channels)
        val samplesPerChannel = wrap {
            decoder.decode(opus, 0, opus?.size ?: 0, pcm, 0, AudioConstants.SAMPLES_PER_FRAME, fec && opus != null)
        }
        check(samplesPerChannel == AudioConstants.SAMPLES_PER_FRAME) { "Opus decoded $samplesPerChannel samples" }
        return pcm
    }

    override fun close() = Unit
}

private fun OpusApplication.toConcentus(): ConcentusApplication = when (this) {
    OpusApplication.VoIp -> ConcentusApplication.OPUS_APPLICATION_VOIP
    OpusApplication.Audio -> ConcentusApplication.OPUS_APPLICATION_AUDIO
    OpusApplication.LowDelay -> ConcentusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY
}

private fun OpusApplication.toSignalType(): OpusSignal = when (this) {
    OpusApplication.VoIp -> OpusSignal.OPUS_SIGNAL_VOICE
    OpusApplication.Audio, OpusApplication.LowDelay -> OpusSignal.OPUS_SIGNAL_AUTO
}

private inline fun <T> wrap(block: () -> T): T = try {
    block()
} catch (failure: ConcentusException) {
    throw OpusException(failure.message.orEmpty())
}
