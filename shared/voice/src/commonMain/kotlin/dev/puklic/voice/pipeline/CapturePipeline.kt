package dev.puklic.voice.pipeline

import dev.puklic.voice.audio.AudioCapture
import dev.puklic.voice.codec.OpusEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Glues capture → Opus encode → AEAD encrypt + RTP wrap → UDP send.
 *
 * Per architect report `2026-05-23-voice.md` §8.
 *
 * VAD is a simple RMS gate (Short scale 0..32767). After [SILENCE_FRAMES_TO_STOP]
 * consecutive silent frames we emit 5 Opus silence frames `[0xF8, 0xFF, 0xFE]` and
 * mark speaking=false.
 *
 * The [encodeAndSend] lambda is what wraps an Opus frame into the encrypted RTP packet
 * and pushes it on the wire. Kept as a lambda so tests do not need a real UDP socket.
 * The wiring `opus -> packetCodec.encode(opus) -> transport.send(packet)` lives in the
 * caller (VoiceClient assembly point).
 */
internal class CapturePipeline(
    private val capture: AudioCapture,
    private val encoder: OpusEncoder,
    private val encodeAndSend: suspend (opusFrame: ByteArray) -> Unit,
    private val onSpeakingChange: suspend (Boolean) -> Unit,
    private val rmsThreshold: Int = DEFAULT_RMS_THRESHOLD,
    private val silenceFramesToStop: Int = SILENCE_FRAMES_TO_STOP,
    /**
     * Optional DAVE per-frame encrypt hook. Applied to the Opus frame BEFORE
     * the per-hop AEAD layer (which lives inside [encodeAndSend]). When null,
     * frames pass through unchanged — required for the non-DAVE fallback path
     * and for tests that don't want to spin up libdave.
     */
    private val daveEncrypt: (suspend (opusFrame: ByteArray) -> ByteArray)? = null,
) {

    private var job: Job? = null

    @Volatile
    private var muted: Boolean = false

    fun start(scope: CoroutineScope, deviceId: String? = null) {
        check(job == null || job?.isActive != true) { "CapturePipeline already running" }
        capture.start(deviceId)
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        capture.stop()
    }

    /**
     * Pause or resume the encode+send path without tearing down the capture line or
     * the Opus encoder. While [muted] is `true` the loop still drains the audio device
     * (so the OS line doesn't drift / overflow) but discards frames — no encode, no send,
     * speaking transitions are suppressed and a final speaking=false is emitted on the
     * transition to muted.
     */
    fun setMuted(value: Boolean) {
        muted = value
    }

    private suspend fun runLoop() {
        var speaking = false
        var silenceFrames = 0
        var wasMuted = false
        try {
            while (currentCoroutineIsActive()) {
                val pcm = capture.read()
                if (muted) {
                    if (!wasMuted) {
                        wasMuted = true
                        if (speaking) {
                            speaking = false
                            silenceFrames = 0
                            onSpeakingChange(false)
                        }
                    }
                    continue
                }
                if (wasMuted) {
                    wasMuted = false
                    // Reset gate so we don't carry stale silence-tail state across the pause.
                    silenceFrames = 0
                    speaking = false
                }
                val isLoud = computeRms(pcm) > rmsThreshold
                if (isLoud) {
                    silenceFrames = 0
                    if (!speaking) {
                        speaking = true
                        onSpeakingChange(true)
                    }
                    val opus = encoder.encode(pcm)
                    val toSend = daveEncrypt?.invoke(opus) ?: opus
                    encodeAndSend(toSend)
                } else if (speaking) {
                    silenceFrames++
                    if (silenceFrames >= silenceFramesToStop) {
                        // Emit 5 Opus silence frames per Discord docs to flush speaking state.
                        repeat(SILENCE_FRAMES_TAIL) { encodeAndSend(OPUS_SILENCE_FRAME) }
                        speaking = false
                        silenceFrames = 0
                        onSpeakingChange(false)
                    } else {
                        // While we're in the "trailing silence" window, still encode silence
                        // frames so Discord's jitter buffer keeps timestamps aligned.
                        val opus = encoder.encode(pcm)
                        encodeAndSend(opus)
                    }
                }
            }
        } finally {
            if (speaking) onSpeakingChange(false)
        }
    }

    private suspend fun currentCoroutineIsActive(): Boolean {
        // Hop through coroutineContext without exposing it to keep the loop concise.
        return kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive ?: false
    }

    companion object {
        /** Number of consecutive silent frames before we stop speaking (25 × 20 ms = 500 ms). */
        const val SILENCE_FRAMES_TO_STOP: Int = 25

        /** Number of Opus silence frames to emit when speaking stops. */
        const val SILENCE_FRAMES_TAIL: Int = 5

        /** RMS gate threshold on signed-16 PCM (0..32767). Tunable later. */
        const val DEFAULT_RMS_THRESHOLD: Int = 500

        /** Opus "silence" frame magic per Discord voice docs. */
        val OPUS_SILENCE_FRAME: ByteArray = byteArrayOf(0xF8.toByte(), 0xFF.toByte(), 0xFE.toByte())

        internal fun computeRms(pcm: ShortArray): Int {
            if (pcm.isEmpty()) return 0
            var sumSq = 0.0
            for (s in pcm) {
                val v = s.toInt()
                sumSq += (v * v).toDouble()
            }
            return kotlin.math.sqrt(sumSq / pcm.size).toInt()
        }
    }
}
