package dev.puklic.voice.audio

import dev.puklic.voice.codec.PuklicVoiceCodec

import dev.puklic.voice.AudioConstants
import dev.puklic.voice.AudioDevice
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine

/**
 * JVM playback actual: `javax.sound.sampled.SourceDataLine` at 48 kHz / 16-bit / mono / LE.
 *
 * The internal buffer is sized to ~80 ms (4 frames) — small enough to keep latency low,
 * large enough that one missed mixer tick does not under-run. Per architect report §9.
 */
private const val INTERNAL_BUFFER_MS: Int = 80
private const val BITS_PER_SAMPLE: Int = 16
private const val SIGNED: Boolean = true
private const val BIG_ENDIAN: Boolean = false

@PuklicVoiceCodec
public actual fun audioPlayback(): AudioPlayback = JavaSoundPlayback()

@PuklicVoiceCodec
public fun listPlaybackDevices(): List<AudioDevice> =
    enumeratePlaybackMixers().map { (info, isDefault) ->
        AudioDevice(
            id = info.name,
            displayName = "${info.name} — ${info.description}",
            direction = AudioDevice.Direction.Playback,
            isDefault = isDefault,
        )
    }

private fun enumeratePlaybackMixers(): List<Pair<Mixer.Info, Boolean>> {
    val results = mutableListOf<Pair<Mixer.Info, Boolean>>()
    var defaultSeen = false
    for (info in AudioSystem.getMixerInfo()) {
        val mixer = AudioSystem.getMixer(info)
        val hasSource = mixer.sourceLineInfo.any { SourceDataLine::class.java.isAssignableFrom(it.lineClass) }
        if (!hasSource) continue
        val isDefault = !defaultSeen
        if (isDefault) defaultSeen = true
        results += info to isDefault
    }
    return results
}

private class JavaSoundPlayback : AudioPlayback {

    private var line: SourceDataLine? = null
    private val scratch =
        ByteArray(AudioConstants.SAMPLES_PER_FRAME * AudioConstants.BYTES_PER_SAMPLE)

    override fun start(deviceId: String?) {
        if (line != null) return
        val format = AudioFormat(
            AudioConstants.SAMPLE_RATE_HZ.toFloat(),
            BITS_PER_SAMPLE,
            AudioConstants.CHANNELS_MONO,
            SIGNED,
            BIG_ENDIAN,
        )
        val bufferBytes = (AudioConstants.SAMPLE_RATE_HZ * INTERNAL_BUFFER_MS / 1000) *
            AudioConstants.CHANNELS_MONO * AudioConstants.BYTES_PER_SAMPLE
        val info = DataLine.Info(SourceDataLine::class.java, format, bufferBytes)
        val opened = openLine(deviceId, info, format, bufferBytes)
        opened.start()
        line = opened
    }

    private fun openLine(
        deviceId: String?,
        info: DataLine.Info,
        format: AudioFormat,
        bufferBytes: Int,
    ): SourceDataLine {
        val mixerInfo = deviceId?.let { id ->
            AudioSystem.getMixerInfo().firstOrNull { it.name == id }
        }
        val line = if (mixerInfo != null) {
            AudioSystem.getMixer(mixerInfo).getLine(info) as SourceDataLine
        } else {
            AudioSystem.getSourceDataLine(format)
        }
        line.open(format, bufferBytes)
        return line
    }

    override fun stop() {
        line?.let {
            it.drain()
            it.stop()
            it.close()
        }
        line = null
    }

    override fun write(pcm: ShortArray) {
        val l = checkNotNull(line) { "AudioPlayback not started" }
        require(pcm.size == AudioConstants.SAMPLES_PER_FRAME) {
            "Frame must be ${AudioConstants.SAMPLES_PER_FRAME} samples, got ${pcm.size}"
        }
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            scratch[i * 2] = (v and 0xFF).toByte()
            scratch[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        l.write(scratch, 0, scratch.size)
    }

    override fun close() {
        stop()
    }
}
