@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class, BetaInteropApi::class)

package dev.puklic.voice.audio

import co.touchlab.kermit.Logger
import dev.puklic.voice.AudioConstants
import dev.puklic.voice.codec.PuklicVoiceCodec
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import platform.AVFAudio.AVAudioConverter
import platform.AVFAudio.AVAudioConverterInputStatus
import platform.AVFAudio.AVAudioConverterInputStatus_HaveData
import platform.AVFAudio.AVAudioConverterInputStatus_NoDataNow
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.AVFAudio.AVAudioTime
import platform.Foundation.NSError

/**
 * iOS `AudioCapture` implementation backed by `AVAudioEngine.inputNode` (the system mic).
 *
 * Pipeline:
 *  1. AVAudioEngine.inputNode delivers `AVAudioPCMBuffer` instances at the hardware-native
 *     format (typically 48 kHz Float32 stereo on device; 44.1 kHz Float32 mono on x86
 *     simulator).
 *  2. An `AVAudioConverter` retargets each buffer to the Discord profile —
 *     48 kHz Int16 mono interleaved (matches `AudioConstants`).
 *  3. The Int16 samples are accumulated into a Channel of 960-sample (20 ms) frames.
 *  4. [read] blocks on the Channel until a frame is ready.
 *
 * Threading: the tap block runs on AVAudioEngine's internal real-time render queue and
 * MUST NOT block. We use `Channel.trySend` (non-blocking) and log drops at WARN.
 * The accumulator is touched exclusively from that single render queue, so no lock is
 * required for it. [read] is called from a dedicated capture worker coroutine in
 * [dev.puklic.voice.pipeline.CapturePipeline].
 *
 * Per architect plan §4 in
 * `docs/03_infrastructure/architect-reports/2026-05-29-fp14h-3-implementation.md`.
 */
@PuklicVoiceCodec
public class IosAVAudioEngineCapture : AudioCapture {

    private val engine = AVAudioEngine()
    private val frames = Channel<ShortArray>(capacity = CHANNEL_CAPACITY)

    /** 48 kHz Int16 mono interleaved — the Discord/Opus capture profile. */
    private val targetFormat: AVAudioFormat = AVAudioFormat(
        commonFormat = AVAudioPCMFormatInt16,
        sampleRate = AudioConstants.SAMPLE_RATE_HZ.toDouble(),
        channels = AudioConstants.CHANNELS_MONO.toUInt(),
        interleaved = true,
    ) ?: error("Failed to construct target AVAudioFormat 48 kHz Int16 mono")

    private val converterRef: AtomicReference<AVAudioConverter?> = AtomicReference(null)
    private val accumulator: ShortArray = ShortArray(AudioConstants.SAMPLES_PER_FRAME)
    private var accumulated: Int = 0

    private var started: Boolean = false
    private var sessionAcquired: Boolean = false

    override fun start(deviceId: String?) {
        if (started) return
        IosAudioSession.acquire()
        sessionAcquired = true

        val input = engine.inputNode
        val inputFormat = input.inputFormatForBus(0u)
        check(inputFormat.sampleRate > 0.0 && inputFormat.channelCount > 0u) {
            "AVAudioEngine.inputNode reported invalid format (sampleRate=${inputFormat.sampleRate}, " +
                "channels=${inputFormat.channelCount}) — microphone permission likely denied"
        }

        converterRef.store(
            AVAudioConverter(fromFormat = inputFormat, toFormat = targetFormat)
                ?: error("Failed to construct AVAudioConverter ${inputFormat} → $targetFormat"),
        )

        // Tap buffer size: ~20 ms at the input rate. AVAudioEngine treats this as a hint.
        val tapBufferFrames = (inputFormat.sampleRate * FRAME_DURATION_MS / MS_PER_SECOND).toUInt()
        input.installTapOnBus(
            bus = 0u,
            bufferSize = tapBufferFrames,
            format = inputFormat,
        ) { buffer: AVAudioPCMBuffer?, _: AVAudioTime? ->
            if (buffer != null) {
                onTapBuffer(buffer)
            }
        }

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val ok = engine.startAndReturnError(errorPtr.ptr)
            check(ok) {
                "AVAudioEngine.startAndReturnError failed: ${errorPtr.value?.localizedDescription ?: "unknown"}"
            }
        }
        started = true
    }

    override fun stop() {
        if (!started) return
        runCatching { engine.inputNode.removeTapOnBus(0u) }
        engine.stop()
        accumulated = 0
        converterRef.store(null)
        started = false
        if (sessionAcquired) {
            IosAudioSession.release()
            sessionAcquired = false
        }
    }

    override fun read(): ShortArray {
        check(started) { "IosAVAudioEngineCapture not started" }
        // Channel is bounded; the tap thread pushes 20 ms frames, this consumer pulls them.
        return runBlocking { frames.receive() }
    }

    override fun close() {
        stop()
        frames.close()
    }

    private fun onTapBuffer(input: AVAudioPCMBuffer) {
        val converter = converterRef.load() ?: return
        // The converter delivers frames at `targetSampleRate / inputSampleRate` of input.
        val ratio = targetFormat.sampleRate / input.format.sampleRate
        val outFrames = ((input.frameLength.toLong() * ratio).toLong() + EXTRA_OUTPUT_FRAMES)
            .coerceAtLeast(MIN_OUTPUT_FRAMES.toLong())
        val outBuffer = AVAudioPCMBuffer(
            pCMFormat = targetFormat,
            frameCapacity = outFrames.toUInt(),
        ) ?: return

        var delivered = false
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            converter.convertToBuffer(
                outputBuffer = outBuffer,
                error = errorPtr.ptr,
                withInputFromBlock = { _, statusOut ->
                    if (delivered) {
                        statusOut?.pointed?.value = AVAudioConverterInputStatus_NoDataNow
                        null
                    } else {
                        delivered = true
                        statusOut?.pointed?.value = AVAudioConverterInputStatus_HaveData
                        input
                    }
                },
            )
            if (errorPtr.value != null) {
                Logger.w(LOG_TAG) { "AVAudioConverter error: ${errorPtr.value?.localizedDescription}" }
                return@memScoped
            }
        }

        val produced = outBuffer.frameLength.toInt()
        if (produced <= 0) return
        val channelData = outBuffer.int16ChannelData ?: return
        val firstChannel = channelData[0] ?: return

        var srcIndex = 0
        while (srcIndex < produced) {
            val available = AudioConstants.SAMPLES_PER_FRAME - accumulated
            val toCopy = minOf(available, produced - srcIndex)
            for (i in 0 until toCopy) {
                accumulator[accumulated + i] = firstChannel[srcIndex + i]
            }
            accumulated += toCopy
            srcIndex += toCopy
            if (accumulated == AudioConstants.SAMPLES_PER_FRAME) {
                val frame = accumulator.copyOf()
                accumulated = 0
                val sent = frames.trySend(frame).isSuccess
                if (!sent) {
                    Logger.w(LOG_TAG) { "Capture channel full; dropping 20 ms frame" }
                }
            }
        }
    }

    // Suppress unused enum import: referenced via converter callback typing.
    @Suppress("unused")
    private val keepEnumReference: AVAudioConverterInputStatus = AVAudioConverterInputStatus_HaveData

    private companion object {
        const val LOG_TAG = "IosAVAudioEngineCapture"
        const val CHANNEL_CAPACITY = 8
        const val FRAME_DURATION_MS = 20
        const val MS_PER_SECOND = 1000
        const val EXTRA_OUTPUT_FRAMES = 64L
        const val MIN_OUTPUT_FRAMES = 1024
    }
}

@PuklicVoiceCodec
public actual fun audioCapture(): AudioCapture = IosAVAudioEngineCapture()
