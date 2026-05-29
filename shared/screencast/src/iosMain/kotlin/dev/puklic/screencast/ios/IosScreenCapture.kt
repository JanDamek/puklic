package dev.puklic.screencast.ios

import kotlin.concurrent.Volatile
import dev.puklic.screencast.H264EncoderFactory
import dev.puklic.screencast.ScreenCapture
import dev.puklic.voice.codec.video.H264Encoder
import dev.puklic.voice.transport.EncodedFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * iOS [ScreenCapture] actual. Wires the FP-11 [BroadcastExtensionBridge] (BGRA frames + Float32
 * PCM out of the ReplayKit Broadcast Upload Extension) into the FP-5 [H264Encoder]
 * (VideoToolbox `VTCompressionSession`) producing [EncodedFrame]s for the existing voice
 * screencast RTP path.
 *
 * Pipeline coroutine (scoped to `scope`, rooted in `Dispatchers.Default`):
 *  1. Collect [bridge].videoFrames().
 *  2. Lazily instantiate [H264Encoder] when the first frame arrives — width/height are
 *     reported per-frame by the extension; the synthetic source's `widthPx/heightPx` are 0.
 *  3. Convert BGRA → I420 via [BgraToYuv420] (BT.601 limited-range, hand-rolled).
 *  4. [H264Encoder.encode]; emit non-null [EncodedFrame] to the video channel.
 *
 * Audio pipeline (only when [shareAudio] is true):
 *  1. Collect [bridge].audioFrames().
 *  2. Convert interleaved Float32 → S16 with `(clamp * Short.MAX_VALUE).toInt().toShort()`.
 *  3. Emit `ShortArray` on the audio channel.
 *
 * Backpressure: drop-oldest channel on both legs — matches the upstream extension ring
 * buffer policy (FP-11 §6) so a slow encoder consumer drops the oldest pending frame
 * rather than blocking the producer.
 *
 * [close] is idempotent: cancels the scope, closes the encoder ( `VTCompressionSession`)
 * and both channels.
 */
public class IosScreenCapture internal constructor(
    private val shareAudio: Boolean,
    private val h264EncoderFactory: H264EncoderFactory,
    private val bridge: BroadcastExtensionBridge = BroadcastExtensionBridge(),
) : ScreenCapture {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val videoChannel = Channel<EncodedFrame>(
        capacity = VIDEO_CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val audioChannel: Channel<ShortArray>? = if (shareAudio) {
        Channel(capacity = AUDIO_CHANNEL_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    } else {
        null
    }

    private var encoder: H264Encoder? = null
    private var converter: BgraToYuv420? = null
    private var yuvBuffer: ByteArray = EMPTY_BYTES

    @Volatile private var closed: Boolean = false

    init {
        scope.launch { videoPipeline() }
        if (shareAudio) {
            scope.launch { audioPipeline() }
        }
    }

    public override val frames: Flow<EncodedFrame> = videoChannel.consumeAsFlow()

    public override val audio: Flow<ShortArray>? = audioChannel?.let { ch ->
        flow {
            for (chunk in ch) emit(chunk)
        }
    }

    public override fun close() {
        if (closed) return
        closed = true
        videoChannel.close()
        audioChannel?.close()
        scope.cancel()
        encoder?.close()
        encoder = null
        converter = null
        yuvBuffer = EMPTY_BYTES
    }

    private suspend fun videoPipeline() {
        bridge.videoFrames().collect { bgra ->
            val w = trimToEven(bgra.width)
            val h = trimToEven(bgra.height)
            if (w == 0 || h == 0) return@collect
            val enc = ensureEncoder()
            val conv = ensureConverter(w, h)
            if (yuvBuffer.size != conv.yuvSize) {
                yuvBuffer = ByteArray(conv.yuvSize)
            }
            conv.convert(
                bgra = bgra.data,
                sourceBytesPerRow = bgra.bytesPerRow.coerceAtLeast(w * BGRA_BYTES_PER_PIXEL),
                dst = yuvBuffer,
            )
            val encoded = enc.encode(yuvBuffer) ?: return@collect
            videoChannel.trySend(encoded)
        }
    }

    private suspend fun audioPipeline() {
        val sink = audioChannel ?: return
        bridge.audioFrames().collect { chunk ->
            val payload = chunk.floatPayload
            val sampleCount = payload.size / FLOAT_BYTES_PER_SAMPLE
            if (sampleCount <= 0) return@collect
            val pcm = ShortArray(sampleCount)
            var byteOff = 0
            for (i in 0 until sampleCount) {
                val bits = (payload[byteOff].toInt() and 0xFF) or
                    ((payload[byteOff + 1].toInt() and 0xFF) shl 8) or
                    ((payload[byteOff + 2].toInt() and 0xFF) shl 16) or
                    ((payload[byteOff + 3].toInt() and 0xFF) shl 24)
                val f = Float.fromBits(bits).coerceIn(-1.0f, 1.0f)
                pcm[i] = (f * Short.MAX_VALUE).toInt().toShort()
                byteOff += FLOAT_BYTES_PER_SAMPLE
            }
            sink.trySend(pcm)
        }
    }

    private fun ensureEncoder(): H264Encoder {
        encoder?.let { return it }
        val created = h264EncoderFactory()
        encoder = created
        return created
    }

    private fun ensureConverter(width: Int, height: Int): BgraToYuv420 {
        val existing = converter
        if (existing != null && existing.matches(width, height)) return existing
        existing?.let { /* dimensions changed mid-stream — rare; replace */ }
        val created = BgraToYuv420(width, height)
        converter = created
        return created
    }

    private fun BgraToYuv420.matches(width: Int, height: Int): Boolean =
        yuvSize == width * height * 3 / 2

    private companion object {
        const val VIDEO_CHANNEL_CAPACITY = 4
        const val AUDIO_CHANNEL_CAPACITY = 16
        const val BGRA_BYTES_PER_PIXEL = 4
        const val FLOAT_BYTES_PER_SAMPLE = 4
        val EMPTY_BYTES: ByteArray = ByteArray(0)

        fun trimToEven(value: Int): Int = if (value <= 0) 0 else value and EVEN_MASK
        const val EVEN_MASK: Int = -2 // 0xFFFFFFFE — clears the low bit
    }
}
