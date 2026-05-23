package dev.puklic.voice.codec

import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import dev.puklic.voice.AudioConstants
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM actual for [OpusCodecFactory]. Uses [LibOpus] (JNA → system libopus).
 *
 * Frame size is hard-coded to mono 48 kHz 20 ms (960 samples). Stereo / non-20 ms
 * frames would require changes to [AudioConstants] and the capture pipeline; out of
 * scope for Phase 3.0.
 */
public actual object OpusCodecFactory {

    public actual fun createEncoder(config: OpusEncoderConfig): OpusEncoder =
        JvmOpusEncoder(config)

    public actual fun createDecoder(): OpusDecoder = JvmOpusDecoder()
}

private class JvmOpusEncoder(config: OpusEncoderConfig) : OpusEncoder {

    private val handle: Pointer
    private val closed = AtomicBoolean(false)
    private val scratch = ByteArray(AudioConstants.MAX_OPUS_FRAME_BYTES)

    init {
        val err = IntByReference()
        handle = LibOpus.INSTANCE.opus_encoder_create(
            AudioConstants.SAMPLE_RATE_HZ,
            AudioConstants.CHANNELS_MONO,
            OPUS_APPLICATION_VOIP,
            err,
        )
        if (err.value != OPUS_OK || handle == Pointer.NULL) {
            throw OpusException("opus_encoder_create failed", err.value)
        }
        applyCtl(OPUS_SET_BITRATE_REQUEST, config.bitrate, "bitrate")
        applyCtl(OPUS_SET_COMPLEXITY_REQUEST, config.complexity, "complexity")
        applyCtl(OPUS_SET_INBAND_FEC_REQUEST, if (config.inbandFec) 1 else 0, "inband_fec")
        applyCtl(OPUS_SET_DTX_REQUEST, if (config.dtx) 1 else 0, "dtx")
    }

    override fun encode(pcm: ShortArray): ByteArray {
        check(!closed.get()) { "OpusEncoder is closed" }
        require(pcm.size == AudioConstants.SAMPLES_PER_FRAME) {
            "Frame must be ${AudioConstants.SAMPLES_PER_FRAME} samples, got ${pcm.size}"
        }
        val written = LibOpus.INSTANCE.opus_encode(
            handle,
            pcm,
            AudioConstants.SAMPLES_PER_FRAME,
            scratch,
            scratch.size,
        )
        if (written < 0) {
            throw OpusException("opus_encode failed: ${LibOpus.INSTANCE.opus_strerror(written)}", written)
        }
        return scratch.copyOf(written)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            LibOpus.INSTANCE.opus_encoder_destroy(handle)
        }
    }

    private fun applyCtl(request: Int, value: Int, label: String) {
        // The trailing arg goes through libopus's va_arg(va, opus_int32); pass a boxed
        // Int so JNA forwards it as a 32-bit int via the platform's varargs ABI.
        val rc = LibOpus.INSTANCE.opus_encoder_ctl(handle, request, value as Any)
        if (rc != OPUS_OK) {
            LibOpus.INSTANCE.opus_encoder_destroy(handle)
            throw OpusException("opus_encoder_ctl($label=$value) failed", rc)
        }
    }
}

private class JvmOpusDecoder : OpusDecoder {

    private val handle: Pointer
    private val closed = AtomicBoolean(false)

    init {
        val err = IntByReference()
        handle = LibOpus.INSTANCE.opus_decoder_create(
            AudioConstants.SAMPLE_RATE_HZ,
            AudioConstants.CHANNELS_MONO,
            err,
        )
        if (err.value != OPUS_OK || handle == Pointer.NULL) {
            throw OpusException("opus_decoder_create failed", err.value)
        }
    }

    override fun decode(opus: ByteArray?, fec: Boolean): ShortArray {
        check(!closed.get()) { "OpusDecoder is closed" }
        val out = ShortArray(AudioConstants.SAMPLES_PER_FRAME)
        // PLC: data == null, len == 0, fec ignored. With data: fec=1 decodes the FEC
        // redundant payload _of the next packet_ as the previous lost frame.
        val decodeFec = if (opus != null && fec) 1 else 0
        val samples = LibOpus.INSTANCE.opus_decode(
            handle,
            opus,
            opus?.size ?: 0,
            out,
            AudioConstants.SAMPLES_PER_FRAME,
            decodeFec,
        )
        if (samples < 0) {
            throw OpusException("opus_decode failed: ${LibOpus.INSTANCE.opus_strerror(samples)}", samples)
        }
        return out
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            LibOpus.INSTANCE.opus_decoder_destroy(handle)
        }
    }
}
