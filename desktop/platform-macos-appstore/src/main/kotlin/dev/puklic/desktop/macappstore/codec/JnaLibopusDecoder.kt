package dev.puklic.desktop.macappstore.codec

import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import dev.puklic.desktop.macappstore.bridge.Libopus
import dev.puklic.voice.AudioConstants
import dev.puklic.voice.codec.OpusDecoder
import dev.puklic.voice.codec.OpusException

/**
 * Mac App Store variant of [OpusDecoder]. Backed by the bundled `libopus.dylib`
 * via JNA. Mirrors the iOS impl [`IosOpusDecoder`][1] — interleaved 48 kHz
 * signed-16 PCM out, PLC on null packet, optional FEC payload decode.
 *
 * NOT thread-safe — one instance per remote SSRC.
 *
 * [1]: shared/voice-codec/src/iosMain/kotlin/dev/puklic/voice/codec/IosOpusCodec.kt
 */
public class JnaLibopusDecoder(
    public override val channels: Int = AudioConstants.CHANNELS_MONO,
) : OpusDecoder {

    private val outputSize: Int = AudioConstants.SAMPLES_PER_FRAME * channels
    private val frameSize: Int = AudioConstants.SAMPLES_PER_FRAME

    private val opus: Libopus = Libopus.INSTANCE
    private val state: Pointer
    private var closed: Boolean = false

    init {
        require(channels == AudioConstants.CHANNELS_MONO || channels == AudioConstants.CHANNELS_STEREO) {
            "Opus channels must be 1 (mono) or 2 (stereo), got $channels"
        }
        val errRef = IntByReference(0)
        val st = opus.opus_decoder_create(AudioConstants.SAMPLE_RATE_HZ, channels, errRef)
        if (st == null || errRef.value != 0) {
            throw OpusException("opus_decoder_create failed", errRef.value)
        }
        state = st
    }

    public override fun decode(opus: ByteArray?, fec: Boolean): ShortArray {
        check(!closed) { "JnaLibopusDecoder is closed" }
        val out = ShortArray(outputSize)
        val rc = if (opus != null && opus.isNotEmpty()) {
            this.opus.opus_decode(state, opus, opus.size, out, frameSize, if (fec) 1 else 0)
        } else {
            // libopus contract: null data + 0 length → packet-loss concealment.
            this.opus.opus_decode(state, null, 0, out, frameSize, 0)
        }
        if (rc < 0) throw OpusException("opus_decode failed", rc)
        return out
    }

    public override fun close() {
        if (!closed) {
            closed = true
            opus.opus_decoder_destroy(state)
        }
    }
}
