package dev.puklic.voice.codec

import dev.puklic.voice.AudioConstants
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EAGAIN
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLT
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable
import org.bytedeco.ffmpeg.global.avutil.av_opt_set
import org.bytedeco.ffmpeg.global.avutil.av_opt_set_int
import kotlinx.atomicfu.atomic
import org.bytedeco.javacpp.ShortPointer

/**
 * JVM actual for [OpusCodecFactory]. Uses libavcodec (FFmpeg GPL build, JavaCPP-bundled).
 *
 * Self-contained phase 1 (2026-05-23): replaces the previous JNA → system libopus binding.
 * Opus encoder/decoder come from the `libopus` wrapper inside `ffmpeg-platform-gpl:7.1-1.5.11`
 * (no system libopus required). Natives extract to `~/.javacpp/cache/` on first use.
 *
 * Encoder: `libopus` wrapper — accepts `AV_SAMPLE_FMT_S16`, exposes FEC / DTX / VBR via av_opt_set.
 * Decoder: native FFmpeg Opus decoder — emits `AV_SAMPLE_FMT_FLT` (interleaved float32) on success;
 * we downcast to int16 to keep the public [OpusDecoder] surface stable.
 *
 * Frames are mono 48 kHz 20 ms (960 samples). Stereo / non-20 ms frames out of scope.
 */
public actual object OpusCodecFactory {

    public actual fun createEncoder(config: OpusEncoderConfig): OpusEncoder = LibavOpusEncoder(config)

    public actual fun createDecoder(channels: Int): OpusDecoder = LibavOpusDecoder(channels)
}

private const val OPUS_ENCODER_NAME = "libopus"

private class LibavOpusEncoder(config: OpusEncoderConfig) : OpusEncoder {

    override val channels: Int = config.channels
    private val expectedPcmSize: Int = AudioConstants.SAMPLES_PER_FRAME * channels

    private val codec: AVCodec
    private val ctx: AVCodecContext
    private val frame: AVFrame
    private val packet: AVPacket
    private val closed = atomic(false)

    init {
        // Prefer the libopus wrapper (accepts S16 PCM directly). Fall back to the native FFmpeg
        // Opus encoder is intentionally NOT done — it's marked experimental and requires FLTP.
        codec = avcodec_find_encoder_by_name(OPUS_ENCODER_NAME)
            ?: throw OpusException(
                "libopus encoder not available in this FFmpeg build. " +
                    "Ensure org.bytedeco:ffmpeg-platform-gpl:7.1-1.5.11 is on the classpath.",
            )

        val allocated = avcodec_alloc_context3(codec)
            ?: throw OpusException("avcodec_alloc_context3 returned null")
        ctx = allocated
        ctx.sample_rate(AudioConstants.SAMPLE_RATE_HZ)
        ctx.sample_fmt(AV_SAMPLE_FMT_S16)
        ctx.bit_rate(config.bitrate.toLong())
        av_channel_layout_default(ctx.ch_layout(), channels)

        // Opus wrapper options (libavcodec/libopusenc.c):
        //   application: voip | audio | lowdelay
        //   vbr: off | on | constrained
        //   frame_duration: 2.5..120 (ms) — 20 matches our 960-sample frame
        //   fec: in-band forward error correction (0/1)
        //   packet_loss: expected packet loss percentage 0..100 (gates FEC activity)
        //   dtx: discontinuous transmission (0/1)
        av_opt_set(ctx.priv_data(), "application", config.application.libopusName, 0)
        av_opt_set(ctx.priv_data(), "vbr", "on", 0)
        av_opt_set_int(ctx.priv_data(), "frame_duration", 20, 0)
        av_opt_set_int(ctx.priv_data(), "fec", if (config.inbandFec) 1L else 0L, 0)
        if (config.inbandFec) {
            // Without expected-loss > 0 the wrapper does not actually emit FEC payloads.
            av_opt_set_int(ctx.priv_data(), "packet_loss", DEFAULT_EXPECTED_PACKET_LOSS_PCT, 0)
        }
        av_opt_set_int(ctx.priv_data(), "dtx", if (config.dtx) 1L else 0L, 0)
        // Map our 0..10 complexity onto libopus complexity directly.
        av_opt_set_int(ctx.priv_data(), "compression_level", config.complexity.toLong(), 0)

        val rc = avcodec_open2(ctx, codec, null as AVDictionary?)
        if (rc < 0) {
            avcodec_free_context(ctx)
            throw OpusException("avcodec_open2(libopus encoder) failed", rc)
        }

        frame = av_frame_alloc() ?: run {
            avcodec_free_context(ctx)
            throw OpusException("av_frame_alloc returned null")
        }
        frame.nb_samples(AudioConstants.SAMPLES_PER_FRAME)
        frame.format(AV_SAMPLE_FMT_S16)
        frame.sample_rate(AudioConstants.SAMPLE_RATE_HZ)
        av_channel_layout_default(frame.ch_layout(), channels)
        val bufRc = av_frame_get_buffer(frame, 0)
        if (bufRc < 0) {
            av_frame_free(frame)
            avcodec_free_context(ctx)
            throw OpusException("av_frame_get_buffer failed", bufRc)
        }

        packet = av_packet_alloc() ?: run {
            av_frame_free(frame)
            avcodec_free_context(ctx)
            throw OpusException("av_packet_alloc returned null")
        }
    }

    override fun encode(pcm: ShortArray): ByteArray {
        check(!closed.value) { "OpusEncoder is closed" }
        require(pcm.size == expectedPcmSize) {
            "Frame must be $expectedPcmSize samples " +
                "(${AudioConstants.SAMPLES_PER_FRAME} per channel * $channels channels), got ${pcm.size}"
        }

        val makeWritable = av_frame_make_writable(frame)
        if (makeWritable < 0) throw OpusException("av_frame_make_writable failed", makeWritable)

        // Mono S16: data[0] is a contiguous Int16 buffer of nb_samples * channels samples.
        val dst = ShortPointer(frame.data(0))
        dst.position(0L)
        dst.put(pcm, 0, pcm.size)

        val sendRc = avcodec_send_frame(ctx, frame)
        if (sendRc < 0) throw OpusException("avcodec_send_frame failed", sendRc)

        val recvRc = avcodec_receive_packet(ctx, packet)
        if (recvRc == AVERROR_EAGAIN() || recvRc == AVERROR_EOF()) {
            // libopus is a 1:1 encoder for 20 ms frames; this is unexpected but not fatal.
            return ByteArray(0)
        }
        if (recvRc < 0) throw OpusException("avcodec_receive_packet failed", recvRc)

        val size = packet.size()
        val out = ByteArray(size)
        if (size > 0) {
            packet.data().capacity(size.toLong()).get(out)
        }
        av_packet_unref(packet)
        return out
    }

    override fun close() {
        if (closed.compareAndSet(expect = false, update = true)) {
            av_packet_free(packet)
            av_frame_free(frame)
            avcodec_free_context(ctx)
        }
    }

    private companion object {
        // Telling the encoder we expect ~10% packet loss makes libopus actually emit in-band FEC
        // payloads when fec=1. Matches Discord's default tolerance for voice channels.
        const val DEFAULT_EXPECTED_PACKET_LOSS_PCT = 10L
    }
}

private class LibavOpusDecoder(override val channels: Int) : OpusDecoder {

    private val outputSize: Int = AudioConstants.SAMPLES_PER_FRAME * channels

    private val codec: AVCodec
    private val ctx: AVCodecContext
    private val frame: AVFrame
    private val packet: AVPacket
    private val closed = atomic(false)

    init {
        require(channels == AudioConstants.CHANNELS_MONO || channels == AudioConstants.CHANNELS_STEREO) {
            "Opus channels must be 1 (mono) or 2 (stereo), got $channels"
        }

        // Prefer the libopus wrapper for the decoder too (S16 output, matches our pipeline
        // without the int16<-float32 downcast). Fall back to native FFmpeg Opus decoder
        // (emits FLTP) if libopus wrapper is unavailable for some reason.
        var c = avcodec_find_decoder_by_name(OPUS_ENCODER_NAME)
        val usingLibopus: Boolean
        if (c != null) {
            usingLibopus = true
        } else {
            c = avcodec_find_decoder(AV_CODEC_ID_OPUS)
                ?: throw OpusException("Opus decoder not available")
            usingLibopus = false
        }
        codec = c

        val allocated = avcodec_alloc_context3(codec)
            ?: throw OpusException("avcodec_alloc_context3 returned null")
        ctx = allocated
        ctx.sample_rate(AudioConstants.SAMPLE_RATE_HZ)
        av_channel_layout_default(ctx.ch_layout(), channels)
        // Request S16 output where supported. The native FFmpeg Opus decoder will still emit
        // FLTP regardless; we handle both shapes when reading the frame.
        if (usingLibopus) ctx.request_sample_fmt(AV_SAMPLE_FMT_S16)

        val rc = avcodec_open2(ctx, codec, null as AVDictionary?)
        if (rc < 0) {
            avcodec_free_context(ctx)
            throw OpusException("avcodec_open2(opus decoder) failed", rc)
        }

        frame = av_frame_alloc() ?: run {
            avcodec_free_context(ctx)
            throw OpusException("av_frame_alloc returned null")
        }
        packet = av_packet_alloc() ?: run {
            av_frame_free(frame)
            avcodec_free_context(ctx)
            throw OpusException("av_packet_alloc returned null")
        }
    }

    override fun decode(opus: ByteArray?, fec: Boolean): ShortArray {
        check(!closed.value) { "OpusDecoder is closed" }
        if (opus == null) {
            // libavcodec does not expose an explicit PLC entry point. Send an empty packet
            // (data=null, size=0) — the decoder treats it as a missed frame and runs PLC.
            return decodeOnePacket(null)
        }
        return decodeOnePacket(opus)
    }

    private fun decodeOnePacket(data: ByteArray?): ShortArray {
        if (data != null && data.isNotEmpty()) {
            val buf = org.bytedeco.javacpp.BytePointer(data.size.toLong())
            buf.put(data, 0, data.size)
            packet.data(buf)
            packet.size(data.size)
        } else {
            packet.data(null)
            packet.size(0)
        }

        val sendRc = avcodec_send_packet(ctx, packet)
        // Reset packet ref count before unref to avoid leaks on the BytePointer we attached.
        av_packet_unref(packet)
        if (sendRc < 0 && sendRc != AVERROR_EAGAIN()) {
            throw OpusException("avcodec_send_packet failed", sendRc)
        }

        val recvRc = avcodec_receive_frame(ctx, frame)
        if (recvRc == AVERROR_EAGAIN() || recvRc == AVERROR_EOF()) {
            // No output produced yet — return silence the caller can mix.
            return ShortArray(outputSize)
        }
        if (recvRc < 0) throw OpusException("avcodec_receive_frame failed", recvRc)

        val samples = frame.nb_samples()
        val out = ShortArray(outputSize)
        when (frame.format()) {
            AV_SAMPLE_FMT_S16 -> {
                // Packed S16 interleaved: nb_samples * channels shorts in data[0].
                val src = ShortPointer(frame.data(0))
                val n = minOf(samples * channels, out.size)
                src.position(0L).get(out, 0, n)
            }
            AV_SAMPLE_FMT_FLT -> {
                // Native FFmpeg Opus decoder path: packed interleaved float32 in data[0].
                val src = org.bytedeco.javacpp.FloatPointer(frame.data(0))
                val n = minOf(samples * channels, out.size)
                val tmp = FloatArray(n)
                src.position(0L).get(tmp, 0, n)
                for (i in 0 until n) {
                    val v = (tmp[i] * Short.MAX_VALUE).toInt()
                    out[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
            else -> throw OpusException("Unexpected decoder sample format ${frame.format()}")
        }
        return out
    }

    override fun close() {
        if (closed.compareAndSet(expect = false, update = true)) {
            av_packet_free(packet)
            av_frame_free(frame)
            avcodec_free_context(ctx)
        }
    }
}
