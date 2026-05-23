package dev.puklic.voice.pipeline

import dev.puklic.voice.AudioConstants
import dev.puklic.voice.audio.AudioPlayback
import dev.puklic.voice.codec.OpusDecoder
import dev.puklic.voice.transport.RtpPacket
import dev.puklic.voice.transport.UdpRtpTransport
import dev.puklic.voice.transport.VoicePacketCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Receives UDP packets → AEAD decrypt → Opus decode (per SSRC) → mix → write to playback.
 *
 * Per architect report `2026-05-23-voice.md` §9.
 *
 * Pacing: the mixer loop is driven by [AudioPlayback.write], which blocks once the platform
 * buffer is full (~80 ms = 4 frames). That yields a natural ~50 Hz tick without a separate
 * scheduled tick. A strict-tick mixer would be more correct under packet drift but is
 * deferred — see report §9 note on simplicity.
 *
 * SSRC streams are GC'd after [SSRC_IDLE_TIMEOUT_NS] of silence.
 */
internal class PlaybackPipeline(
    private val transport: UdpRtpTransport,
    private val packetCodec: VoicePacketCodec,
    private val decoderFactory: () -> OpusDecoder,
    private val playback: AudioPlayback,
    private val onSpeakers: suspend (Set<Int>) -> Unit,
    /**
     * Optional override for the packet source. Defaults to reading directly from [transport],
     * which is the legacy single-consumer mode used by tests. The wiring layer
     * ([dev.puklic.voice.DefaultVoiceClient]) injects a dispatcher-fed source so audio and
     * video pipelines can co-exist on the same UDP socket without contending on receive().
     */
    private val packetSource: (suspend () -> ByteArray)? = null,
) {

    private val streams = ConcurrentHashMap<Int, SsrcStream>()
    private var receiveJob: Job? = null
    private var mixerJob: Job? = null

    fun start(scope: CoroutineScope, deviceId: String? = null) {
        check(receiveJob == null && mixerJob == null) { "PlaybackPipeline already running" }
        playback.start(deviceId)
        receiveJob = scope.launch(Dispatchers.IO) { receiveLoop() }
        mixerJob = scope.launch(Dispatchers.IO) { mixerLoop() }
    }

    fun stop() {
        receiveJob?.cancel()
        mixerJob?.cancel()
        receiveJob = null
        mixerJob = null
        playback.stop()
        streams.values.forEach { it.decoder.close() }
        streams.clear()
    }

    private suspend fun receiveLoop() {
        val source: suspend () -> ByteArray = packetSource ?: { transport.receive() }
        while (isActive()) {
            val packet = try {
                source()
            } catch (_: Exception) {
                // Socket closed or interrupted — exit cleanly; mixer loop will catch up & exit.
                return
            }
            val decoded = try {
                packetCodec.decode(packet)
            } catch (_: IllegalArgumentException) {
                continue
            } catch (_: Exception) {
                // AEAD failure on a non-Opus payload (video uses a separate counter sequence)
                // — drop and continue.
                continue
            }
            if (decoded.header.payloadType != RtpPacket.PAYLOAD_TYPE_OPUS) continue
            val ssrc = decoded.header.ssrc
            val stream = streams.getOrPut(ssrc) { SsrcStream(decoderFactory(), JitterBuffer()) }
            stream.lastPacketNs = System.nanoTime()
            val ready = stream.buffer.push(decoded.header.sequence, decoded.opus)
            for (opus in ready) {
                val pcm = try {
                    stream.decoder.decode(opus, fec = false)
                } catch (_: Exception) {
                    continue
                }
                stream.pendingFrames.add(pcm)
            }
        }
    }

    private suspend fun mixerLoop() {
        val mixed = ShortArray(AudioConstants.SAMPLES_PER_FRAME)
        while (isActive()) {
            for (i in mixed.indices) mixed[i] = 0
            val active = mutableSetOf<Int>()
            for ((ssrc, stream) in streams) {
                val frame = stream.pendingFrames.poll() ?: continue
                active += ssrc
                mixInto(mixed, frame)
            }
            playback.write(mixed)
            onSpeakers(active)
            gcIdleStreams()
        }
    }

    private fun gcIdleStreams() {
        val now = System.nanoTime()
        val it = streams.entries.iterator()
        while (it.hasNext()) {
            val (_, stream) = it.next()
            if (now - stream.lastPacketNs > SSRC_IDLE_TIMEOUT_NS) {
                stream.decoder.close()
                it.remove()
            }
        }
    }

    private suspend fun isActive(): Boolean =
        currentCoroutineContext()[Job]?.isActive ?: false

    companion object {
        const val SSRC_IDLE_TIMEOUT_NS: Long = 10_000_000_000L

        internal fun mixInto(dst: ShortArray, src: ShortArray) {
            val n = minOf(dst.size, src.size)
            for (i in 0 until n) {
                val sum = dst[i].toInt() + src[i].toInt()
                dst[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
    }
}

private class SsrcStream(
    val decoder: OpusDecoder,
    val buffer: JitterBuffer,
) {
    val pendingFrames: ConcurrentLinkedQueue<ShortArray> = ConcurrentLinkedQueue()
    @Volatile var lastPacketNs: Long = System.nanoTime()
}
