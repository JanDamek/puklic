package dev.puklic.voice.pipeline

import dev.puklic.voice.AudioConstants
import dev.puklic.voice.audio.AudioPlayback
import dev.puklic.voice.codec.OpusDecoder
import dev.puklic.voice.codec.PuklicVoiceCodec
import dev.puklic.voice.codec.transport.VoiceUdpTransport
import dev.puklic.voice.transport.RtpPacket
import dev.puklic.voice.transport.VoicePacketCodec
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

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
 * SSRC streams are GC'd after [SSRC_IDLE_TIMEOUT] of silence.
 *
 * FP-14h-4 (architect report 2026-06-02-fp14h-applevoiceclient-promotion-plan.md §2):
 * promoted from `:shared:voice/jvmMain` to `:shared:voice-codec/commonMain`. JVM-only
 * concurrency primitives are replaced with KMP-portable equivalents:
 * `ConcurrentHashMap` → [MutableMap] guarded by [Mutex]; `ConcurrentLinkedQueue` →
 * `ArrayDeque` guarded by the same mutex; `java.util.concurrent.atomic` →
 * `kotlinx.atomicfu`; `System.nanoTime()` → [TimeSource.Monotonic]. Behaviour is
 * preserved: the receive coroutine is the sole writer to a stream's queue, the mixer
 * coroutine is the sole reader, and the mutex serialises map structural changes.
 */
@PuklicVoiceCodec
public class PlaybackPipeline(
    private val transport: VoiceUdpTransport,
    private val packetCodec: VoicePacketCodec,
    private val decoderFactory: () -> OpusDecoder,
    private val playback: AudioPlayback,
    private val onSpeakers: suspend (Set<Int>) -> Unit,
    /**
     * Optional override for the packet source. Defaults to reading directly from [transport],
     * which is the legacy single-consumer mode used by tests. The wiring layer
     * (`dev.puklic.voice.DefaultVoiceClient`) injects a dispatcher-fed source so audio and
     * video pipelines can co-exist on the same UDP socket without contending on receive().
     */
    private val packetSource: (suspend () -> ByteArray)? = null,
    /**
     * Optional DAVE per-frame decrypt hook. Invoked AFTER per-hop AEAD strips the
     * RTP wrapper and BEFORE Opus decode. Receives (ssrc, ciphertext-or-opus) and
     * returns plaintext Opus, or null on integrity failure (drop the frame).
     * When null, frames pass through unchanged (non-DAVE fallback).
     */
    private val daveDecrypt: (suspend (ssrc: Int, opusOrCiphertext: ByteArray) -> ByteArray?)? = null,
) {

    private val mutex = Mutex()
    private val streams: MutableMap<Int, SsrcStream> = mutableMapOf()
    private var receiveJob: Job? = null
    private var mixerJob: Job? = null

    public fun start(scope: CoroutineScope, deviceId: String? = null) {
        check(receiveJob == null && mixerJob == null) { "PlaybackPipeline already running" }
        playback.start(deviceId)
        receiveJob = scope.launch(Dispatchers.Default) { receiveLoop() }
        mixerJob = scope.launch(Dispatchers.Default) { mixerLoop() }
    }

    public fun stop() {
        receiveJob?.cancel()
        mixerJob?.cancel()
        receiveJob = null
        mixerJob = null
        playback.stop()
        // Both coroutines cancelled; safe to iterate without the mutex.
        streams.values.forEach { it.decoder.close() }
        streams.clear()
    }

    private suspend fun receiveLoop() {
        val explicitSource = packetSource
        try {
            if (explicitSource != null) {
                while (isActive()) {
                    handlePacket(explicitSource())
                }
            } else {
                transport.incoming.collect { packet -> handlePacket(packet) }
            }
        } catch (_: Exception) {
            // Socket closed, coroutine cancelled, or upstream Flow terminated — exit cleanly;
            // mixer loop will catch up & exit.
        }
    }

    private suspend fun handlePacket(packet: ByteArray) {
        val decoded = try {
            packetCodec.decode(packet)
        } catch (_: IllegalArgumentException) {
            return
        } catch (_: Exception) {
            // AEAD failure on a non-Opus payload (video uses a separate counter sequence)
            // — drop and continue.
            return
        }
        if (decoded.header.payloadType != RtpPacket.PAYLOAD_TYPE_OPUS) return
        val ssrc = decoded.header.ssrc
        val stream = mutex.withLock {
            streams.getOrPut(ssrc) { SsrcStream(decoderFactory(), JitterBuffer()) }
        }
        stream.lastPacketNanos.value = monotonicNanos()
        // DAVE: strip the per-frame ciphertext layer (if any) BEFORE we hand
        // bytes to the jitter buffer + Opus decoder. Pass-through on null hook;
        // drop the frame on integrity failure (null return) when a hook is set.
        val opusPlain: ByteArray = if (daveDecrypt != null) {
            daveDecrypt.invoke(ssrc, decoded.opus) ?: return
        } else {
            decoded.opus
        }
        val ready = stream.buffer.push(decoded.header.sequence, opusPlain)
        for (opus in ready) {
            val pcm = try {
                stream.decoder.decode(opus, fec = false)
            } catch (_: Exception) {
                continue
            }
            stream.offerFrame(pcm)
        }
    }

    private suspend fun mixerLoop() {
        val mixed = ShortArray(AudioConstants.SAMPLES_PER_FRAME)
        while (isActive()) {
            for (i in mixed.indices) mixed[i] = 0
            val active = mutableSetOf<Int>()
            // Snapshot the current SSRC set so we don't hold the mutex while invoking
            // user callbacks or blocking playback writes.
            val snapshot = mutex.withLock { streams.toMap() }
            for ((ssrc, stream) in snapshot) {
                val frame = stream.pollFrame() ?: continue
                active += ssrc
                mixInto(mixed, frame)
            }
            playback.write(mixed)
            onSpeakers(active)
            gcIdleStreams()
        }
    }

    private suspend fun gcIdleStreams() {
        val now = monotonicNanos()
        mutex.withLock {
            val it = streams.entries.iterator()
            while (it.hasNext()) {
                val (_, stream) = it.next()
                if (now - stream.lastPacketNanos.value > SSRC_IDLE_TIMEOUT_NS) {
                    stream.decoder.close()
                    it.remove()
                }
            }
        }
    }

    private suspend fun isActive(): Boolean =
        currentCoroutineContext()[Job]?.isActive ?: false

    public companion object {
        /** SSRC idle GC threshold in nanoseconds (10 s) — same as the legacy JVM impl. */
        public const val SSRC_IDLE_TIMEOUT_NS: Long = 10_000_000_000L

        // Monotonic clock anchor. TimeSource.Monotonic doesn't expose raw nanoseconds, but
        // marking once at class-load and measuring elapsedNow() on each call gives a stable
        // monotonic nanosecond value across both JVM (System.nanoTime) and Kotlin/Native
        // (mach_absolute_time / clock_gettime CLOCK_MONOTONIC).
        private val origin = TimeSource.Monotonic.markNow()

        internal fun monotonicNanos(): Long = origin.elapsedNow().inWholeNanoseconds

        internal fun mixInto(dst: ShortArray, src: ShortArray) {
            val n = minOf(dst.size, src.size)
            for (i in 0 until n) {
                val sum = dst[i].toInt() + src[i].toInt()
                dst[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
    }
}

/**
 * Per-SSRC playback state. The pending-frames queue is guarded by an internal mutex
 * because the receive coroutine writes and the mixer coroutine reads from different
 * threads on JVM (`Dispatchers.Default` is a multi-thread pool). On single-threaded
 * Kotlin/Native dispatchers the lock is uncontended.
 */
private class SsrcStream(
    val decoder: OpusDecoder,
    val buffer: JitterBuffer,
) {
    val lastPacketNanos = atomic(PlaybackPipeline.monotonicNanos())
    private val queueMutex = Mutex()
    private val pending: ArrayDeque<ShortArray> = ArrayDeque()

    suspend fun offerFrame(frame: ShortArray) {
        queueMutex.withLock { pending.addLast(frame) }
    }

    suspend fun pollFrame(): ShortArray? = queueMutex.withLock {
        if (pending.isEmpty()) null else pending.removeFirst()
    }
}
