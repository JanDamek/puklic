package dev.puklic.voice.transport

import dev.puklic.voice.codec.transport.VoiceUdpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Single owner of the UDP receive loop. Reads raw RTP packets from [transport]'s incoming
 * Flow, peeks at the RTP payload type without decrypting, and forwards the raw bytes to
 * either the audio or video channel. Each consumer pulls packets via [audioPackets] /
 * [videoPackets].
 *
 * Why peek at the header bytes here instead of letting each consumer try AEAD-decrypt and
 * filter: the audio and video paths use the same XChaCha20 key but independent nonce
 * counters (architect report 2026-05-23-screenshare.md §5). Cross-feeding decrypt attempts
 * would advance the wrong counter and burn cycles on guaranteed failures.
 *
 * The dispatcher does NOT decrypt — it merely reads the unencrypted RTP header byte 1 and
 * routes by payload type. Each pipeline owns its own [VoicePacketCodec] for AEAD.
 */
internal class VoicePacketDispatcher(
    private val transport: VoiceUdpTransport,
) {
    private val audioChannel: Channel<ByteArray> = Channel(capacity = AUDIO_CAPACITY)
    private val videoChannel: Channel<ByteArray> = Channel(capacity = VIDEO_CAPACITY)
    private var job: Job? = null

    suspend fun audioPackets(): ByteArray = audioChannel.receive()
    suspend fun videoPackets(): ByteArray = videoChannel.receive()

    fun start(scope: CoroutineScope) {
        check(job == null) { "VoicePacketDispatcher already started" }
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        audioChannel.close()
        videoChannel.close()
    }

    private suspend fun runLoop() {
        try {
            transport.incoming.collect { packet ->
                if (packet.size < RtpPacket.HEADER_SIZE) return@collect
                // Byte 1 low 7 bits = payload type. We tolerate non-RTP control packets (e.g.
                // stray IP-discovery responses) silently by checking against our known PTs.
                val pt = (packet[1].toInt() and 0x7F).toByte()
                when (pt) {
                    RtpPacket.PAYLOAD_TYPE_OPUS -> audioChannel.trySend(packet)
                    RtpPacket.PAYLOAD_TYPE_H264, RtpPacket.PAYLOAD_TYPE_VP8 -> videoChannel.trySend(packet)
                    else -> { /* drop */ }
                }
            }
        } catch (_: Exception) {
            // Socket closed or coroutine cancelled — exit cleanly.
        }
    }

    companion object {
        // Audio is paced by playback (~50 fps), generous buffer protects against bursts.
        private const val AUDIO_CAPACITY = 64
        // Video FU-A fragments at 30 fps × ~10 fragments/frame ≈ 300 pps; 128 absorbs jitter.
        private const val VIDEO_CAPACITY = 128
    }
}
