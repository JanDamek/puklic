package dev.puklic.voice.pipeline

import dev.puklic.voice.codec.transport.VoiceUdpTransport
import dev.puklic.voice.codec.video.H264Decoder
import dev.puklic.voice.codec.video.H264DecoderFactory
import dev.puklic.voice.crypto.AeadCipher
import dev.puklic.voice.crypto.xchacha20Poly1305
import dev.puklic.voice.transport.RtpPacket
import dev.puklic.voice.transport.VoicePacketCodec
import dev.puklic.voice.transport.VoicePacketDispatcher
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * IncomingVideoPipeline contract test using a fake `H264DecoderFactory`:
 *
 *  - factory.create is invoked lazily, exactly once per remote SSRC seen
 *  - decoded frames surface via [IncomingVideoPipeline.frames]
 *  - [IncomingVideoPipeline.stop] closes every issued decoder and clears state
 */
class IncomingVideoPipelineTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `pipeline lazily creates one decoder per SSRC and surfaces frames`() {
        runBlocking { runDecodeTest() }
    }

    @Test
    fun `stop closes every issued decoder exactly once`() {
        runBlocking { runStopTest() }
    }

    private suspend fun runDecodeTest() {
        val key = ByteArray(32) { 7 }
        val aead = xchacha20Poly1305(key)
        val ssrcA = 4242
        val ssrcB = 5151
        val pktA = encodeVideoPacket(aead, ssrcA, 1, 100, byteArrayOf(0x65, 0x01, 0x02, 0x03))
        val pktB = encodeVideoPacket(aead, ssrcB, 1, 100, byteArrayOf(0x65, 0x04, 0x05, 0x06))

        val transport = FakeTransport().apply {
            offer(pktA)
            offer(pktB)
        }
        val dispatcher = VoicePacketDispatcher(transport)
        dispatcher.start(scope)

        val factory = RecordingDecoderFactory()
        val pipeline = IncomingVideoPipeline(
            dispatcher = dispatcher,
            packetCodec = VoicePacketCodec(xchacha20Poly1305(key), ssrc = 0),
            decoderFactory = factory,
        )
        pipeline.start(scope)
        val frames: Map<Int, H264Decoder.DecodedFrame>
        try {
            withTimeout(2_000) {
                while (pipeline.frames.value.size < 2) delay(10)
            }
            // Snapshot before stop() — stop() clears _frames to free decoder references.
            frames = pipeline.frames.value
        } finally {
            pipeline.stop()
            dispatcher.stop()
        }
        frames.keys shouldBe setOf(ssrcA, ssrcB)
        frames[ssrcA]?.width shouldBe 1
        frames[ssrcA]?.height shouldBe 1
        factory.createCount.get() shouldBe 2
    }

    private suspend fun runStopTest() {
        val key = ByteArray(32) { 2 }
        val aead = xchacha20Poly1305(key)
        val ssrc = 9999
        val transport = FakeTransport().apply {
            offer(encodeVideoPacket(aead, ssrc, 1, 100, byteArrayOf(0x65, 0xA.toByte(), 0xB.toByte(), 0xC.toByte())))
        }
        val dispatcher = VoicePacketDispatcher(transport)
        dispatcher.start(scope)

        val factory = RecordingDecoderFactory()
        val pipeline = IncomingVideoPipeline(
            dispatcher = dispatcher,
            packetCodec = VoicePacketCodec(xchacha20Poly1305(key), ssrc = 0),
            decoderFactory = factory,
        )
        pipeline.start(scope)
        withTimeout(2_000) {
            while (factory.createCount.get() == 0) delay(10)
        }
        pipeline.stop()
        dispatcher.stop()
        factory.closedCount.get() shouldBe factory.createCount.get()
        pipeline.frames.value shouldBe emptyMap()
    }

    /**
     * Build a video packet directly (`VoicePacketCodec.encode` is audio-only — it hardcodes
     * Opus PT, and the header bytes are AEAD-AAD so post-hoc patching breaks the MAC).
     */
    private fun encodeVideoPacket(
        aead: AeadCipher,
        ssrc: Int,
        sequence: Int,
        timestamp: Int,
        nal: ByteArray,
    ): ByteArray {
        val header = RtpPacket.writeHeader(
            sequence = sequence.toShort(),
            timestamp = timestamp,
            ssrc = ssrc,
            payloadType = RtpPacket.PAYLOAD_TYPE_H264,
            marker = true,
        )
        val nonceCounter = NONCE_SEQ.getAndIncrement()
        val nonce24 = VoicePacketCodec.buildNonce(nonceCounter)
        val ciphertext = aead.encrypt(nal, nonce24, header)
        val packet = ByteArray(RtpPacket.HEADER_SIZE + ciphertext.size + VoicePacketCodec.NONCE_COUNTER_SIZE)
        header.copyInto(packet, destinationOffset = 0)
        ciphertext.copyInto(packet, destinationOffset = RtpPacket.HEADER_SIZE)
        VoicePacketCodec.writeIntBE(packet, packet.size - VoicePacketCodec.NONCE_COUNTER_SIZE, nonceCounter)
        return packet
    }

    private class FakeTransport : VoiceUdpTransport {
        private val channel: Channel<ByteArray> = Channel(capacity = Channel.UNLIMITED)
        fun offer(b: ByteArray) { channel.trySend(b) }
        override suspend fun send(packet: ByteArray) {}
        override val incoming: Flow<ByteArray> = channel.receiveAsFlow()
        override fun close() { channel.close() }
    }

    private class RecordingDecoderFactory : H264DecoderFactory {
        val createCount = AtomicInteger(0)
        val closedCount = AtomicInteger(0)

        override fun create(width: Int, height: Int): H264Decoder {
            createCount.incrementAndGet()
            return object : H264Decoder {
                override fun decode(annexBNalUnit: ByteArray): H264Decoder.DecodedFrame? {
                    // Echo the first three payload bytes (skip 4-byte start code + 1-byte NAL
                    // header) as RGB, alpha 0. Any non-empty input produces a 1×1 frame.
                    val payload = if (annexBNalUnit.size >= START_CODE + NAL_HEADER + 3) {
                        byteArrayOf(
                            annexBNalUnit[START_CODE + NAL_HEADER],
                            annexBNalUnit[START_CODE + NAL_HEADER + 1],
                            annexBNalUnit[START_CODE + NAL_HEADER + 2],
                            0,
                        )
                    } else byteArrayOf(0, 0, 0, 0)
                    return H264Decoder.DecodedFrame(rgba = payload, width = 1, height = 1)
                }

                override fun close() {
                    closedCount.incrementAndGet()
                }
            }
        }

        companion object {
            const val START_CODE = 4
            const val NAL_HEADER = 1
        }
    }

    private companion object {
        val NONCE_SEQ = AtomicInteger(1)
    }
}
