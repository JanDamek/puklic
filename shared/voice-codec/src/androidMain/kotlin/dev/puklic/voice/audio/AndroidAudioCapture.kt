package dev.puklic.voice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.puklic.voice.AudioConstants
import dev.puklic.voice.codec.PuklicVoiceCodec
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile

private const val BUFFER_FRAMES = 4

/**
 * Android `actual` for [audioCapture]: an [AudioRecord] on the `VOICE_COMMUNICATION` source (the
 * platform applies echo cancellation and noise suppression to it), 48 kHz mono 16-bit PCM, read in
 * blocking 20 ms frames. The record permission is requested and checked by the app before a call
 * starts, which is why the lint check for it is suppressed here.
 */
@PuklicVoiceCodec
public actual fun audioCapture(): AudioCapture = AndroidAudioCapture()

@SuppressLint("MissingPermission")
private class AndroidAudioCapture : AudioCapture {

    private var record: AudioRecord? = null

    @Volatile
    private var stopped: Boolean = true

    override fun start(deviceId: String?) {
        if (record != null) return
        val created = createRecord()
        check(created.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialise" }
        created.startRecording()
        check(created.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not start recording" }
        record = created
        stopped = false
    }

    private fun createRecord(): AudioRecord {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            AudioConstants.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferBytes > 0) { "AudioRecord does not support 48 kHz mono capture ($minBufferBytes)" }
        val frameBytes = AudioConstants.SAMPLES_PER_FRAME * AudioConstants.BYTES_PER_SAMPLE
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioConstants.SAMPLE_RATE_HZ)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBufferBytes, frameBytes * BUFFER_FRAMES))
            .build()
    }

    override fun read(): ShortArray {
        val active = checkNotNull(record) { "AudioCapture not started" }
        val frame = ShortArray(AudioConstants.SAMPLES_PER_FRAME)
        var filled = 0
        while (filled < frame.size) {
            val read = active.read(frame, filled, frame.size - filled, AudioRecord.READ_BLOCKING)
            if (read < 0) throw readFailure(read)
            filled += read
        }
        return frame
    }

    private fun readFailure(code: Int): Exception = when {
        stopped -> CancellationException("AudioCapture stopped")
        else -> IllegalStateException("AudioRecord.read returned $code")
    }

    override fun stop() {
        stopped = true
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }

    override fun close() = stop()
}
