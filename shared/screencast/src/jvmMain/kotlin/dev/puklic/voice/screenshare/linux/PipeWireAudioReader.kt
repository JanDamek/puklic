package dev.puklic.voice.screenshare.linux

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
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
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AV_CH_LAYOUT_STEREO
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_uninit
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.swresample.swr_alloc
import org.bytedeco.ffmpeg.global.swresample.swr_alloc_set_opts2
import org.bytedeco.ffmpeg.global.swresample.swr_convert
import org.bytedeco.ffmpeg.global.swresample.swr_free
import org.bytedeco.ffmpeg.global.swresample.swr_init
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.javacpp.ShortPointer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads PCM audio from a PipeWire node attached to an xdg-desktop-portal screencast session and
 * emits 20 ms / 48 kHz / interleaved-stereo / signed-16 frames as [ShortArray] of 1920 samples
 * (960 per channel) — the exact format the soundshare Opus encoder consumes.
 *
 * Architecture: this is the "PipeWire PCM reader" piece of architect report
 * `docs/03_infrastructure/architect-reports/2026-05-28-pipewire-pcm-reader.md`. It reuses the
 * javacpp FFmpeg GPL bundle that is already on the desktop classpath
 * (`ffmpeg-platform-gpl:7.1-1.5.11`) — same `pipewire` libavdevice demuxer that
 * [LibavVideoEncoder] uses for video. Zero new Gradle dependencies.
 *
 * Lifecycle: the cold [read] flow opens libavdevice on first collection, runs the decode +
 * resample loop on [Dispatchers.IO], and frees everything in reverse allocation order on
 * cancellation. The [AtomicBoolean] gate keeps the close path observable from any thread.
 *
 * Linux-only — the `pipewire` demuxer is present in the FFmpeg GPL bundle for all OSes but the
 * PipeWire daemon and the portal-allocated fd only exist on Linux. Callers MUST gate
 * construction on the platform.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
public class PipeWireAudioReader(
    private val nodeId: Int,
    private val fd: Int,
) {

    private val closed = AtomicBoolean(false)

    fun stop() {
        closed.set(true)
    }

    fun read(): Flow<ShortArray> = channelFlow {
        avdevice_register_all()

        val ifmt: AVInputFormat = av_find_input_format(PIPEWIRE_FORMAT)
            ?: error("libavdevice input format '$PIPEWIRE_FORMAT' not available")

        val fmtCtx: AVFormatContext = avformat_alloc_context()
            ?: error("avformat_alloc_context returned null")
        val opts = AVDictionary(null)
        av_dict_set(opts, "fd", fd.toString(), 0)

        val openRc = avformat_open_input(fmtCtx, nodeId.toString(), ifmt, opts)
        av_dict_free(opts)
        if (openRc < 0) error("avformat_open_input('$nodeId') failed: $openRc")

        var decCtx: AVCodecContext? = null
        var pkt: AVPacket? = null
        var srcFrame: AVFrame? = null
        var swr: SwrContext? = null
        val outLayout = AVChannelLayout()

        try {
            check(avformat_find_stream_info(fmtCtx, null as AVDictionary?) >= 0) {
                "avformat_find_stream_info failed"
            }
            val audioStreamIdx = (0 until fmtCtx.nb_streams()).firstOrNull { i ->
                fmtCtx.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_AUDIO
            } ?: error("No audio stream in PipeWire node $nodeId")
            val srcCodecpar = fmtCtx.streams(audioStreamIdx).codecpar()

            val decoder: AVCodec = avcodec_find_decoder(srcCodecpar.codec_id())
                ?: error("Audio decoder for codec_id=${srcCodecpar.codec_id()} not available")
            decCtx = avcodec_alloc_context3(decoder) ?: error("avcodec_alloc_context3(dec) null")
            check(avcodec_parameters_to_context(decCtx, srcCodecpar) >= 0) {
                "avcodec_parameters_to_context(audio) failed"
            }
            check(avcodec_open2(decCtx, decoder, null as AVDictionary?) >= 0) {
                "avcodec_open2(audio decoder) failed"
            }

            // Resampler: input layout/rate/fmt come from the decoder context; output is fixed to
            // 48 kHz stereo S16 — exactly what SoundshareAudioRtpSender wants.
            av_channel_layout_default(outLayout, OUTPUT_CHANNELS)
            swr = swr_alloc() ?: error("swr_alloc returned null")
            check(
                swr_alloc_set_opts2(
                    swr,
                    outLayout,
                    AV_SAMPLE_FMT_S16,
                    OUTPUT_SAMPLE_RATE,
                    decCtx.ch_layout(),
                    decCtx.sample_fmt(),
                    decCtx.sample_rate(),
                    0,
                    null,
                ) >= 0,
            ) { "swr_alloc_set_opts2 failed" }
            check(swr_init(swr) >= 0) { "swr_init failed" }

            pkt = av_packet_alloc() ?: error("av_packet_alloc returned null")
            srcFrame = av_frame_alloc() ?: error("av_frame_alloc returned null")

            // Ring buffer holding interleaved S16 stereo samples awaiting frame-boundary emission.
            val pending = ShortArray(FRAME_SAMPLES_STEREO * BUFFER_FRAMES)
            var pendingLen = 0

            while (!closed.get()) {
                val readRc = av_read_frame(fmtCtx, pkt)
                if (readRc < 0) {
                    if (readRc == AVERROR_EOF()) break
                    if (readRc == AVERROR_EAGAIN()) continue
                    error("av_read_frame(audio) failed: $readRc")
                }
                try {
                    if (pkt.stream_index() != audioStreamIdx) continue
                    val sendRc = avcodec_send_packet(decCtx, pkt)
                    if (sendRc < 0 && sendRc != AVERROR_EAGAIN()) {
                        error("avcodec_send_packet(audio) failed: $sendRc")
                    }
                    while (!closed.get()) {
                        val rc = avcodec_receive_frame(decCtx, srcFrame)
                        if (rc == AVERROR_EAGAIN() || rc == AVERROR_EOF()) break
                        if (rc < 0) error("avcodec_receive_frame(audio) failed: $rc")

                        // Allocate an output buffer sized for the maximum possible converted
                        // sample count (input rate may be higher than 48 kHz).
                        val outSamples = srcFrame.nb_samples()
                            .toLong()
                            .times(OUTPUT_SAMPLE_RATE.toLong())
                            .div(maxOf(decCtx.sample_rate(), 1).toLong())
                            .toInt()
                            .coerceAtLeast(srcFrame.nb_samples())
                        val outBytes = outSamples.toLong() * OUTPUT_CHANNELS.toLong() *
                            BYTES_PER_SAMPLE.toLong()
                        val outBuf = BytePointer(outBytes)
                        val outPlanes = PointerPointer<BytePointer>(1L).put(0L, outBuf)
                        val inPlanes = PointerPointer<BytePointer>(srcFrame.extended_data())
                        val produced = swr_convert(
                            swr,
                            outPlanes,
                            outSamples,
                            inPlanes,
                            srcFrame.nb_samples(),
                        )
                        if (produced < 0) {
                            outBuf.deallocate()
                            error("swr_convert failed: $produced")
                        }
                        val convertedShorts = produced * OUTPUT_CHANNELS
                        if (pendingLen + convertedShorts > pending.size) {
                            // Backpressure: drop oldest samples to keep latency bounded. Shouldn't
                            // happen at the configured BUFFER_FRAMES, but guard anyway.
                            Logger.w(TAG) { "ring buffer overflow — dropping ${pendingLen} samples" }
                            pendingLen = 0
                        }
                        // Read S16 little-endian samples out of the byte buffer into ShortArray.
                        val byteBuf = java.nio.ByteBuffer.allocate(convertedShorts * BYTES_PER_SAMPLE)
                            .order(java.nio.ByteOrder.nativeOrder())
                        outBuf.position(0L).limit(byteBuf.capacity().toLong())
                            .get(byteBuf.array(), 0, byteBuf.capacity())
                        byteBuf.asShortBuffer().get(pending, pendingLen, convertedShorts)
                        outBuf.deallocate()
                        pendingLen += convertedShorts

                        while (pendingLen >= FRAME_SAMPLES_STEREO) {
                            val frame = pending.copyOfRange(0, FRAME_SAMPLES_STEREO)
                            send(frame)
                            // Shift remaining samples to the front of the ring.
                            val remaining = pendingLen - FRAME_SAMPLES_STEREO
                            if (remaining > 0) {
                                System.arraycopy(
                                    pending,
                                    FRAME_SAMPLES_STEREO,
                                    pending,
                                    0,
                                    remaining,
                                )
                            }
                            pendingLen = remaining
                        }
                    }
                } finally {
                    av_packet_unref(pkt)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(TAG) { "PipeWireAudioReader: ${e.message}" }
        } finally {
            srcFrame?.let { av_frame_free(it) }
            pkt?.let { av_packet_free(it) }
            swr?.let { swr_free(it) }
            decCtx?.let { avcodec_free_context(it) }
            av_channel_layout_uninit(outLayout)
            avformat_close_input(fmtCtx)
        }
        awaitClose { closed.set(true) }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val TAG = "PipeWireAudioReader"
        const val PIPEWIRE_FORMAT = "pipewire"
        const val OUTPUT_SAMPLE_RATE = 48_000
        const val OUTPUT_CHANNELS = 2
        // 20 ms stereo frame: 48000 / 50 = 960 samples per channel, interleaved → 1920 shorts.
        const val FRAME_SAMPLES_STEREO = 1920
        // Ring buffer = 8 frames (160 ms) of headroom before backpressure drop.
        const val BUFFER_FRAMES = 8
        const val BYTES_PER_SAMPLE = 2
    }
}
