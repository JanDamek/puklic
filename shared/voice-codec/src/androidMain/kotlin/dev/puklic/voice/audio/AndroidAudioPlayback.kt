package dev.puklic.voice.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dev.puklic.voice.AudioConstants
import dev.puklic.voice.codec.PuklicVoiceCodec

private const val BUFFER_MS = 200

/**
 * Android `actual` for [audioPlayback]: a streaming [AudioTrack] with the voice-communication usage so
 * the platform echo canceller sees the same signal path as the capture. [AudioTrack.write] blocks while
 * the buffer is full, which paces the mixer loop at the 20 ms frame rate.
 */
@PuklicVoiceCodec
public actual fun audioPlayback(): AudioPlayback = AndroidAudioPlayback()

private class AndroidAudioPlayback : AudioPlayback {

    private var track: AudioTrack? = null

    override fun start(deviceId: String?) {
        if (track != null) return
        val created = createTrack()
        check(created.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialise" }
        created.play()
        track = created
    }

    private fun createTrack(): AudioTrack {
        val minBufferBytes = AudioTrack.getMinBufferSize(
            AudioConstants.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferBytes > 0) { "AudioTrack does not support 48 kHz mono playback ($minBufferBytes)" }
        val targetBytes = AudioConstants.SAMPLE_RATE_HZ / MILLIS_PER_SECOND * BUFFER_MS * AudioConstants.BYTES_PER_SAMPLE
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioConstants.SAMPLE_RATE_HZ)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBufferBytes, targetBytes))
            .build()
    }

    override fun write(pcm: ShortArray) {
        val active = checkNotNull(track) { "AudioPlayback not started" }
        require(pcm.size == AudioConstants.SAMPLES_PER_FRAME) {
            "Frame must be ${AudioConstants.SAMPLES_PER_FRAME} samples, got ${pcm.size}"
        }
        var written = 0
        while (written < pcm.size) {
            val result = active.write(pcm, written, pcm.size - written, AudioTrack.WRITE_BLOCKING)
            check(result >= 0) { "AudioTrack.write returned $result" }
            written += result
        }
    }

    override fun stop() {
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            it.release()
        }
        track = null
    }

    override fun close() = stop()

    private companion object {
        const val MILLIS_PER_SECOND = 1_000
    }
}
