package dev.puklic.voice.pipeline

import dev.puklic.voice.AudioConstants
import dev.puklic.voice.audio.AudioPlayback
import dev.puklic.voice.codec.OpusDecoder
import dev.puklic.voice.crypto.xchacha20Poly1305
import dev.puklic.voice.transport.UdpRtpTransport
import dev.puklic.voice.transport.VoicePacketCodec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test

class PlaybackPipelineTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `mixInto sums and clamps`() {
        val dst = ShortArray(4) { 0 }
        PlaybackPipeline.mixInto(dst, shortArrayOf(100, 200, 300, 400))
        PlaybackPipeline.mixInto(dst, shortArrayOf(50, 50, 50, 50))
        dst.toList() shouldBe listOf<Short>(150, 250, 350, 450)
    }

    @Test
    fun `mixInto clamps overflow to Short MAX`() {
        val dst = ShortArray(2) { Short.MAX_VALUE }
        PlaybackPipeline.mixInto(dst, shortArrayOf(1000, 2000))
        dst[0] shouldBe Short.MAX_VALUE
        dst[1] shouldBe Short.MAX_VALUE
    }

    @Test
    fun `mixInto clamps underflow to Short MIN`() {
        val dst = ShortArray(2) { Short.MIN_VALUE }
        PlaybackPipeline.mixInto(dst, shortArrayOf(-1000, -2000))
        dst[0] shouldBe Short.MIN_VALUE
        dst[1] shouldBe Short.MIN_VALUE
    }

    @Test
    fun `mixes two SSRCs and reports both speakers`() {
        runBlocking { runMixTest() }
    }

    private suspend fun runMixTest() {
        val key = ByteArray(32) { 1 }
        val sender1 = VoicePacketCodec(xchacha20Poly1305(key), ssrc = 111)
        val sender2 = VoicePacketCodec(xchacha20Poly1305(key), ssrc = 222)
        val opus1 = byteArrayOf(1, 2, 3)
        val opus2 = byteArrayOf(4, 5, 6)
        val transport = FakeTransport().apply {
            offer(sender1.encode(opus1))
            offer(sender2.encode(opus2))
        }
        val pcm1 = ShortArray(AudioConstants.SAMPLES_PER_FRAME) { 100 }
        val pcm2 = ShortArray(AudioConstants.SAMPLES_PER_FRAME) { 200 }
        val decoderFactory: () -> OpusDecoder = {
            // First decoder yields pcm1, second yields pcm2 — order matches SSRC creation.
            val emitted = AtomicReference<ShortArray>()
            object : OpusDecoder {
                override fun decode(opus: ByteArray?, fec: Boolean): ShortArray {
                    val out = if (emitted.compareAndSet(null, pcm1)) pcm1 else pcm2
                    return out
                }
                override fun close() {}
            }
        }
        // Use a stateful factory that hands out distinct PCM per SSRC.
        val perCallFactory = SequencedDecoderFactory(pcm1, pcm2)
        val playback = FakePlayback()
        val receivedSpeakers = mutableListOf<Set<Int>>()
        val receiver = VoicePacketCodec(xchacha20Poly1305(key), ssrc = 0)
        val pipeline = PlaybackPipeline(
            transport = transport,
            packetCodec = receiver,
            decoderFactory = perCallFactory::create,
            playback = playback,
            onSpeakers = { synchronized(receivedSpeakers) { receivedSpeakers += it } },
        )
        pipeline.start(scope)
        try {
            withTimeout(2_000) {
                while (playback.written.isEmpty()) delay(10)
                // Wait a bit more so both packets are processed.
                delay(100)
            }
        } finally {
            pipeline.stop()
        }
        // At least one mixed frame written.
        playback.written.isNotEmpty() shouldBe true
        // Find a frame where both SSRCs contributed: value should be 100+200=300 at sample 0.
        val mixed = playback.written.firstOrNull { it[0].toInt() == 300 }
        (mixed != null) shouldBe true
        // The onSpeakers callback should have been called with both SSRCs at some point.
        val anyBoth = synchronized(receivedSpeakers) {
            receivedSpeakers.any { it.containsAll(setOf(111, 222)) }
        }
        anyBoth shouldBe true
    }

    @Suppress("RedundantSuppression")
    private class FakeTransport : UdpRtpTransport {
        private val queue = ConcurrentLinkedQueue<ByteArray>()
        fun offer(b: ByteArray) { queue.add(b) }
        override suspend fun bind(): Int = 0
        override suspend fun connect(host: String, port: Int) {}
        override suspend fun send(packet: ByteArray) {}
        override suspend fun receive(): ByteArray {
            while (true) {
                val b = queue.poll()
                if (b != null) return b
                delay(5)
            }
            @Suppress("UNREACHABLE_CODE") return ByteArray(0)
        }
        override fun close() {}
    }

    private class FakePlayback : AudioPlayback {
        val written: MutableList<ShortArray> = java.util.Collections.synchronizedList(mutableListOf())
        override fun start(deviceId: String?) {}
        override fun stop() {}
        override fun write(pcm: ShortArray) {
            written += pcm.copyOf()
            // Throttle so the test doesn't burn CPU.
            Thread.sleep(5)
        }
        override fun close() {}
    }

    private class SequencedDecoderFactory(private val pcms: ShortArray, private val pcm2: ShortArray) {
        private var count = 0
        @Synchronized
        fun create(): OpusDecoder {
            val mine = if (count == 0) pcms else pcm2
            count++
            return object : OpusDecoder {
                override fun decode(opus: ByteArray?, fec: Boolean): ShortArray = mine
                override fun close() {}
            }
        }
    }
}
