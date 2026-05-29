@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.puklic.voice.codec

import dev.puklic.voice.AudioConstants
import cnames.structs.OpusDecoder as NativeOpusDecoder
import cnames.structs.OpusEncoder as NativeOpusEncoder
import dev.puklic.voice.codec.libopus.OPUS_APPLICATION_AUDIO
import dev.puklic.voice.codec.libopus.OPUS_APPLICATION_RESTRICTED_LOWDELAY
import dev.puklic.voice.codec.libopus.OPUS_APPLICATION_VOIP
import dev.puklic.voice.codec.libopus.opus_decode
import dev.puklic.voice.codec.libopus.opus_decoder_create
import dev.puklic.voice.codec.libopus.opus_decoder_destroy
import dev.puklic.voice.codec.libopus.opus_encode
import dev.puklic.voice.codec.libopus.opus_encoder_create
import dev.puklic.voice.codec.libopus.opus_encoder_destroy
import dev.puklic.voice.codec.libopus.puklic_opus_encoder_set_bitrate
import dev.puklic.voice.codec.libopus.puklic_opus_encoder_set_complexity
import dev.puklic.voice.codec.libopus.puklic_opus_encoder_set_dtx
import dev.puklic.voice.codec.libopus.puklic_opus_encoder_set_inband_fec
import dev.puklic.voice.codec.libopus.puklic_opus_encoder_set_packet_loss_perc
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/**
 * iOS `actual` for [OpusCodecFactory]. Backed by upstream libopus 1.5.2
 * (BSD-3-Clause) statically linked via the prebuilt
 * `shared/voice-codec/libs/Opus.xcframework` bundle. Built by
 * `dist/apple/build-libopus-xcframework.sh`. SHA pinned in
 * `Opus.xcframework.sha256`.
 *
 * Behaviour mirrors the JVM `actual` ([LibavOpusEncoder] / [LibavOpusDecoder]
 * in `OpusCodec.jvm.kt`): mono/stereo 48 kHz 20 ms frames; FEC, DTX, VBR,
 * complexity, application all surfaced via libopus CTL macros (typed C
 * shims in `libopus.def`).
 */
public actual object OpusCodecFactory {

    public actual fun createEncoder(config: OpusEncoderConfig): OpusEncoder = IosOpusEncoder(config)

    public actual fun createDecoder(channels: Int): OpusDecoder = IosOpusDecoder(channels)
}

private const val DEFAULT_EXPECTED_PACKET_LOSS_PCT: Int = 10
private const val MAX_OPUS_PACKET_BYTES: Int = 4000

private fun OpusApplication.toNative(): Int = when (this) {
    OpusApplication.VoIp -> OPUS_APPLICATION_VOIP
    OpusApplication.Audio -> OPUS_APPLICATION_AUDIO
    OpusApplication.LowDelay -> OPUS_APPLICATION_RESTRICTED_LOWDELAY
}

@OptIn(ExperimentalForeignApi::class)
private class IosOpusEncoder(config: OpusEncoderConfig) : OpusEncoder {

    override val channels: Int = config.channels
    private val expectedPcmSize: Int = AudioConstants.SAMPLES_PER_FRAME * channels
    private val maxFrameSize: Int = AudioConstants.SAMPLES_PER_FRAME

    private val state: kotlinx.cinterop.CPointer<NativeOpusEncoder>
    // Encoder/decoder are documented as NOT thread-safe (one instance per stream),
    // so a plain flag is sufficient — no atomicity needed.
    private var closed: Boolean = false

    init {
        val (st, err) = memScoped {
            val errVar = alloc<IntVar>()
            val ptr = opus_encoder_create(
                AudioConstants.SAMPLE_RATE_HZ,
                channels,
                config.application.toNative(),
                errVar.ptr,
            )
            ptr to errVar.value
        }
        if (st == null || err != 0) {
            throw OpusException("opus_encoder_create failed", err)
        }
        state = st

        checkCtl("OPUS_SET_BITRATE", puklic_opus_encoder_set_bitrate(state, config.bitrate))
        checkCtl("OPUS_SET_COMPLEXITY", puklic_opus_encoder_set_complexity(state, config.complexity))
        checkCtl(
            "OPUS_SET_INBAND_FEC",
            puklic_opus_encoder_set_inband_fec(state, if (config.inbandFec) 1 else 0),
        )
        if (config.inbandFec) {
            // libopus only emits FEC when expected-loss > 0. Match the JVM impl's
            // default (10 %, Discord's voice-channel tolerance).
            checkCtl(
                "OPUS_SET_PACKET_LOSS_PERC",
                puklic_opus_encoder_set_packet_loss_perc(state, DEFAULT_EXPECTED_PACKET_LOSS_PCT),
            )
        }
        checkCtl("OPUS_SET_DTX", puklic_opus_encoder_set_dtx(state, if (config.dtx) 1 else 0))
    }

    override fun encode(pcm: ShortArray): ByteArray {
        check(!closed) { "OpusEncoder is closed" }
        require(pcm.size == expectedPcmSize) {
            "Frame must be $expectedPcmSize samples " +
                "(${AudioConstants.SAMPLES_PER_FRAME} per channel * $channels channels), got ${pcm.size}"
        }

        return pcm.usePinned { pinnedIn ->
            val outBytes = ByteArray(MAX_OPUS_PACKET_BYTES)
            val written = outBytes.usePinned { pinnedOut ->
                opus_encode(
                    state,
                    pinnedIn.addressOf(0),
                    maxFrameSize,
                    pinnedOut.addressOf(0).reinterpret(),
                    MAX_OPUS_PACKET_BYTES,
                )
            }
            if (written < 0) throw OpusException("opus_encode failed", written)
            outBytes.copyOf(written)
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            opus_encoder_destroy(state)
        }
    }

    private fun checkCtl(name: String, rc: Int) {
        if (rc < 0) throw OpusException("opus_encoder_ctl($name) failed", rc)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosOpusDecoder(override val channels: Int) : OpusDecoder {

    private val outputSize: Int = AudioConstants.SAMPLES_PER_FRAME * channels
    private val maxFrameSize: Int = AudioConstants.SAMPLES_PER_FRAME

    private val state: kotlinx.cinterop.CPointer<NativeOpusDecoder>
    // Encoder/decoder are documented as NOT thread-safe (one instance per stream),
    // so a plain flag is sufficient — no atomicity needed.
    private var closed: Boolean = false

    init {
        require(channels == AudioConstants.CHANNELS_MONO || channels == AudioConstants.CHANNELS_STEREO) {
            "Opus channels must be 1 (mono) or 2 (stereo), got $channels"
        }
        val (st, err) = memScoped {
            val errVar = alloc<IntVar>()
            val ptr = opus_decoder_create(
                AudioConstants.SAMPLE_RATE_HZ,
                channels,
                errVar.ptr,
            )
            ptr to errVar.value
        }
        if (st == null || err != 0) {
            throw OpusException("opus_decoder_create failed", err)
        }
        state = st
    }

    override fun decode(opus: ByteArray?, fec: Boolean): ShortArray {
        check(!closed) { "OpusDecoder is closed" }
        // libopus contract: `data == NULL && len == 0` → packet-loss concealment (PLC).
        // `data != NULL && len > 0 && decode_fec == 1` → decode the FEC payload of the
        // next packet as if it were the missing previous packet.
        val out = ShortArray(outputSize)
        val rc = out.usePinned { pinnedOut ->
            if (opus != null && opus.isNotEmpty()) {
                opus.usePinned { pinnedIn ->
                    opus_decode(
                        state,
                        pinnedIn.addressOf(0).reinterpret(),
                        opus.size,
                        pinnedOut.addressOf(0),
                        maxFrameSize,
                        if (fec) 1 else 0,
                    )
                }
            } else {
                opus_decode(state, null, 0, pinnedOut.addressOf(0), maxFrameSize, 0)
            }
        }
        if (rc < 0) throw OpusException("opus_decode failed", rc)
        // libopus returns samples-per-channel; if rc < SAMPLES_PER_FRAME the
        // remaining tail in `out` is already zeroed (ShortArray default).
        return out
    }

    override fun close() {
        if (!closed) {
            closed = true
            opus_decoder_destroy(state)
        }
    }
}
