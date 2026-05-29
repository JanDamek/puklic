package dev.puklic.voice.screenshare.encoder

import dev.puklic.voice.screenshare.ScreenSource
import dev.puklic.voice.transport.AnnexBSplitter
import dev.puklic.voice.transport.EncodedFrame
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
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avdevice.avdevice_register_all
import org.bytedeco.ffmpeg.global.avformat.av_find_input_format
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_network_init
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EAGAIN
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_opt_set
import org.bytedeco.ffmpeg.global.swscale.SWS_FAST_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.DoublePointer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-process H.264 video encoder using libavformat/libavcodec/libavdevice/libswscale from
 * the JavaCPP FFmpeg GPL bundle. Replaces [FfmpegVideoEncoder]'s subprocess approach
 * (Phase 2 of self-contained Linux refactor — see
 * `docs/03_infrastructure/architect-reports/2026-05-23-self-contained-linux.md` §3).
 *
 * Pipeline:
 *   libavdevice input (avfoundation / pipewire / lavfi testsrc) →
 *   per-stream AVCodec decoder →
 *   AVFrame →
 *   sws_scale to width×height YUV420P →
 *   libx264 encoder (zerolatency, baseline) →
 *   Annex-B AVPacket bytes → split into NAL units → [EncodedFrame]
 *
 * Cancellation: the cold [encode] flow runs the read/decode/encode loop on [Dispatchers.IO];
 * collection cancellation (or [stop]) sets [closed], which exits the loop and frees resources
 * in reverse allocation order.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
public class LibavVideoEncoder(
    private val source: ScreenSource,
    @Suppress("UnusedPrivateMember") private val shareAudio: Boolean,
    private val width: Int = DEFAULT_WIDTH,
    private val height: Int = DEFAULT_HEIGHT,
    private val framerate: Int = DEFAULT_FRAMERATE,
    private val bitrate: Long = DEFAULT_BITRATE,
    /**
     * Test seam: when non-null, overrides platform detection and uses
     * (`inputFormatOverride`, `inputUrlOverride`). Used by tests to plug in `lavfi`.
     */
    private val inputFormatOverride: String? = null,
    private val inputUrlOverride: String? = null,
    /**
     * Linux Wayland only: PipeWire fd handed out by `OpenPipeWireRemote` on the
     * xdg-desktop-portal session. When set, passed to libavdevice as
     * `av_dict_set("fd", "<int>", 0)` so the pipewire demuxer can attach to the portal-allocated
     * remote endpoint rather than the default system PipeWire socket. Ignored on non-Linux.
     */
    private val pipewireFd: Int? = null,
    /**
     * Output codec. Defaults to [VideoCodec.H264] (libx264) for behaviour parity with the
     * pre-VP8 implementation. When set to [VideoCodec.VP8] the encoder produces one
     * `EncodedFrame` per VP8 compressed frame (no NAL split — VP8 frames are atomic);
     * RTP packetisation is the caller's responsibility (RFC 7741).
     */
    private val codec: VideoCodec = VideoCodec.H264,
) : VideoEncoder {

    private val closed = AtomicBoolean(false)

    override fun encode(): Flow<EncodedFrame> = channelFlow {
        avdevice_register_all()
        avformat_network_init()

        val (inputFormatName, inputUrl) = resolveInput()
        val ifmt: AVInputFormat = av_find_input_format(inputFormatName)
            ?: error("libavdevice input format '$inputFormatName' not available")

        val fmtCtx: AVFormatContext = avformat_alloc_context()
            ?: error("avformat_alloc_context returned null")
        val opts = AVDictionary(null)
        av_dict_set(opts, "framerate", framerate.toString(), 0)
        // Some inputs (lavfi) ignore unknown options; that's fine — avformat_open_input
        // silently drops them.
        av_dict_set(opts, "capture_cursor", "1", 0)
        av_dict_set(opts, "pixel_format", "nv12", 0)
        // Portal-allocated PipeWire fd (Linux Wayland). libavdevice's pipewire demuxer reads
        // this option and dup()s the fd; safe to free `opts` afterwards.
        if (pipewireFd != null) {
            av_dict_set(opts, "fd", pipewireFd.toString(), 0)
        }

        val openRc = avformat_open_input(fmtCtx, inputUrl, ifmt, opts)
        av_dict_free(opts)
        if (openRc < 0) error("avformat_open_input('$inputUrl') failed: $openRc")

        var sws: SwsContext? = null
        var decCtx: AVCodecContext? = null
        var encCtx: AVCodecContext? = null
        var srcFrame: AVFrame? = null
        var dstFrame: AVFrame? = null
        var pkt: AVPacket? = null
        var encPkt: AVPacket? = null

        try {
            check(avformat_find_stream_info(fmtCtx, null as AVDictionary?) >= 0) {
                "avformat_find_stream_info failed"
            }
            val videoStreamIdx = (0 until fmtCtx.nb_streams()).firstOrNull { i ->
                fmtCtx.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO
            } ?: error("No video stream in input")
            val srcCodecpar = fmtCtx.streams(videoStreamIdx).codecpar()

            // --- Decoder for the source stream ---
            val decoder: AVCodec = avcodec_find_decoder(srcCodecpar.codec_id())
                ?: error("Decoder for codec_id=${srcCodecpar.codec_id()} not available")
            decCtx = avcodec_alloc_context3(decoder) ?: error("avcodec_alloc_context3(dec) null")
            check(avcodec_parameters_to_context(decCtx, srcCodecpar) >= 0) {
                "avcodec_parameters_to_context failed"
            }
            check(avcodec_open2(decCtx, decoder, null as AVDictionary?) >= 0) {
                "avcodec_open2(decoder) failed"
            }
            val srcW = decCtx.width()
            val srcH = decCtx.height()
            val srcPixFmt = decCtx.pix_fmt()
            check(srcW > 0 && srcH > 0) { "decoder reported invalid size ${srcW}x$srcH" }

            // --- Encoder (libx264 or libvpx, selected per [codec]) ---
            val encoder: AVCodec = avcodec_find_encoder_by_name(codec.encoderName)
                ?: error(
                    "${codec.encoderName} not available in this FFmpeg build " +
                        "(codec=${codec.name}); rebuild ffmpeg-platform-gpl with the codec enabled",
                )
            encCtx = avcodec_alloc_context3(encoder) ?: error("avcodec_alloc_context3(enc) null")
            encCtx.width(width)
            encCtx.height(height)
            encCtx.pix_fmt(AV_PIX_FMT_YUV420P)
            encCtx.time_base().num(1).den(framerate)
            encCtx.framerate().num(framerate).den(1)
            encCtx.gop_size(GOP_SIZE)
            encCtx.max_b_frames(0)
            encCtx.bit_rate(bitrate)
            encCtx.rc_max_rate(bitrate)
            encCtx.rc_buffer_size((bitrate / RC_BUFSIZE_DIVISOR).toInt())

            when (codec) {
                VideoCodec.H264 -> {
                    av_opt_set(encCtx.priv_data(), "preset", "veryfast", 0)
                    av_opt_set(encCtx.priv_data(), "tune", "zerolatency", 0)
                    av_opt_set(encCtx.priv_data(), "profile", "baseline", 0)
                    av_opt_set(encCtx.priv_data(), "x264-params", X264_PARAMS, 0)
                }
                VideoCodec.VP8 -> {
                    // libvpx: CBR, real-time deadline, force keyframes at GOP boundary.
                    // `rc_min_rate` must equal `bit_rate` for true CBR; otherwise libvpx falls
                    // back to its VBR mode.
                    encCtx.rc_min_rate(bitrate)
                    av_opt_set(encCtx.priv_data(), "quality", "realtime", 0)
                    av_opt_set(encCtx.priv_data(), "deadline", "realtime", 0)
                    av_opt_set(encCtx.priv_data(), "cpu-used", VPX_CPU_USED.toString(), 0)
                    av_opt_set(encCtx.priv_data(), "error-resilient", "1", 0)
                }
            }

            check(avcodec_open2(encCtx, encoder, null as AVDictionary?) >= 0) {
                "avcodec_open2(${codec.encoderName}) failed"
            }

            // --- Scaler ---
            sws = sws_getContext(
                srcW, srcH, srcPixFmt,
                width, height, AV_PIX_FMT_YUV420P,
                SWS_FAST_BILINEAR, null, null, null as DoublePointer?,
            ) ?: error("sws_getContext returned null")

            srcFrame = av_frame_alloc() ?: error("av_frame_alloc(src) null")
            dstFrame = av_frame_alloc()?.apply {
                width(this@LibavVideoEncoder.width)
                height(this@LibavVideoEncoder.height)
                format(AV_PIX_FMT_YUV420P)
            } ?: error("av_frame_alloc(dst) null")
            check(av_frame_get_buffer(dstFrame, 0) >= 0) { "av_frame_get_buffer(dst) failed" }

            pkt = av_packet_alloc() ?: error("av_packet_alloc(read) null")
            encPkt = av_packet_alloc() ?: error("av_packet_alloc(enc) null")

            var pts = 0L

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
                if (pkt.stream_index() != videoStreamIdx) {
                    av_packet_unref(pkt)
                    continue
                }

                val sendPktRc = avcodec_send_packet(decCtx, pkt)
                av_packet_unref(pkt)
                if (sendPktRc < 0 && sendPktRc != AVERROR_EAGAIN()) {
                    error("avcodec_send_packet(decoder) failed: $sendPktRc")
                }

                // Drain decoded frames
                decodeLoop@ while (!closed.get() && !isClosedForSend) {
                    val recvFrameRc = avcodec_receive_frame(decCtx, srcFrame)
                    if (recvFrameRc == AVERROR_EAGAIN() || recvFrameRc == AVERROR_EOF()) break@decodeLoop
                    if (recvFrameRc < 0) error("avcodec_receive_frame failed: $recvFrameRc")

                    sws_scale(
                        sws,
                        srcFrame.data(), srcFrame.linesize(), 0, srcH,
                        dstFrame.data(), dstFrame.linesize(),
                    )
                    dstFrame.pts(pts)
                    pts++

                    val sendFrameRc = avcodec_send_frame(encCtx, dstFrame)
                    if (sendFrameRc < 0) error("avcodec_send_frame(encoder) failed: $sendFrameRc")

                    // Drain encoded packets
                    encDrain@ while (!closed.get() && !isClosedForSend) {
                        val recvPktRc = avcodec_receive_packet(encCtx, encPkt)
                        if (recvPktRc == AVERROR_EAGAIN() || recvPktRc == AVERROR_EOF()) break@encDrain
                        if (recvPktRc < 0) error("avcodec_receive_packet(encoder) failed: $recvPktRc")

                        emitEncodedPacket(encPkt, pts)
                        av_packet_unref(encPkt)
                    }
                }
            }

            // Flush encoder if we exited cleanly (not on cancel) — drain any buffered frames.
            if (!closed.get() && !isClosedForSend) {
                avcodec_send_frame(encCtx, null as AVFrame?)
                while (!isClosedForSend) {
                    val recvPktRc = avcodec_receive_packet(encCtx, encPkt)
                    if (recvPktRc == AVERROR_EAGAIN() || recvPktRc == AVERROR_EOF()) break
                    if (recvPktRc < 0) break
                    emitEncodedPacket(encPkt, pts)
                    av_packet_unref(encPkt)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            pkt?.let { av_packet_free(it) }
            encPkt?.let { av_packet_free(it) }
            srcFrame?.let { av_frame_free(it) }
            dstFrame?.let { av_frame_free(it) }
            sws?.let { sws_freeContext(it) }
            encCtx?.let { avcodec_free_context(it) }
            decCtx?.let { avcodec_free_context(it) }
            avformat_close_input(fmtCtx)
        }

        awaitClose { closed.set(true) }
    }.flowOn(Dispatchers.IO)

    /**
     * Emit an encoded packet.
     *
     * H.264: split Annex-B byte stream into NAL units, emitting one [EncodedFrame] per NAL.
     * Keyframe is detected from the NAL type (5 = IDR).
     *
     * VP8: emit the whole packet as a single [EncodedFrame] (VP8 compressed frames are
     * atomic — RFC 7741 RTP packetisation happens later in the send pipeline). Keyframe
     * comes from `AVPacket.flags & AV_PKT_FLAG_KEY`.
     */
    private suspend fun kotlinx.coroutines.channels.ProducerScope<EncodedFrame>.emitEncodedPacket(
        encPkt: AVPacket,
        framePts: Long,
    ) {
        val size = encPkt.size()
        if (size <= 0) return
        val bytes = ByteArray(size)
        encPkt.data().capacity(size.toLong()).get(bytes)
        val ts90k = (framePts * TS_CLOCK_HZ / framerate).toInt()
        when (codec) {
            VideoCodec.H264 -> {
                for (nal in AnnexBSplitter.split(bytes)) {
                    if (nal.isEmpty()) continue
                    val nalType = nal[0].toInt() and NAL_TYPE_MASK
                    val keyframe = nalType == NAL_TYPE_IDR
                    send(EncodedFrame(nal, ts90k, keyframe))
                }
            }
            VideoCodec.VP8 -> {
                val keyframe = (encPkt.flags() and AV_PKT_FLAG_KEY) != 0
                send(EncodedFrame(bytes, ts90k, keyframe))
            }
        }
    }

    override suspend fun stop() {
        closed.set(true)
    }

    private fun resolveInput(): Pair<String, String> {
        if (inputFormatOverride != null && inputUrlOverride != null) {
            return inputFormatOverride to inputUrlOverride
        }
        return resolveInputForOs(System.getProperty("os.name").orEmpty(), source.id)
    }

    public companion object {
        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
        const val DEFAULT_FRAMERATE = 30
        const val DEFAULT_BITRATE = 2_500_000L
        const val GOP_SIZE = 60
        const val RC_BUFSIZE_DIVISOR = 2
        const val TS_CLOCK_HZ = 90_000
        const val X264_PARAMS = "keyint=60:min-keyint=60:scenecut=0:repeat-headers=1"
        // libvpx realtime quality preset: 0 = highest quality / slowest, 16 = lowest /
        // fastest. 5 matches WebRTC's default for screen capture (good quality, low CPU).
        const val VPX_CPU_USED = 5
        // H.264 NAL header
        const val NAL_TYPE_MASK = 0x1F
        const val NAL_TYPE_IDR = 5
    }
}

/**
 * Pure mapping of `(os.name, ScreenSource.id) → (libavdevice input format, libavdevice URL)`.
 *
 * Extracted as a top-level function so it can be unit-tested without spinning up FFmpeg.
 * - macOS uses the `avfoundation` libavdevice demuxer; the URL is `"<video>:<audio>"`.
 * - Linux Wayland uses the `pipewire` libavdevice demuxer; the URL is the PipeWire node id
 *   (a numeric string) handed out by `org.freedesktop.portal.ScreenCast.Start` → first stream.
 *   The portal-allocated fd is passed separately via `av_dict_set("fd", ...)`, not via the URL.
 *
 * Windows and macOS-x86_64 are intentionally unsupported (see `CLAUDE.md §Platforms`).
 */
public fun resolveInputForOs(osName: String, sourceId: String): Pair<String, String> = when {
    osName.startsWith("Mac") -> "avfoundation" to "$sourceId:none"
    osName.contains("Linux", ignoreCase = true) -> "pipewire" to sourceId
    else -> error("Screen capture on '$osName' is not supported by LibavVideoEncoder")
}

/** Convenience constructor — analogous to [ffmpegVideoEncoder]. */
public fun libavVideoEncoder(
    source: ScreenSource,
    shareAudio: Boolean,
    codec: VideoCodec = VideoCodec.H264,
): VideoEncoder = LibavVideoEncoder(source = source, shareAudio = shareAudio, codec = codec)

/** Linux-only convenience constructor that injects the portal-allocated PipeWire fd. */
public fun libavVideoEncoder(
    source: ScreenSource,
    shareAudio: Boolean,
    pipewireFd: Int,
    codec: VideoCodec = VideoCodec.H264,
): VideoEncoder = LibavVideoEncoder(
    source = source,
    shareAudio = shareAudio,
    pipewireFd = pipewireFd,
    codec = codec,
)
