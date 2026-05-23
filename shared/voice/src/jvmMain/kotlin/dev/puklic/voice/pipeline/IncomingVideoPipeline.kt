package dev.puklic.voice.pipeline

import co.touchlab.kermit.Logger
import dev.puklic.voice.codec.H264Decoder
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives video RTP packets from [dispatcher], decrypts them with [packetCodec], depacketizes
 * per remote SSRC into Annex-B access units, decodes via libavcodec H.264 in-process, and
 * exposes the latest decoded frame per SSRC as a [StateFlow].
 *
 * Phase 4.2 (architect report 2026-05-23-screenshare.md §10 slice 5). H.264 only in v1 —
 * VP8 packets are dropped here (added later if needed). Decoder allocation is lazy per SSRC
 * and torn down on [stop].
 */
internal class IncomingVideoPipeline(
    private val dispatcher: VoicePacketDispatcher,
    private val packetCodec: VoicePacketCodec,
) {
    private val depacketizers = ConcurrentHashMap<Int, H264Depacketizer>()
    private val decoders = ConcurrentHashMap<Int, H264Decoder>()

    private val _frames = MutableStateFlow<Map<Int, H264Decoder.DecodedFrame>>(emptyMap())
    val frames: StateFlow<Map<Int, H264Decoder.DecodedFrame>> = _frames.asStateFlow()

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        check(job == null) { "IncomingVideoPipeline already started" }
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        decoders.values.forEach {
            runCatching { it.close() }
        }
        decoders.clear()
        depacketizers.clear()
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
            val annexB = depacketizers.getOrPut(ssrc) { H264Depacketizer() }
                .push(decoded.opus, marker = decoded.header.marker)
                ?: continue
            val decoder = decoders.getOrPut(ssrc) {
                runCatching { H264Decoder() }.getOrElse {
                    Logger.w(TAG) { "H264Decoder init failed for ssrc=$ssrc: ${it.message}" }
                    return
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
    }
}
