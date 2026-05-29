package dev.puklic.voice.screenshare.encoder

import dev.puklic.voice.codec.video.H264Encoder
import dev.puklic.voice.codec.video.H264EncoderFactory
import dev.puklic.voice.transport.AnnexBSplitter
import dev.puklic.voice.transport.EncodedFrame
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
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
import org.bytedeco.ffmpeg.global.avutil.av_image_fill_arrays
import org.bytedeco.ffmpeg.global.avutil.av_opt_set
import org.bytedeco.javacpp.BytePointer
import java.util.ArrayDeque

/**
 * Push-frame H.264 encoder backed by libx264 from the JavaCPP FFmpeg-GPL bundle.
 *
 * Companion to [LibavVideoEncoder] which is *source-driven* (pulls frames from a libavdevice
 * input). [JvmLibavH264Encoder] is the *push-driven* half — the caller hands in YUV420P
 * pixels per call. Used by the Windows DXGI Output Duplication capture path (FP-9) and,
 * later, the macOS ScreenCaptureKit path (FP-8) where the OS pushes pre-decoded surfaces.
 *
 * The libx264 configuration mirrors [LibavVideoEncoder] verbatim
 * (`preset=veryfast`, `tune=zerolatency`, `profile=baseline`,
 * `x264-params=keyint=60:min-keyint=60:scenecut=0:repeat-headers=1`, GOP=60, B-frames=0)
 * so Discord clients see the same NAL-unit shape across Linux source-driven and Windows
 * push-driven captures.
 *
 * Per the [H264Encoder.encode] contract, this class returns one Annex-B NAL unit per call
 * with the start code stripped. libx264 typically emits multiple NALs per frame (SPS / PPS /
 * IDR for keyframes; one slice NAL for P-frames); excess NALs are queued in [pendingNals]
 * and drained by subsequent `encode(...)` invocations before the next libx264 push.
 *
 * Lifecycle: constructor allocates the codec context, codec, internal reusable AVFrame
 * (sized once to width×height YUV420P), and AVPacket. [close] frees all in reverse order.
 * The class is not thread-safe — callers must serialise access (the screencast capture
 * loops are single-threaded by construction).
 *
 * @param width frame width in pixels (must match every input buffer's logical width)
 * @param height frame height in pixels
 * @param bitrateKbps target output bitrate in kilobits per second
 * @param framerate target frames per second; sets `time_base` and GOP cadence
 */
@Suppress("LongMethod")
public class JvmLibavH264Encoder(
    private val width: Int,
    private val height: Int,
    private val bitrateKbps: Int,
    private val framerate: Int,
) : H264Encoder {

    private val encCtx: AVCodecContext
    private val pkt: AVPacket
    private val frame: AVFrame
    private val frameBuffer: BytePointer
    private val pendingNals: ArrayDeque<EncodedFrame> = ArrayDeque()
    private var nextPts: Long = 0
    private var closed = false

    init {
        require(width > 0 && height > 0) { "width/height must be positive, got ${width}x$height" }
        require(bitrateKbps > 0) { "bitrateKbps must be positive, got $bitrateKbps" }
        require(framerate > 0) { "framerate must be positive, got $framerate" }
        val expectedSize = width.toLong() * height * BYTES_PER_YUV420_PIXEL_NUM / BYTES_PER_YUV420_PIXEL_DEN

        val encoder: AVCodec = avcodec_find_encoder_by_name(ENCODER_NAME)
            ?: error(
                "$ENCODER_NAME not available in this FFmpeg build; " +
                    "rebuild ffmpeg-platform-gpl with libx264 enabled",
            )
        encCtx = avcodec_alloc_context3(encoder)
            ?: error("avcodec_alloc_context3($ENCODER_NAME) returned null")
        encCtx.width(width)
        encCtx.height(height)
        encCtx.pix_fmt(AV_PIX_FMT_YUV420P)
        encCtx.time_base().num(1).den(framerate)
        encCtx.framerate().num(framerate).den(1)
        encCtx.gop_size(GOP_SIZE)
        encCtx.max_b_frames(0)
        val bitsPerSecond = bitrateKbps.toLong() * BITS_PER_KBIT
        encCtx.bit_rate(bitsPerSecond)
        encCtx.rc_max_rate(bitsPerSecond)
        encCtx.rc_buffer_size((bitsPerSecond / RC_BUFSIZE_DIVISOR).toInt())
        av_opt_set(encCtx.priv_data(), "preset", "veryfast", 0)
        av_opt_set(encCtx.priv_data(), "tune", "zerolatency", 0)
        av_opt_set(encCtx.priv_data(), "profile", "baseline", 0)
        av_opt_set(encCtx.priv_data(), "x264-params", X264_PARAMS, 0)

        val openRc = avcodec_open2(encCtx, encoder, null as AVDictionary?)
        if (openRc < 0) {
            avcodec_free_context(encCtx)
            error("avcodec_open2($ENCODER_NAME) failed: $openRc")
        }

        // Reusable input frame. Allocate the pixel buffer ourselves rather than via
        // av_frame_get_buffer so we can repoint the planes at the caller's ByteArray per
        // encode() without reallocating each call.
        frame = av_frame_alloc() ?: run {
            avcodec_free_context(encCtx)
            error("av_frame_alloc returned null")
        }
        frame.width(width)
        frame.height(height)
        frame.format(AV_PIX_FMT_YUV420P)
        val bufSize = expectedSize.toInt()
        frameBuffer = BytePointer(bufSize.toLong())
        val fillRc = av_image_fill_arrays(
            frame.data(), frame.linesize(),
            frameBuffer, AV_PIX_FMT_YUV420P, width, height, 1,
        )
        if (fillRc < 0) {
            frameBuffer.deallocate()
            av_frame_free(frame)
            avcodec_free_context(encCtx)
            error("av_image_fill_arrays failed: $fillRc")
        }

        pkt = av_packet_alloc() ?: run {
            frameBuffer.deallocate()
            av_frame_free(frame)
            avcodec_free_context(encCtx)
            error("av_packet_alloc returned null")
        }
    }

    /**
     * Encode one YUV420P frame.
     *
     * @param yuv420p tightly packed Y-plane (width×height) followed by U and V planes
     *   (each width/2 × height/2). Total size MUST equal `width * height * 3 / 2`.
     * @return one Annex-B NAL unit (start code stripped) or `null` when libx264 buffered
     *   the input without emitting output yet.
     */
    override fun encode(yuv420p: ByteArray): EncodedFrame? {
        check(!closed) { "JvmLibavH264Encoder.encode after close" }
        pendingNals.pollFirst()?.let { return it }

        val expected = width * height * BYTES_PER_YUV420_PIXEL_NUM / BYTES_PER_YUV420_PIXEL_DEN
        require(yuv420p.size == expected) {
            "yuv420p size=${yuv420p.size} doesn't match ${width}x$height YUV420P expected=$expected"
        }

        // Copy caller bytes into the reusable native buffer; frame.data()/linesize() were
        // configured to point at frameBuffer in init.
        frameBuffer.position(0L).put(yuv420p, 0, yuv420p.size)
        frame.pts(nextPts)
        nextPts++

        val sendRc = avcodec_send_frame(encCtx, frame)
        if (sendRc < 0 && sendRc != AVERROR_EAGAIN()) {
            error("avcodec_send_frame failed: $sendRc")
        }

        while (true) {
            val recvRc = avcodec_receive_packet(encCtx, pkt)
            if (recvRc == AVERROR_EAGAIN() || recvRc == AVERROR_EOF()) break
            if (recvRc < 0) error("avcodec_receive_packet failed: $recvRc")
            val size = pkt.size()
            if (size > 0) {
                val bytes = ByteArray(size)
                pkt.data().capacity(size.toLong()).get(bytes)
                val ts90k = (pkt.pts() * TS_CLOCK_HZ / framerate).toInt()
                for (nal in AnnexBSplitter.split(bytes)) {
                    if (nal.isEmpty()) continue
                    val nalType = nal[0].toInt() and NAL_TYPE_MASK
                    val isKeyframe = nalType == NAL_TYPE_IDR
                    pendingNals.addLast(EncodedFrame(nal, ts90k, isKeyframe))
                }
            }
            av_packet_unref(pkt)
        }
        return pendingNals.pollFirst()
    }

    /**
     * Drain one already-buffered NAL unit without pushing a new input frame. Returns
     * `null` when the internal queue is empty.
     *
     * libx264 emits multiple NAL units per encoded packet on keyframes (SPS + PPS + IDR).
     * The single-NAL [encode] return contract forces them to be queued; callers driving a
     * high-framerate capture loop should drain via [drainPending] after a non-null
     * [encode] result to flush the queue before pushing the next YUV frame.
     */
    public fun drainPending(): EncodedFrame? = if (closed) null else pendingNals.pollFirst()

    override fun close() {
        if (closed) return
        closed = true
        av_packet_free(pkt)
        av_frame_free(frame)
        frameBuffer.deallocate()
        avcodec_free_context(encCtx)
        pendingNals.clear()
    }

    public companion object {
        public const val ENCODER_NAME: String = "libx264"
        public const val GOP_SIZE: Int = 60
        public const val RC_BUFSIZE_DIVISOR: Int = 2
        public const val TS_CLOCK_HZ: Int = 90_000
        public const val BITS_PER_KBIT: Int = 1_000
        public const val X264_PARAMS: String = "keyint=60:min-keyint=60:scenecut=0:repeat-headers=1"
        public const val NAL_TYPE_MASK: Int = 0x1F
        public const val NAL_TYPE_IDR: Int = 5
        // YUV 4:2:0 has 1 + 1/4 + 1/4 = 1.5 bytes per pixel → numerator 3, denominator 2.
        public const val BYTES_PER_YUV420_PIXEL_NUM: Int = 3
        public const val BYTES_PER_YUV420_PIXEL_DEN: Int = 2
    }
}

/**
 * [H264EncoderFactory] producing [JvmLibavH264Encoder] instances. Used by Windows
 * (and, transitively, macOS) screen capture wiring on JVM desktop where no platform
 * hardware encoder is wired up yet.
 */
public object JvmLibavH264EncoderFactory : H264EncoderFactory {
    override fun create(width: Int, height: Int, bitrateKbps: Int, framerate: Int): H264Encoder =
        JvmLibavH264Encoder(width, height, bitrateKbps, framerate)
}
