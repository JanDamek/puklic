package dev.puklic.screencast.macos

import dev.puklic.voice.codec.video.H264Encoder
import dev.puklic.voice.transport.AnnexBSplitter
import dev.puklic.voice.transport.EncodedFrame
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EAGAIN
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_opt_set
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Push-frame H.264 encoder backed by libx264 from the JavaCPP
 * ffmpeg-platform-gpl bundle. Implements the KMP-clean [H264Encoder]
 * contract so platform raw-frame producers — currently
 * [MacScreenCapture] for ScreenCaptureKit BGRA, [WindowsScreenCapture]
 * for DXGI BGRA — share one encoder type.
 *
 * Unlike the source-driven
 * `dev.puklic.voice.screenshare.encoder.LibavVideoEncoder`, this class owns
 * neither libavformat nor libavdevice — its only job is `AVFrame in → AVPacket out`.
 * Settings (`preset = veryfast`, `tune = zerolatency`, `profile = baseline`,
 * GOP = 60, no B-frames) match `LibavVideoEncoder` so on-wire compatibility
 * with Discord is preserved across encoders.
 *
 * Lifecycle: stateful (DPB, rate-control). [close] frees the codec
 * context. Calling [encode] after [close] throws.
 */
internal class LibavPushH264Encoder(
    private val width: Int,
    private val height: Int,
    private val bitrateKbps: Int,
    private val framerate: Int,
) : H264Encoder {

    private val closed = AtomicBoolean(false)
    private var pts: Long = 0

    private var encCtx: AVCodecContext? = null
    private var frame: AVFrame? = null
    private var pkt: AVPacket? = null
    private val pendingNals: ArrayDeque<EncodedFrame> = ArrayDeque()

    init {
        openEncoder()
    }

    private fun openEncoder() {
        val codec: AVCodec = avcodec_find_encoder_by_name(LibavX264Settings.ENCODER_NAME)
            ?: error("libx264 not available in the bundled FFmpeg build")
        val ctx = avcodec_alloc_context3(codec) ?: error("avcodec_alloc_context3 returned null")
        ctx.width(width)
        ctx.height(height)
        ctx.pix_fmt(AV_PIX_FMT_YUV420P)
        ctx.time_base().num(1).den(framerate)
        ctx.framerate().num(framerate).den(1)
        ctx.gop_size(LibavX264Settings.GOP_SIZE)
        ctx.max_b_frames(0)
        val bitrate = bitrateKbps.toLong() * BITS_PER_KBIT
        ctx.bit_rate(bitrate)
        ctx.rc_max_rate(bitrate)
        ctx.rc_buffer_size((bitrate / LibavX264Settings.RC_BUFSIZE_DIVISOR).toInt())
        av_opt_set(ctx.priv_data(), "preset", LibavX264Settings.PRESET, 0)
        av_opt_set(ctx.priv_data(), "tune", LibavX264Settings.TUNE, 0)
        av_opt_set(ctx.priv_data(), "profile", LibavX264Settings.PROFILE, 0)
        av_opt_set(ctx.priv_data(), "x264-params", LibavX264Settings.X264_PARAMS, 0)
        check(avcodec_open2(ctx, codec, null as AVDictionary?) >= 0) {
            "avcodec_open2(${LibavX264Settings.ENCODER_NAME}) failed"
        }
        encCtx = ctx

        val f = av_frame_alloc() ?: error("av_frame_alloc returned null")
        f.width(width)
        f.height(height)
        f.format(AV_PIX_FMT_YUV420P)
        check(av_frame_get_buffer(f, 0) >= 0) { "av_frame_get_buffer failed" }
        frame = f

        pkt = av_packet_alloc() ?: error("av_packet_alloc returned null")
    }

    override fun encode(yuv420p: ByteArray): EncodedFrame? {
        check(!closed.get()) { "LibavPushH264Encoder is closed" }
        // Drain previously-emitted NALs first — a single AVPacket can contain
        // SPS + PPS + IDR at keyframe boundaries. Caller polls back-to-back
        // via the cold flow loop in MacScreenCapture, passing an empty array
        // to drain without re-encoding.
        pendingNals.removeFirstOrNull()?.let { return it }
        if (yuv420p.isEmpty()) return null

        val ctx = encCtx ?: error("encoder not initialised")
        val f = frame ?: error("frame not allocated")
        val p = pkt ?: error("packet not allocated")
        val ySize = width * height
        val uSize = ySize / 4
        require(yuv420p.size >= ySize + uSize + uSize) {
            "yuv420p too small: ${yuv420p.size} < ${ySize + uSize + uSize}"
        }

        // Copy planes respecting libavutil's row stride (`linesize`).
        copyPlane(yuv420p, 0, f, planeIndex = 0, planeWidth = width, planeHeight = height)
        copyPlane(yuv420p, ySize, f, planeIndex = 1, planeWidth = width / 2, planeHeight = height / 2)
        copyPlane(yuv420p, ySize + uSize, f, planeIndex = 2, planeWidth = width / 2, planeHeight = height / 2)

        f.pts(pts)
        val framePts = pts
        pts++

        val sendRc = avcodec_send_frame(ctx, f)
        if (sendRc < 0 && sendRc != AVERROR_EAGAIN()) {
            error("avcodec_send_frame failed: $sendRc")
        }

        while (true) {
            val recvRc = avcodec_receive_packet(ctx, p)
            if (recvRc == AVERROR_EAGAIN() || recvRc == AVERROR_EOF()) break
            if (recvRc < 0) error("avcodec_receive_packet failed: $recvRc")
            val size = p.size()
            if (size > 0) {
                val bytes = ByteArray(size)
                p.data().capacity(size.toLong()).get(bytes)
                val ts90k = (framePts * LibavX264Settings.TS_CLOCK_HZ / framerate).toInt()
                val packetIsKey = (p.flags() and AV_PKT_FLAG_KEY) != 0
                for (nal in AnnexBSplitter.split(bytes)) {
                    if (nal.isEmpty()) continue
                    val nalType = nal[0].toInt() and LibavX264Settings.NAL_TYPE_MASK
                    val nalIsIdr = nalType == LibavX264Settings.NAL_TYPE_IDR
                    pendingNals.addLast(EncodedFrame(nal, ts90k, packetIsKey || nalIsIdr))
                }
            }
            av_packet_unref(p)
        }

        return pendingNals.removeFirstOrNull()
    }

    private fun copyPlane(
        src: ByteArray,
        srcOffset: Int,
        frame: AVFrame,
        planeIndex: Int,
        planeWidth: Int,
        planeHeight: Int,
    ) {
        val data = frame.data(planeIndex)
        val stride = frame.linesize(planeIndex)
        if (stride == planeWidth) {
            data.capacity((planeWidth * planeHeight).toLong())
                .put(src, srcOffset, planeWidth * planeHeight)
        } else {
            for (row in 0 until planeHeight) {
                data.position((row.toLong() * stride))
                    .capacity((row.toLong() * stride) + planeWidth)
                    .put(src, srcOffset + row * planeWidth, planeWidth)
            }
            data.position(0)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pkt?.let { av_packet_free(it) }
        frame?.let { av_frame_free(it) }
        encCtx?.let { avcodec_free_context(it) }
        pkt = null
        frame = null
        encCtx = null
        pendingNals.clear()
    }

    companion object {
        const val BITS_PER_KBIT = 1000L
    }
}

/**
 * Centralised libx264 + Annex-B constants shared by [LibavPushH264Encoder] and the
 * source-driven `dev.puklic.voice.screenshare.encoder.LibavVideoEncoder` so on-wire
 * encoder behaviour stays in lockstep regardless of capture origin.
 */
internal object LibavX264Settings {
    const val ENCODER_NAME = "libx264"
    const val PRESET = "veryfast"
    const val TUNE = "zerolatency"
    const val PROFILE = "baseline"
    const val GOP_SIZE = 60
    const val RC_BUFSIZE_DIVISOR = 2
    const val TS_CLOCK_HZ = 90_000L
    const val X264_PARAMS = "keyint=60:min-keyint=60:scenecut=0:repeat-headers=1"
    const val NAL_TYPE_MASK = 0x1F
    const val NAL_TYPE_IDR = 5
}
