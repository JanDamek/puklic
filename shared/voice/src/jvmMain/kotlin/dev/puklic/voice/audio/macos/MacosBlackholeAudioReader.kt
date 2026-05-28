package dev.puklic.voice.audio.macos

import dev.puklic.voice.AudioConstants
import dev.puklic.voice.audio.EncodedAudioPacket
import dev.puklic.voice.codec.OpusApplication
import dev.puklic.voice.codec.OpusCodecFactory
import dev.puklic.voice.codec.OpusEncoder
import dev.puklic.voice.codec.OpusEncoderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVInputFormat
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avdevice.avdevice_register_all
import org.bytedeco.ffmpeg.global.avformat.av_find_input_format
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EAGAIN
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AV_CH_LAYOUT_STEREO
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_uninit
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.swresample.swr_alloc
import org.bytedeco.ffmpeg.global.swresample.swr_convert
import org.bytedeco.ffmpeg.global.swresample.swr_free
import org.bytedeco.ffmpeg.global.swresample.swr_init
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.javacpp.ShortPointer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * macOS BlackHole 2ch screencast-audio reader.
 *
 * Pipeline:
 *   libavdevice avfoundation (audio-only `":<idx>"`) →
 *   PCM decoder →
 *   swresample to S16 interleaved stereo 48 kHz →
 *   slice into 20 ms (960-sample-per-channel) frames →
 *   libopus encoder (stereo, audio application) →
 *   [EncodedAudioPacket] Flow.
 *
 * Per repo `CLAUDE.md` HARD RULE #2 ("never temporary"): this reader ships the full capture
 * pipeline up to the `EncodedAudioPacket` boundary. Wiring the resulting flow onto the
 * Discord voice transport is **blocked on prerequisite 1 of issue #25** — the screencast-audio
 * SSRC / mixing model has not been decided yet. That wiring is a separate, scoped follow-up
 * once the SSRC research agent's design lands; it is NOT a TODO inside this file.
 *
 * Lifecycle: [start] / [stop] are idempotent and paired with the screen-share session.
 * The [packets] flow is cold; collecting it transitions the reader to capturing.
 *
 * @param deviceIndex the avfoundation audio device index of BlackHole 2ch on this host —
 *  resolved via [dev.puklic.voice.screenshare.source.BlackholeDetector.findDeviceIndex].
 *  Passing `null` (or starting before BlackHole is detected) produces a clear failure
 *  pointing the user at https://existential.audio/blackhole/.
 */
public class MacosBlackholeAudioReader(
    private val deviceIndex: String?,
) {

    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pumpJob: Job? = null

    /**
     * Opus packet stream — cold flow. Each emission is a 20 ms stereo Opus frame whose
     * `rtpTimestamp` advances by [AudioConstants.SAMPLES_PER_FRAME] per packet on the
     * mandated 48 kHz Opus RTP clock (RFC 7587).
     */
    public val packets: Flow<EncodedAudioPacket> = channelFlow {
        if (deviceIndex == null) {
            throw BlackholeNotInstalledException()
        }
        avdevice_register_all()

        val ifmt: AVInputFormat = av_find_input_format(AVFOUNDATION_INPUT_FORMAT)
            ?: error("libavdevice input format '$AVFOUNDATION_INPUT_FORMAT' not available")
        val fmtCtx: AVFormatContext = avformat_alloc_context()
            ?: error("avformat_alloc_context returned null")

        val opts = AVDictionary(null)
        // avfoundation cannot probe its real native rate without a capture, so pin both sides
        // to the Discord-mandated 48 kHz stereo target. swresample handles any mismatch.
        av_dict_set(opts, "sample_rate", BLACKHOLE_SAMPLE_RATE_HZ.toString(), 0)
        av_dict_set(opts, "channels", BLACKHOLE_CHANNELS.toString(), 0)

        val url = audioOnlyAvfoundationUrl(deviceIndex)
        val openRc = avformat_open_input(fmtCtx, url, ifmt, opts)
        av_dict_free(opts)
        if (openRc < 0) {
            error("avformat_open_input('$url') failed: $openRc — is BlackHole 2ch installed?")
        }

        var decCtx: AVCodecContext? = null
        var srcFrame: AVFrame? = null
        var pkt: AVPacket? = null
        var swr: SwrContext? = null
        var encoder: OpusEncoder? = null
        val outLayout = AVChannelLayout()
        val inLayout = AVChannelLayout()

        try {
            check(avformat_find_stream_info(fmtCtx, null as AVDictionary?) >= 0) {
                "avformat_find_stream_info failed"
            }
            val audioStreamIdx = (0 until fmtCtx.nb_streams()).firstOrNull { i ->
                fmtCtx.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_AUDIO
            } ?: error("No audio stream in BlackHole avfoundation input")
            val codecpar = fmtCtx.streams(audioStreamIdx).codecpar()

            val decoder: AVCodec = avcodec_find_decoder(codecpar.codec_id())
                ?: error("Decoder for codec_id=${codecpar.codec_id()} not available")
            decCtx = avcodec_alloc_context3(decoder) ?: error("avcodec_alloc_context3(audio) null")
            check(avcodec_parameters_to_context(decCtx, codecpar) >= 0) {
                "avcodec_parameters_to_context(audio) failed"
            }
            check(avcodec_open2(decCtx, decoder, null as AVDictionary?) >= 0) {
                "avcodec_open2(audio decoder) failed"
            }

            av_channel_layout_default(outLayout, BLACKHOLE_CHANNELS)
            // Input layout from the decoder; copy by default-from-count to avoid relying on
            // codecpar's optional layout field (avfoundation rarely populates it explicitly).
            av_channel_layout_default(inLayout, decCtx.ch_layout().nb_channels().coerceAtLeast(1))

            swr = swr_alloc() ?: error("swr_alloc returned null")
            // Use the option-based configuration API by setting fields directly via swr's
            // av_opt interface is more complex than the channel-layout setter helper. Use
            // swr_alloc_set_opts2 via global function for clarity.
            val swrInitRc = org.bytedeco.ffmpeg.global.swresample.swr_alloc_set_opts2(
                swr,
                outLayout, AV_SAMPLE_FMT_S16, BLACKHOLE_SAMPLE_RATE_HZ,
                inLayout, decCtx.sample_fmt(), decCtx.sample_rate(),
                0, null,
            )
            if (swrInitRc < 0) error("swr_alloc_set_opts2 failed: $swrInitRc")
            check(swr_init(swr) >= 0) { "swr_init failed" }

            encoder = OpusCodecFactory.createEncoder(
                OpusEncoderConfig(
                    channels = AudioConstants.CHANNELS_STEREO,
                    application = OpusApplication.Audio,
                ),
            )

            srcFrame = av_frame_alloc() ?: error("av_frame_alloc(audio src) null")
            pkt = av_packet_alloc() ?: error("av_packet_alloc(audio) null")

            val frameSamplesPerChannel = BLACKHOLE_FRAME_SAMPLES_PER_CHANNEL
            val pendingPcm = ShortArray(frameSamplesPerChannel * BLACKHOLE_CHANNELS * RESAMPLE_BUFFER_FRAMES)
            var pendingSamples = 0 // per channel
            var rtpTs = 0L

            while (!closed.get() && !isClosedForSend) {
                val readRc = av_read_frame(fmtCtx, pkt)
                if (readRc == AVERROR_EOF()) break
                if (readRc < 0) {
                    if (readRc == AVERROR_EAGAIN()) {
                        av_packet_unref(pkt)
                        continue
                    }
                    break
                }
                if (pkt.stream_index() != audioStreamIdx) {
                    av_packet_unref(pkt)
                    continue
                }

                val sendRc = avcodec_send_packet(decCtx, pkt)
                av_packet_unref(pkt)
                if (sendRc < 0 && sendRc != AVERROR_EAGAIN()) {
                    error("avcodec_send_packet(audio) failed: $sendRc")
                }

                while (!closed.get() && !isClosedForSend) {
                    val recvRc = avcodec_receive_frame(decCtx, srcFrame)
                    if (recvRc == AVERROR_EAGAIN() || recvRc == AVERROR_EOF()) break
                    if (recvRc < 0) error("avcodec_receive_frame(audio) failed: $recvRc")

                    val inSamples = srcFrame.nb_samples()
                    val outCapacity =
                        ((pendingPcm.size / BLACKHOLE_CHANNELS) - pendingSamples).coerceAtLeast(0)
                    if (outCapacity == 0) continue

                    // Resample directly into a temporary S16 buffer, then copy into pendingPcm.
                    val tmpBytes = ByteArray(outCapacity * BLACKHOLE_CHANNELS * Short.SIZE_BYTES)
                    val tmpPtr = BytePointer(tmpBytes.size.toLong())
                    val outPlanes = PointerPointer<BytePointer>(1).put(0L, tmpPtr)
                    val converted = swr_convert(
                        swr,
                        outPlanes, outCapacity,
                        srcFrame.extended_data(), inSamples,
                    )
                    if (converted < 0) error("swr_convert failed: $converted")
                    if (converted > 0) {
                        val shorts = ShortArray(converted * BLACKHOLE_CHANNELS)
                        ShortPointer(tmpPtr).position(0L).get(shorts, 0, shorts.size)
                        System.arraycopy(
                            shorts, 0,
                            pendingPcm, pendingSamples * BLACKHOLE_CHANNELS,
                            shorts.size,
                        )
                        pendingSamples += converted
                    }
                    tmpPtr.close()
                    outPlanes.close()

                    while (pendingSamples >= frameSamplesPerChannel && !isClosedForSend) {
                        val framePcm = ShortArray(frameSamplesPerChannel * BLACKHOLE_CHANNELS)
                        System.arraycopy(pendingPcm, 0, framePcm, 0, framePcm.size)
                        val opus = encoder.encode(framePcm)
                        if (opus.isNotEmpty()) {
                            send(EncodedAudioPacket(opus = opus, rtpTimestamp = rtpTs))
                        }
                        rtpTs += AudioConstants.SAMPLES_PER_FRAME.toLong()
                        // Shift the buffer left by one frame.
                        val remaining = pendingSamples - frameSamplesPerChannel
                        if (remaining > 0) {
                            System.arraycopy(
                                pendingPcm, framePcm.size,
                                pendingPcm, 0,
                                remaining * BLACKHOLE_CHANNELS,
                            )
                        }
                        pendingSamples = remaining
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            encoder?.close()
            pkt?.let { av_packet_free(it) }
            srcFrame?.let { av_frame_free(it) }
            swr?.let { swr_free(it) }
            decCtx?.let { avcodec_free_context(it) }
            avformat_close_input(fmtCtx)
            av_channel_layout_uninit(outLayout)
            av_channel_layout_uninit(inLayout)
        }

        awaitClose { closed.set(true) }
    }.flowOn(Dispatchers.IO)

    /**
     * Validate prerequisites without starting a background collector. The actual capture
     * runs lazily when a collector subscribes to [packets]; this method exists so callers
     * (the screenshare client) can fail fast with a clear error when BlackHole is missing.
     */
    public fun start(): Result<Unit> {
        if (deviceIndex == null) {
            return Result.failure(BlackholeNotInstalledException())
        }
        closed.set(false)
        return Result.success(Unit)
    }

    /** Cancel any active collector and free pipeline resources. Safe to call multiple times. */
    public fun stop() {
        closed.set(true)
        val job = pumpJob
        pumpJob = null
        if (job != null) {
            runBlocking { job.cancelAndJoin() }
        }
    }

    /**
     * Internal pump used by integration callers that want a hot collector. Production
     * wiring will collect [packets] directly into the RTP sender once issue #25 prereq 1
     * lands; this helper exists so tests / future glue can keep the lifecycle in one place.
     */
    internal fun pumpInto(sink: suspend (EncodedAudioPacket) -> Unit) {
        pumpJob = scope.launch {
            packets.collect { sink(it) }
        }
    }

    public class BlackholeNotInstalledException : Exception(
        "BlackHole 2ch virtual audio device is not installed. " +
            "Install it from https://existential.audio/blackhole/ to enable " +
            "Share with audio on macOS.",
    )

    public companion object {
        public const val BLACKHOLE_DEVICE_NAME: String = "BlackHole 2ch"
        public const val BLACKHOLE_SAMPLE_RATE_HZ: Int = 48_000
        public const val BLACKHOLE_CHANNELS: Int = 2
        public const val BLACKHOLE_FRAME_SAMPLES_PER_CHANNEL: Int = 960
        internal const val AVFOUNDATION_INPUT_FORMAT: String = "avfoundation"
        // Hold up to this many 20 ms frames worth of PCM in the resample buffer before
        // we'd start dropping samples (we never reach this in practice — we drain every
        // iteration). 4 frames = 80 ms of headroom is enough to absorb avfoundation's
        // bursty delivery without growing the buffer indefinitely.
        private const val RESAMPLE_BUFFER_FRAMES: Int = 4

        /**
         * Build the libavdevice avfoundation URL for an audio-only capture from device
         * [audioIndex]. The format string is `"<video>:<audio>"`; the video slot is left
         * empty for audio-only readers.
         */
        @JvmStatic
        public fun audioOnlyAvfoundationUrl(audioIndex: String): String = ":$audioIndex"

        @Suppress("unused")
        private fun stereoLayoutMarker(): Long = AV_CH_LAYOUT_STEREO
    }
}
