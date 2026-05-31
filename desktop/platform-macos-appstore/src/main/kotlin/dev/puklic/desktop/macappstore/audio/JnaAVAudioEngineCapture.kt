@file:OptIn(dev.puklic.voice.codec.PuklicVoiceCodec::class)

package dev.puklic.desktop.macappstore.audio

import co.touchlab.kermit.Logger
import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import dev.puklic.desktop.macappstore.bridge.AVAudioFoundation
import dev.puklic.desktop.macappstore.bridge.AVAudioObjcRuntime
import dev.puklic.desktop.macappstore.bridge.AVFAudio
import dev.puklic.voice.AudioConstants
import dev.puklic.voice.audio.AudioCapture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Mac App Store `AudioCapture` impl backed by `AVAudioEngine.inputNode` via JNA
 * Objective-C runtime. Mirrors the iOS `IosAVAudioEngineCapture` flow:
 *
 *  1. installTapOnBus delivers `AVAudioPCMBuffer` with the hardware-native format
 *     (typically 48 kHz Float32 stereo).
 *  2. AVAudioConverter retargets to 48 kHz Int16 mono interleaved.
 *  3. Int16 samples accumulate to 960-sample (20 ms) frames pushed into a
 *     `LinkedBlockingQueue<ShortArray>` (capacity 8 = 160 ms of headroom).
 *  4. [read] takes from the queue (blocking, matches AudioCapture contract).
 *
 * Threading: the tap and converter callback run on AVAudioEngine's internal
 * real-time queue. Strong references to Callback instances and Block memory
 * are held in fields so JNA does not GC the trampolines mid-call.
 *
 * Per architect plan §5.3 in
 * `docs/03_infrastructure/architect-reports/2026-05-29-fp14h-3-implementation.md`.
 */
public class JnaAVAudioEngineCapture : AudioCapture {

    private val frames: LinkedBlockingQueue<ShortArray> = LinkedBlockingQueue(QUEUE_CAPACITY)
    private val accumulator: ShortArray = ShortArray(AudioConstants.SAMPLES_PER_FRAME)
    private var accumulated: Int = 0

    private var engine: Pointer? = null
    private var inputNode: Pointer? = null
    private var inputFormat: Pointer? = null
    private var targetFormat: Pointer? = null
    private var converter: Pointer? = null

    // Strong-held block + callback references so JNA does not GC the trampolines while
    // Apple holds the function pointer. Per FP-8 DelegateClass pattern.
    private var tapBlockHandle: AVFAudio.BlockHandle? = null
    private var inputBlockHandle: AVFAudio.BlockHandle? = null
    private var pendingInput: Pointer? = null
    private var pendingInputConsumed: Boolean = false

    private var started: Boolean = false

    public override fun start(deviceId: String?) {
        if (started) return

        val newEngine = AVAudioObjcRuntime.msgSend(
            AVAudioObjcRuntime.alloc(AVFAudio.classAVAudioEngine),
            AVFAudio.selInit,
        )
        check(Pointer.nativeValue(newEngine) != 0L) { "AVAudioEngine alloc/init returned nil" }
        engine = newEngine

        val input = AVAudioObjcRuntime.msgSend(newEngine, AVFAudio.selInputNode)
        check(Pointer.nativeValue(input) != 0L) { "AVAudioEngine.inputNode returned nil" }
        inputNode = input

        val inFmt = AVAudioObjcRuntime.msgSendLong(input, AVFAudio.selFormatOfNode, 0L)
        check(Pointer.nativeValue(inFmt) != 0L) { "AVAudioInputNode.inputFormatForBus(0) returned nil" }
        inputFormat = inFmt
        val inputSampleRate = AVAudioObjcRuntime.msgSendDouble(inFmt, AVFAudio.selSampleRate)
        check(inputSampleRate > 0.0) { "AVAudioInputNode sample rate is 0 — mic permission denied?" }

        val tgt = AVFAudio.makeFormat(
            commonFormat = AVFAudio.PCM_FORMAT_INT16,
            sampleRate = AudioConstants.SAMPLE_RATE_HZ.toDouble(),
            channels = AudioConstants.CHANNELS_MONO,
            interleaved = true,
        )
        targetFormat = tgt

        val conv = AVAudioObjcRuntime.msgSend(
            AVAudioObjcRuntime.alloc(AVFAudio.classAVAudioConverter),
            AVFAudio.selInitFromToFormat,
            inFmt,
            tgt,
        )
        check(Pointer.nativeValue(conv) != 0L) { "AVAudioConverter init returned nil" }
        converter = conv

        // Pre-build the converter-input block. The same block is reused per onTapBuffer call
        // (we update pendingInput / pendingInputConsumed fields before invoking convertToBuffer).
        val inputCallback = ConverterInputCallback()
        inputBlockHandle = AVFAudio.makeBlock(inputCallback)

        // Build the tap block.
        val tapCallback = TapCallback()
        tapBlockHandle = AVFAudio.makeBlock(tapCallback)

        val tapBufferFrames = (inputSampleRate * FRAME_DURATION_MS / MS_PER_SECOND).toInt()
        AVAudioObjcRuntime.msgSendInstallTap(
            target = input,
            sel = AVFAudio.selInstallTap,
            bus = 0L,
            bufferSize = tapBufferFrames,
            format = inFmt,
            block = tapBlockHandle?.pointer,
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
        started = true
    }

    public override fun stop() {
        if (!started) return
        inputNode?.let { node ->
            runCatching {
                AVAudioObjcRuntime.msgSend(node, AVFAudio.selRemoveTap, Pointer.createConstant(0L))
            }
        }
        engine?.let { AVAudioObjcRuntime.msgSendVoid(it, AVFAudio.selStop) }
        accumulated = 0
        frames.clear()
        // Release Objective-C objects.
        converter?.let { AVFAudio.release(it) }
        targetFormat?.let { AVFAudio.release(it) }
        engine?.let { AVFAudio.release(it) }
        converter = null
        targetFormat = null
        inputFormat = null
        inputNode = null
        engine = null
        tapBlockHandle = null
        inputBlockHandle = null
        started = false
    }

    public override fun read(): ShortArray {
        check(started) { "JnaAVAudioEngineCapture not started" }
        // Blocking take; mirrors JavaSound's TargetDataLine.read blocking semantics.
        return frames.take()
    }

    public override fun close() {
        stop()
    }

    private fun onTapBuffer(buffer: Pointer) {
        val conv = converter ?: return
        val tgt = targetFormat ?: return

        // Output capacity heuristic: target_sample_rate / input_sample_rate * input_frames + slack.
        val inputLength = AVAudioObjcRuntime.msgSendLong(buffer, AVFAudio.selFrameLength).toInt()
        if (inputLength <= 0) return
        val ratio = AudioConstants.SAMPLE_RATE_HZ.toDouble() /
            AVAudioObjcRuntime.msgSendDouble(
                AVAudioObjcRuntime.msgSend(buffer, AVFAudio.selFormat),
                AVFAudio.selSampleRate,
            )
        val outFrames = (inputLength * ratio).toInt() + EXTRA_OUTPUT_FRAMES

        val outBuffer = AVAudioObjcRuntime.msgSendInitBuffer(
            target = AVAudioObjcRuntime.alloc(AVFAudio.classAVAudioPCMBuffer),
            sel = AVFAudio.selInitWithPCMFormatFrameCap,
            format = tgt,
            capacity = outFrames,
        )
        check(Pointer.nativeValue(outBuffer) != 0L) { "AVAudioPCMBuffer init returned nil" }

        try {
            pendingInput = buffer
            pendingInputConsumed = false
            val errOut = PointerByReference()
            val inputBlock = inputBlockHandle?.pointer ?: return
            AVAudioObjcRuntime.msgSend(
                conv,
                AVFAudio.selConvertToBufferError,
                outBuffer,
                errOut.pointer,
                inputBlock,
            )
            if (errOut.value != null && Pointer.nativeValue(errOut.value) != 0L) {
                Logger.w(LOG_TAG) {
                    "AVAudioConverter error: ${AVAudioFoundation.errorMessage(errOut.value)}"
                }
                return
            }

            val produced = AVAudioObjcRuntime.msgSendLong(outBuffer, AVFAudio.selFrameLength).toInt()
            if (produced <= 0) return

            val channelDataPtr = AVAudioObjcRuntime.msgSend(outBuffer, AVFAudio.selInt16ChannelData)
            if (Pointer.nativeValue(channelDataPtr) == 0L) return
            // `int16ChannelData` is `Int16 ** const`; channel 0 is at offset 0.
            val firstChannel = channelDataPtr.getPointer(0L) ?: return

            // Read out the Int16 samples; emit 960-sample frames.
            val shortBuffer = ShortArray(produced)
            firstChannel.read(0L, shortBuffer, 0, produced)
            var srcIndex = 0
            while (srcIndex < produced) {
                val available = AudioConstants.SAMPLES_PER_FRAME - accumulated
                val toCopy = kotlin.math.min(available, produced - srcIndex)
                System.arraycopy(shortBuffer, srcIndex, accumulator, accumulated, toCopy)
                accumulated += toCopy
                srcIndex += toCopy
                if (accumulated == AudioConstants.SAMPLES_PER_FRAME) {
                    val frame = accumulator.copyOf()
                    accumulated = 0
                    val accepted = frames.offer(frame, 0L, TimeUnit.MILLISECONDS)
                    if (!accepted) {
                        Logger.w(LOG_TAG) { "Capture queue full; dropping 20 ms frame" }
                    }
                }
            }
        } finally {
            AVFAudio.release(outBuffer)
            pendingInput = null
        }
    }

    /**
     * Block invoked by AVAudioConverter to request input. Signature:
     *   `AVAudioBuffer * _Nullable (^block)(AVAudioPacketCount inNumberOfPackets,
     *                                       AVAudioConverterInputStatus *outStatus)`.
     * The first invocation returns our buffer + HAVE_DATA; subsequent returns NO_DATA_NOW
     * so the converter stops asking.
     */
    private inner class ConverterInputCallback : Callback {
        @Suppress("unused")
        fun invoke(
            blockSelf: Pointer?,
            @Suppress("UNUSED_PARAMETER") numPackets: Int,
            statusOut: Pointer?,
        ): Pointer? {
            if (pendingInputConsumed) {
                statusOut?.setInt(0L, AVFAudio.INPUT_STATUS_NO_DATA_NOW)
                return null
            }
            pendingInputConsumed = true
            statusOut?.setInt(0L, AVFAudio.INPUT_STATUS_HAVE_DATA)
            return pendingInput
        }
    }

    /**
     * Block invoked by AVAudioInputNode tap. Signature:
     *   `void (^block)(AVAudioPCMBuffer *buffer, AVAudioTime *when)`.
     */
    private inner class TapCallback : Callback {
        @Suppress("unused")
        fun invoke(blockSelf: Pointer?, buffer: Pointer?, time: Pointer?) {
            if (buffer == null || Pointer.nativeValue(buffer) == 0L) return
            try {
                onTapBuffer(buffer)
            } catch (t: Throwable) {
                Logger.e(LOG_TAG, t) { "Tap callback failed" }
            }
        }
    }

    private companion object {
        const val LOG_TAG = "JnaAVAudioEngineCapture"
        const val QUEUE_CAPACITY = 8
        const val FRAME_DURATION_MS = 20
        const val MS_PER_SECOND = 1000
        const val EXTRA_OUTPUT_FRAMES = 64
    }
}

/** Mac App Store factory returning a fresh AVAudioEngine-backed [AudioCapture]. */
public fun jnaAudioCapture(): AudioCapture = JnaAVAudioEngineCapture()

/** Suppress unused field warning on Memory imports. */
@Suppress("unused")
private val keepImport: Class<Memory> = Memory::class.java
