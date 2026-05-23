package dev.puklic.voice.audio

import dev.puklic.voice.AudioConstants
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * JVM `actual` for [AudioCapture] using `javax.sound.sampled.TargetDataLine`.
 *
 * Per architect report 2026-05-23-voice.md §8: 48 kHz / 16-bit / mono / signed / little-endian,
 * blocking read of 1920 bytes = 960 samples = 20 ms per frame.
 */
internal actual fun audioCapture(): AudioCapture = JavaSoundCapture()

private class JavaSoundCapture : AudioCapture {

    private var line: TargetDataLine? = null
    private val frameBytes: Int =
        AudioConstants.SAMPLES_PER_FRAME * AudioConstants.BYTES_PER_SAMPLE
    private val readBuffer: ByteArray = ByteArray(frameBytes)

    override fun start(deviceId: String?) {
        if (line?.isOpen == true) return
        val format = AudioFormat(
            /* sampleRate = */ AudioConstants.SAMPLE_RATE_HZ.toFloat(),
            /* sampleSizeInBits = */ AudioConstants.BYTES_PER_SAMPLE * 8,
            /* channels = */ AudioConstants.CHANNELS_MONO,
            /* signed = */ true,
            /* bigEndian = */ false,
        )
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val target = openLineForDevice(deviceId, info, format)
        target.start()
        line = target
    }

    private fun openLineForDevice(
        deviceId: String?,
        info: DataLine.Info,
        format: AudioFormat,
    ): TargetDataLine {
        if (deviceId != null) {
            val match = AudioSystem.getMixerInfo()
                .firstOrNull { it.name.contains(deviceId, ignoreCase = false) }
            if (match != null) {
                val mixer = AudioSystem.getMixer(match)
                if (mixer.isLineSupported(info)) {
                    val tdl = mixer.getLine(info) as TargetDataLine
                    tdl.open(format, frameBytes * BUFFER_FRAMES)
                    return tdl
                }
            }
        }
        val tdl = AudioSystem.getLine(info) as TargetDataLine
        tdl.open(format, frameBytes * BUFFER_FRAMES)
        return tdl
    }

    override fun stop() {
        line?.let {
            it.stop()
            it.close()
        }
        line = null
    }

    override fun read(): ShortArray {
        val l = checkNotNull(line) { "AudioCapture not started" }
        var off = 0
        while (off < frameBytes) {
            val n = l.read(readBuffer, off, frameBytes - off)
            if (n <= 0) error("TargetDataLine returned $n; line closed?")
            off += n
        }
        val out = ShortArray(AudioConstants.SAMPLES_PER_FRAME)
        var bi = 0
        for (i in out.indices) {
            val lo = readBuffer[bi].toInt() and 0xFF
            val hi = readBuffer[bi + 1].toInt() // signed
            out[i] = ((hi shl 8) or lo).toShort()
            bi += 2
        }
        return out
    }

    override fun close() = stop()

    companion object {
        /** Internal line buffer in frames — large enough to avoid xruns under jitter. */
        private const val BUFFER_FRAMES: Int = 4
    }
}
