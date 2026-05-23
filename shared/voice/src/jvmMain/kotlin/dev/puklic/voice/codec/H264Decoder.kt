package dev.puklic.voice.codec

import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EAGAIN
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-process H.264 decoder backed by libavcodec (JavaCPP bundled). Phase 4.2 — receive side
 * of screenshare.
 *
 * Accepts Annex-B framed access units (`00 00 00 01 NAL …`, possibly multiple NALs
 * concatenated) and emits decoded ARGB frames as [DecodedFrame] (RGBA byte layout suitable
 * for Compose `BufferedImage(TYPE_INT_ARGB).setRGB`).
 *
 * Lifecycle: one decoder per remote SSRC. Not thread-safe; the owning pipeline coroutine
 * must serialise calls. [close] is idempotent.
 */
internal class H264Decoder {

    private val codec: AVCodec
    private val ctx: AVCodecContext
    private val packet: AVPacket
    private val frame: AVFrame
    private val rgbFrame: AVFrame
    private var sws: SwsContext? = null
    private var rgbBuffer: BytePointer? = null
    private var rgbStride: Int = 0
    private var allocatedWidth: Int = 0
    private var allocatedHeight: Int = 0
    private val closed = AtomicBoolean(false)

    init {
        codec = avcodec_find_decoder(AV_CODEC_ID_H264)
            ?: throw H264DecoderException("H.264 decoder not available in this FFmpeg build")
        val allocated = avcodec_alloc_context3(codec)
            ?: throw H264DecoderException("avcodec_alloc_context3 returned null")
        ctx = allocated
        val rc = avcodec_open2(ctx, codec, null as AVDictionary?)
        if (rc < 0) {
            avcodec_free_context(ctx)
            throw H264DecoderException("avcodec_open2(h264) failed: $rc")
        }
        packet = av_packet_alloc() ?: run {
            avcodec_free_context(ctx)
            throw H264DecoderException("av_packet_alloc returned null")
        }
        frame = av_frame_alloc() ?: run {
            av_packet_free(packet)
            avcodec_free_context(ctx)
            throw H264DecoderException("av_frame_alloc returned null")
        }
        rgbFrame = av_frame_alloc() ?: run {
            av_frame_free(frame)
            av_packet_free(packet)
            avcodec_free_context(ctx)
            throw H264DecoderException("av_frame_alloc rgb returned null")
        }
    }

    /**
     * Decode one Annex-B access unit. Returns the produced frame, or null if the decoder
     * needs more input (typical for the first few packets before SPS/PPS are seen).
     */
    fun decode(annexB: ByteArray): DecodedFrame? {
        check(!closed.get()) { "H264Decoder is closed" }
        if (annexB.isEmpty()) return null

        val data = BytePointer(annexB.size.toLong())
        data.put(annexB, 0, annexB.size)
        packet.data(data)
        packet.size(annexB.size)
        val sendRc = avcodec_send_packet(ctx, packet)
        av_packet_unref(packet)
        if (sendRc < 0 && sendRc != AVERROR_EAGAIN()) {
            // Codec couldn't ingest the packet; common during startup. Drop silently.
            return null
        }

        val recvRc = avcodec_receive_frame(ctx, frame)
        if (recvRc == AVERROR_EAGAIN() || recvRc == AVERROR_EOF()) return null
        if (recvRc < 0) return null

        val w = frame.width()
        val h = frame.height()
        if (w <= 0 || h <= 0) return null

        ensureRgbBuffer(w, h)
        val pixFmt = frame.format()
        val sc = sws_getContext(
            w, h, pixFmt,
            w, h, AV_PIX_FMT_RGBA,
            SWS_BILINEAR,
            null, null, null as DoublePointer?,
        ) ?: return null
        // Re-create sws context when frame size/format changes — cheap enough; we don't
        // currently cache the (w,h,format) tuple. If shown to matter, cache + diff later.
        sws?.let { sws_freeContext(it) }
        sws = sc

        val srcSlice = frame.data()
        val srcStride = frame.linesize()
        sws_scale(sc, srcSlice, srcStride, 0, h, rgbFrame.data(), rgbFrame.linesize())

        val rgba = ByteArray(w * h * BYTES_PER_PIXEL)
        val ptr = rgbBuffer ?: return null
        // linesize[0] may be > w*4 (alignment). Copy row by row from the BytePointer we own.
        val rowBytes = w * BYTES_PER_PIXEL
        val row = ByteArray(rowBytes)
        for (y in 0 until h) {
            ptr.position(y.toLong() * rgbStride.toLong()).get(row, 0, rowBytes)
            row.copyInto(rgba, destinationOffset = y * rowBytes)
        }
        return DecodedFrame(rgba, w, h)
    }

    private fun ensureRgbBuffer(w: Int, h: Int) {
        if (w == allocatedWidth && h == allocatedHeight && rgbBuffer != null) return
        rgbBuffer?.deallocate()
        // RGBA stride aligned to 32 bytes per FFmpeg convention.
        val stride = ((w * BYTES_PER_PIXEL + STRIDE_ALIGN - 1) / STRIDE_ALIGN) * STRIDE_ALIGN
        val size = stride.toLong() * h.toLong()
        val buf = BytePointer(size)
        rgbBuffer = buf
        rgbStride = stride
        allocatedWidth = w
        allocatedHeight = h
        rgbFrame.width(w)
        rgbFrame.height(h)
        rgbFrame.format(AV_PIX_FMT_RGBA)
        rgbFrame.data(0, buf)
        rgbFrame.linesize(0, stride)
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            sws?.let { sws_freeContext(it) }
            sws = null
            av_frame_free(rgbFrame)
            av_frame_free(frame)
            av_packet_free(packet)
            avcodec_free_context(ctx)
            rgbBuffer?.deallocate()
            rgbBuffer = null
        }
    }

    /** Decoded RGBA frame: `rgba.size == width * height * 4`, layout R G B A per pixel. */
    data class DecodedFrame(val rgba: ByteArray, val width: Int, val height: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecodedFrame) return false
            return width == other.width && height == other.height && rgba.contentEquals(other.rgba)
        }

        override fun hashCode(): Int {
            var h = width
            h = 31 * h + height
            h = 31 * h + rgba.contentHashCode()
            return h
        }
    }

    companion object {
        private const val BYTES_PER_PIXEL = 4
        private const val STRIDE_ALIGN = 32
    }
}

internal class H264DecoderException(message: String) : RuntimeException(message)
