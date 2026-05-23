package dev.puklic.voice.pipeline

import dev.puklic.voice.AudioConstants
import dev.puklic.voice.audio.AudioCapture
import dev.puklic.voice.codec.OpusEncoder
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test

class CapturePipelineTest {

    private fun loudFrame(): ShortArray = ShortArray(AudioConstants.SAMPLES_PER_FRAME) { 10_000 }
    private fun silentFrame(): ShortArray = ShortArray(AudioConstants.SAMPLES_PER_FRAME) { 0 }

    private class FakeCapture(frames: List<ShortArray>) : AudioCapture {
        private val q = LinkedBlockingQueue<ShortArray>().also { q -> frames.forEach { q.put(it) } }
        val started = AtomicBoolean(false)
        override fun start(deviceId: String?) { started.set(true) }
        override fun stop() { started.set(false) }
        override fun read(): ShortArray {
            // After exhausting the queue, block forever so the loop can be cancelled
            // by stop().
            return q.poll() ?: run { Thread.sleep(60_000); ShortArray(0) }
        }
        override fun close() = stop()
    }

    private class FakeEncoder : OpusEncoder {
        var calls = 0
        override fun encode(pcm: ShortArray): ByteArray {
            calls++
            // Tag encoded payload with first sample so tests can tell loud vs silent.
            return byteArrayOf((pcm.firstOrNull()?.toInt()?.shr(8) ?: 0).toByte())
        }
        override fun close() {}
    }

    @Test
    fun `loud frames produce packets and emit speaking on`(): Unit = runBlocking {
        val capture = FakeCapture(listOf(loudFrame(), loudFrame(), loudFrame()))
        val encoder = FakeEncoder()
        val sent = mutableListOf<ByteArray>()
        val speakingEvents = mutableListOf<Boolean>()

        val pipeline = CapturePipeline(
            capture = capture,
            encoder = encoder,
            encodeAndSend = { synchronized(sent) { sent.add(it) } },
            onSpeakingChange = { speakingEvents.add(it) },
        )

        val scope = CoroutineScope(Dispatchers.IO)
        pipeline.start(scope)
        // Wait for the three loud frames to flow through.
        withTimeout(2_000) {
            while (synchronized(sent) { sent.size } < 3) Thread.sleep(5)
        }
        pipeline.stop()

        synchronized(sent) { sent.size } shouldBeGreaterThanOrEqual 3
        speakingEvents.firstOrNull() shouldBe true
        encoder.calls shouldBeGreaterThanOrEqual 3
    }

    @Test
    fun `mute unmute cycle preserves encoder and resumes sending`(): Unit = runBlocking {
        // Endless loud frames so the test only depends on the mute flag.
        val capture = object : AudioCapture {
            override fun start(deviceId: String?) {}
            override fun stop() {}
            override fun read(): ShortArray = loudFrame()
            override fun close() {}
        }
        val encoder = FakeEncoder()
        val sent = mutableListOf<ByteArray>()
        val speakingEvents = mutableListOf<Boolean>()

        val pipeline = CapturePipeline(
            capture = capture,
            encoder = encoder,
            encodeAndSend = { synchronized(sent) { sent.add(it) } },
            onSpeakingChange = { speakingEvents.add(it) },
        )

        val scope = CoroutineScope(Dispatchers.IO)
        pipeline.start(scope)

        suspend fun waitForGrowth(prev: Int, atLeast: Int) {
            withTimeout(2_000) {
                while (synchronized(sent) { sent.size } < prev + atLeast) {
                    kotlinx.coroutines.delay(5)
                }
            }
        }

        // Cycle 3x: each iteration must produce new packets after unmute.
        repeat(3) {
            waitForGrowth(prev = synchronized(sent) { sent.size }, atLeast = 5)
            val beforeMute = synchronized(sent) { sent.size }
            pipeline.setMuted(true)
            // Allow loop to observe the flag and drain any in-flight frame.
            kotlinx.coroutines.delay(100)
            val afterMute = synchronized(sent) { sent.size }
            pipeline.setMuted(false)
            // Now expect new packets within a short window.
            withTimeout(2_000) {
                while (synchronized(sent) { sent.size } <= afterMute + 3) {
                    kotlinx.coroutines.delay(5)
                }
            }
            // While muted, no significant growth.
            (afterMute - beforeMute) shouldBeLessThanOrEqual 3
        }

        pipeline.stop()
        // Encoder kept being reused (not rebuilt across cycles).
        encoder.calls shouldBeGreaterThanOrEqual 15
    }

    @Test
    fun `silence after speech ends with 5 silence frames and speaking off`(): Unit = runBlocking {
        // 2 loud frames, then plenty of silent frames to trigger stop.
        val frames = buildList {
            add(loudFrame())
            add(loudFrame())
            repeat(CapturePipeline.SILENCE_FRAMES_TO_STOP + 2) { add(silentFrame()) }
        }
        val capture = FakeCapture(frames)
        val encoder = FakeEncoder()
        val sent = mutableListOf<ByteArray>()
        val speakingEvents = mutableListOf<Boolean>()

        val pipeline = CapturePipeline(
            capture = capture,
            encoder = encoder,
            encodeAndSend = { synchronized(sent) { sent.add(it) } },
            onSpeakingChange = { speakingEvents.add(it) },
        )

        val scope = CoroutineScope(Dispatchers.IO)
        pipeline.start(scope)
        withTimeout(5_000) {
            while (true) {
                val snapshot = synchronized(sent) { sent.toList() }
                val tailSilence = snapshot.takeLast(CapturePipeline.SILENCE_FRAMES_TAIL)
                    .all { it.contentEquals(CapturePipeline.OPUS_SILENCE_FRAME) }
                if (speakingEvents.size >= 2 && tailSilence && snapshot.size >= CapturePipeline.SILENCE_FRAMES_TAIL) break
                Thread.sleep(5)
            }
        }
        pipeline.stop()

        speakingEvents shouldContainInOrder listOf(true, false)
        val tail = sent.takeLast(CapturePipeline.SILENCE_FRAMES_TAIL)
        tail.size shouldBe CapturePipeline.SILENCE_FRAMES_TAIL
        tail.forEach { it.toList() shouldBe CapturePipeline.OPUS_SILENCE_FRAME.toList() }
    }
}
