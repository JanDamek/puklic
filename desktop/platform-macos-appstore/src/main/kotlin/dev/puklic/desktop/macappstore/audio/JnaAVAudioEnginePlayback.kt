@file:OptIn(dev.puklic.voice.codec.PuklicVoiceCodec::class)

package dev.puklic.desktop.macappstore.audio

import co.touchlab.kermit.Logger
import com.sun.jna.Callback
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import dev.puklic.desktop.macappstore.bridge.AVAudioFoundation
import dev.puklic.desktop.macappstore.bridge.AVAudioObjcRuntime
import dev.puklic.desktop.macappstore.bridge.AVFAudio
import dev.puklic.voice.AudioConstants
import dev.puklic.voice.audio.AudioPlayback
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/**
 * Mac App Store `AudioPlayback` impl backed by `AVAudioPlayerNode` attached to
 * `AVAudioEngine.mainMixerNode` via JNA Objective-C runtime.
 *
 * Each [write] allocates a fresh `AVAudioPCMBuffer` (48 kHz Int16 mono, 960
 * samples), copies the caller's `ShortArray` into channel 0, and schedules it
 * on the player node. The mixer drains at line rate.
 *
 * Backpressure: scheduled-but-not-yet-played buffers are tracked in
 * [outstanding]. If the count reaches [MAX_OUTSTANDING] (≈80 ms = 4 frames),
 * [write] parks on a 1 ms quantum until the completion handler decrements.
 * Matches JavaSoundPlayback's INTERNAL_BUFFER_MS=80 policy.
 *
 * Per architect plan §5.4 in
 * `docs/03_infrastructure/architect-reports/2026-05-29-fp14h-3-implementation.md`.
 */
public class JnaAVAudioEnginePlayback : AudioPlayback {

    private val outstanding: AtomicInteger = AtomicInteger(0)
    private var engine: Pointer? = null
    private var player: Pointer? = null
    private var outputFormat: Pointer? = null

    private var completionBlockHandle: AVFAudio.BlockHandle? = null
    private var started: Boolean = false

    public override fun start(deviceId: String?) {
        if (started) return

        val newEngine = AVAudioObjcRuntime.msgSend(
            AVAudioObjcRuntime.alloc(AVFAudio.classAVAudioEngine),
            AVFAudio.selInit,
        )
        check(Pointer.nativeValue(newEngine) != 0L) { "AVAudioEngine alloc/init returned nil" }
        engine = newEngine

        val newPlayer = AVAudioObjcRuntime.msgSend(
            AVAudioObjcRuntime.alloc(AVFAudio.classAVAudioPlayerNode),
            AVFAudio.selInit,
        )
        check(Pointer.nativeValue(newPlayer) != 0L) { "AVAudioPlayerNode alloc/init returned nil" }
        player = newPlayer

        val fmt = AVFAudio.makeFormat(
            commonFormat = AVFAudio.PCM_FORMAT_INT16,
            sampleRate = AudioConstants.SAMPLE_RATE_HZ.toDouble(),
            channels = AudioConstants.CHANNELS_MONO,
            interleaved = true,
        )
        outputFormat = fmt

        AVAudioObjcRuntime.msgSendVoid(newEngine, AVFAudio.selAttachNode, newPlayer)
        val mixer = AVAudioObjcRuntime.msgSend(newEngine, AVFAudio.selMainMixerNode)
        check(Pointer.nativeValue(mixer) != 0L) { "AVAudioEngine.mainMixerNode returned nil" }
        AVAudioObjcRuntime.msgSendConnect(
            target = newEngine,
            sel = AVFAudio.selConnectToFormat,
            from = newPlayer,
            to = mixer,
            format = fmt,
        )

        val errOut = PointerByReference()
        val ok = AVAudioObjcRuntime.msgSendStartAndReturnError(
            target = newEngine,
            sel = AVFAudio.selStartAndReturnError,
            errOut = errOut.pointer,
        )
        check(ok) {
            "AVAudioEngine.startAndReturnError failed: ${AVAudioFoundation.errorMessage(errOut.value)}"
        }
        AVAudioObjcRuntime.msgSendVoid(newPlayer, AVFAudio.selPlay)

        completionBlockHandle = AVFAudio.makeBlock(CompletionCallback())
        started = true
    }

    public override fun stop() {
        if (!started) return
        player?.let { AVAudioObjcRuntime.msgSendVoid(it, AVFAudio.selPlayerStop) }
        engine?.let { AVAudioObjcRuntime.msgSendVoid(it, AVFAudio.selStop) }
        // Detach + release in reverse order.
        engine?.let { eng ->
            player?.let { AVAudioObjcRuntime.msgSendVoid(eng, AVFAudio.selDetachNode, it) }
        }
        outputFormat?.let { AVFAudio.release(it) }
        player?.let { AVFAudio.release(it) }
        engine?.let { AVFAudio.release(it) }
        outputFormat = null
        player = null
        engine = null
        completionBlockHandle = null
        outstanding.set(0)
        started = false
    }

    public override fun write(pcm: ShortArray) {
        check(started) { "JnaAVAudioEnginePlayback not started" }
        require(pcm.size == AudioConstants.SAMPLES_PER_FRAME) {
            "Frame must be ${AudioConstants.SAMPLES_PER_FRAME} samples, got ${pcm.size}"
        }
        // Backpressure — wait for the completion handler to drain the queue.
        while (outstanding.get() >= MAX_OUTSTANDING) {
            LockSupport.parkNanos(BACKPRESSURE_PARK_NS)
        }

        val fmt = outputFormat ?: return
        val pl = player ?: return
        val blockPtr = completionBlockHandle?.pointer ?: return

        val buffer = AVAudioObjcRuntime.msgSendInitBuffer(
            target = AVAudioObjcRuntime.alloc(AVFAudio.classAVAudioPCMBuffer),
            sel = AVFAudio.selInitWithPCMFormatFrameCap,
            format = fmt,
            capacity = AudioConstants.SAMPLES_PER_FRAME,
        )
        check(Pointer.nativeValue(buffer) != 0L) { "AVAudioPCMBuffer init returned nil" }

        // Set frameLength = 960. setFrameLength: takes a UInt32 — encoded via the long arg form.
        AVAudioObjcRuntime.msgSendLong(buffer, AVFAudio.selSetFrameLength, AudioConstants.SAMPLES_PER_FRAME.toLong())

        val channelDataPtr = AVAudioObjcRuntime.msgSend(buffer, AVFAudio.selInt16ChannelData)
        check(Pointer.nativeValue(channelDataPtr) != 0L) { "AVAudioPCMBuffer.int16ChannelData null" }
        val firstChannel = channelDataPtr.getPointer(0L)
            ?: error("AVAudioPCMBuffer channel 0 pointer null")
        firstChannel.write(0L, pcm, 0, pcm.size)

        outstanding.incrementAndGet()
        AVAudioObjcRuntime.msgSendScheduleBuffer(
            target = pl,
            sel = AVFAudio.selScheduleBuffer,
            buffer = buffer,
            block = blockPtr,
        )
        // The player retains the buffer for the duration of playback; we release our ref.
        AVFAudio.release(buffer)
    }

    public override fun close() {
        stop()
    }

    /** AVAudioPlayerNodeCompletionHandler — `void (^)(void)`. */
    private inner class CompletionCallback : Callback {
        @Suppress("unused")
        fun invoke(@Suppress("UNUSED_PARAMETER") blockSelf: Pointer?) {
            val n = outstanding.decrementAndGet()
            if (n < 0) {
                Logger.w(LOG_TAG) { "Completion handler ran with outstanding < 0; resetting" }
                outstanding.set(0)
            }
        }
    }

    private companion object {
        const val LOG_TAG = "JnaAVAudioEnginePlayback"
        const val MAX_OUTSTANDING = 4
        const val BACKPRESSURE_PARK_NS = 1_000_000L // 1 ms
    }
}

/** Mac App Store factory returning a fresh AVAudioEngine-backed [AudioPlayback]. */
public fun jnaAudioPlayback(): AudioPlayback = JnaAVAudioEnginePlayback()
