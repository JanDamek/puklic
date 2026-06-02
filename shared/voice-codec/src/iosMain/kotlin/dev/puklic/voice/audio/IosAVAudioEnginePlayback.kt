@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class, BetaInteropApi::class)

package dev.puklic.voice.audio

import dev.puklic.voice.AudioConstants
import dev.puklic.voice.codec.PuklicVoiceCodec
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.AVFAudio.AVAudioPlayerNode
import platform.Foundation.NSError

/**
 * iOS `AudioPlayback` implementation backed by `AVAudioPlayerNode` attached to
 * `AVAudioEngine.mainMixerNode`.
 *
 * Each [write] copies the 960-sample Int16 mono frame into a freshly allocated
 * `AVAudioPCMBuffer` at the 48 kHz Int16 mono format and schedules it. The mixer
 * drains buffers at line rate; backpressure is enforced by a bounded "in-flight"
 * counter — [write] busy-waits if more than [MAX_IN_FLIGHT] buffers are queued,
 * matching the JavaSound `INTERNAL_BUFFER_MS=80` policy (≈4 frames).
 *
 * Threading: AVAudioPlayerNode's completion handler fires on AVAudioEngine's
 * internal queue. We use lock-free `AtomicInt` for the counter.
 *
 * Per architect plan §4.3 in
 * `docs/03_infrastructure/architect-reports/2026-05-29-fp14h-3-implementation.md`.
 */
@PuklicVoiceCodec
public class IosAVAudioEnginePlayback : AudioPlayback {

    private val engine = AVAudioEngine()
    private val player = AVAudioPlayerNode()
    private val inFlight = AtomicInt(0)

    /** 48 kHz Int16 mono interleaved — matches the encoded frame contract. */
    private val outputFormat: AVAudioFormat = AVAudioFormat(
        commonFormat = AVAudioPCMFormatInt16,
        sampleRate = AudioConstants.SAMPLE_RATE_HZ.toDouble(),
        channels = AudioConstants.CHANNELS_MONO.toUInt(),
        interleaved = true,
    ) ?: error("Failed to construct AVAudioFormat 48 kHz Int16 mono")

    private var started: Boolean = false
    private var sessionAcquired: Boolean = false

    override fun start(deviceId: String?) {
        if (started) return
        IosAudioSession.acquire()
        sessionAcquired = true

        engine.attachNode(player)
        engine.connect(player, to = engine.mainMixerNode, format = outputFormat)

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val ok = engine.startAndReturnError(errorPtr.ptr)
            check(ok) {
                "AVAudioEngine.startAndReturnError failed: " +
                    "${errorPtr.value?.localizedDescription ?: "unknown"}"
            }
        }
        player.play()
        started = true
    }

    override fun stop() {
        if (!started) return
        runCatching { player.stop() }
        engine.stop()
        engine.detachNode(player)
        inFlight.store(0)
        started = false
        if (sessionAcquired) {
            IosAudioSession.release()
            sessionAcquired = false
        }
    }

    override fun write(pcm: ShortArray) {
        check(started) { "IosAVAudioEnginePlayback not started" }
        require(pcm.size == AudioConstants.SAMPLES_PER_FRAME) {
            "Frame must be ${AudioConstants.SAMPLES_PER_FRAME} samples, got ${pcm.size}"
        }

        // Backpressure: wait if the mixer queue is saturated. We yield using a short
        // sleep so we don't burn CPU in a tight loop.
        while (inFlight.load() >= MAX_IN_FLIGHT) {
            platform.posix.usleep(BACKPRESSURE_SLEEP_US)
        }

        val buffer = AVAudioPCMBuffer(
            pCMFormat = outputFormat,
            frameCapacity = AudioConstants.SAMPLES_PER_FRAME.toUInt(),
        ) ?: error("Failed to allocate AVAudioPCMBuffer")
        buffer.frameLength = AudioConstants.SAMPLES_PER_FRAME.toUInt()

        val channelData = buffer.int16ChannelData ?: error("AVAudioPCMBuffer has no int16ChannelData")
        val firstChannel = channelData[0] ?: error("AVAudioPCMBuffer channel 0 is null")
        for (i in pcm.indices) {
            firstChannel[i] = pcm[i]
        }

        inFlight.fetchAndAdd(1)
        player.scheduleBuffer(buffer) {
            inFlight.fetchAndAdd(-1)
        }
    }

    override fun close() {
        stop()
    }

    private companion object {
        /** ≈80 ms tolerated queue depth — matches JavaSoundPlayback INTERNAL_BUFFER_MS. */
        const val MAX_IN_FLIGHT = 4
        const val BACKPRESSURE_SLEEP_US: UInt = 1000u
    }
}

@PuklicVoiceCodec
public actual fun audioPlayback(): AudioPlayback = IosAVAudioEnginePlayback()
