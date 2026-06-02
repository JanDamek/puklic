package dev.puklic.voice.pipeline

import co.touchlab.kermit.Logger
import dev.puklic.voice.codec.PuklicVoiceCodec
import dev.puklic.voice.codec.video.H264Decoder
import dev.puklic.voice.codec.video.H264DecoderFactory
import dev.puklic.voice.transport.H264Depacketizer
import dev.puklic.voice.transport.RtpPacket
import dev.puklic.voice.transport.VoicePacketCodec
import dev.puklic.voice.transport.VoicePacketDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Receives video RTP packets from [dispatcher], decrypts them with [packetCodec], depacketizes
 * per remote SSRC into Annex-B access units, decodes via an injected [H264DecoderFactory] and
 * exposes the latest decoded frame per SSRC as a [StateFlow].
 *
 * Phase 4.2 (architect report 2026-05-23-screenshare.md §10 slice 5). H.264 only in v1 —
 * VP8 packets are dropped here (added later if needed). Decoder allocation is lazy per SSRC
 * and torn down on [stop].
 *
 * FP-14h-5 (architect report 2026-06-02-fp14h-applevoiceclient-promotion-plan.md §2):
 * promoted from `:shared:voice/jvmMain` to `:shared:voice-codec/commonMain`. The previous
 * direct dependency on the libavcodec-backed `H264Decoder` is replaced with the KMP-clean
 * `H264DecoderFactory` SPI. JVM wires a [H264DecoderFactory] backed by libavcodec
 * (`LibavH264DecoderFactory` in `:shared:voice/jvmMain`); iOS wires `IosH264DecoderFactory`;
 * Mac App Store wires `JnaVideoToolboxH264DecoderFactory`. JVM-only concurrency primitives
 * are replaced with KMP-portable equivalents: `ConcurrentHashMap` → [MutableMap] guarded by
 * [Mutex]. The runLoop is the sole writer to the SSRC → decoder/depacketizer maps; the mutex
 * serialises structural changes against [stop]. The width / height arguments are factory
 * *hints* — Apple VideoToolbox uses them to pre-size the destination pixel buffer; libavcodec
 * ignores them.
 */
@PuklicVoiceCodec
public class IncomingVideoPipeline(
    private val dispatcher: VoicePacketDispatcher,
    private val packetCodec: VoicePacketCodec,
    private val decoderFactory: H264DecoderFactory,
    private val widthHint: Int = DEFAULT_WIDTH_HINT,
    private val heightHint: Int = DEFAULT_HEIGHT_HINT,
) {
    private val mutex = Mutex()
    private val depacketizers: MutableMap<Int, H264Depacketizer> = mutableMapOf()
    private val decoders: MutableMap<Int, H264Decoder> = mutableMapOf()

    private val _frames = MutableStateFlow<Map<Int, H264Decoder.DecodedFrame>>(emptyMap())
    public val frames: StateFlow<Map<Int, H264Decoder.DecodedFrame>> = _frames.asStateFlow()

    private var job: Job? = null

    public fun start(scope: CoroutineScope) {
        check(job == null) { "IncomingVideoPipeline already started" }
        job = scope.launch(Dispatchers.Default) { runLoop() }
    }

    public suspend fun stop() {
        job?.cancel()
        job = null
        mutex.withLock {
            decoders.values.forEach { runCatching { it.close() } }
            decoders.clear()
            depacketizers.clear()
        }
        _frames.value = emptyMap()
    }

    private suspend fun runLoop() {
        while (isActive()) {
            val raw = try {
                dispatcher.videoPackets()
            } catch (_: Exception) {
                return
            }
            val decoded = try {
                packetCodec.decode(raw)
            } catch (_: Exception) {
                continue
            }
            if (decoded.header.payloadType != RtpPacket.PAYLOAD_TYPE_H264) continue
            val ssrc = decoded.header.ssrc
            val depacketizer = mutex.withLock {
                depacketizers.getOrPut(ssrc) { H264Depacketizer() }
            }
            val annexB = depacketizer
                .push(decoded.opus, marker = decoded.header.marker)
                ?: continue
            val decoder = mutex.withLock {
                decoders.getOrPut(ssrc) {
                    runCatching { decoderFactory.create(widthHint, heightHint) }.getOrElse {
                        Logger.w(TAG) { "H264 decoder init failed for ssrc=$ssrc: ${it.message}" }
                        return
                    }
                }
            }
            val frame = try {
                decoder.decode(annexB)
            } catch (t: Throwable) {
                Logger.w(TAG) { "H264 decode failed for ssrc=$ssrc: ${t.message}" }
                null
            } ?: continue
            _frames.value = _frames.value + (ssrc to frame)
        }
    }

    private suspend fun isActive(): Boolean =
        currentCoroutineContext()[Job]?.isActive ?: false

    private companion object {
        const val TAG = "IncomingVideoPipeline"

        // Hint passed to factory.create(). Apple platforms use these for the destination
        // CVPixelBuffer pre-allocation but downscale to the actual SPS dimensions on the
        // first decoded slice; libavcodec ignores them. 1920x1080 covers the common
        // Discord screen-cast cap; oversized streams still decode correctly because the
        // hints are not enforced.
        const val DEFAULT_WIDTH_HINT = 1920
        const val DEFAULT_HEIGHT_HINT = 1080
    }
}
