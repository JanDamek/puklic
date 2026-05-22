package dev.puklic.repositories

import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import dev.puklic.persistence.repository.OutboundState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test

class OutboundMessageWorkerTest {

    private val channel = ChannelId(7L)

    @Test
    fun success_persists_confirmed_message_and_deletes_outbound() = runTest {
        val queue = FakeOutboundQueue()
        val storage = FakeMessageStorage()
        val rest = FakeMessageGateway()
        val confirmed = message(id = 555L, channelId = channel, ts = 100L, content = "ok")
        rest.sendResponse = { _, _, _, _ -> Result.success(confirmed) }
        val worker = OutboundMessageWorker(this, queue, rest, storage, RetryBackoff.Fixed(0L))

        val localId = queue.enqueue(channel, "ok", null, "nonce-1", Instant.fromEpochMilliseconds(1L))
        worker.process(queue.findById(localId)!!)

        storage.findById(MessageId(555L))?.rawContent shouldBe "ok"
        queue.findById(localId) shouldBe null
    }

    @Test
    fun failure_marks_record_failed_and_keeps_in_queue() = runTest {
        val queue = FakeOutboundQueue()
        val storage = FakeMessageStorage()
        val rest = FakeMessageGateway()
        rest.sendResponse = { _, _, _, _ -> Result.failure(RuntimeException("boom")) }
        val worker = OutboundMessageWorker(this, queue, rest, storage, RetryBackoff.Fixed(0L))

        val localId = queue.enqueue(channel, "x", null, "n", Instant.fromEpochMilliseconds(1L))
        worker.process(queue.findById(localId)!!)

        val updated = queue.findById(localId)
        updated?.state shouldBe OutboundState.FAILED
        updated?.lastError shouldBe "boom"
    }

    @Test
    fun exponential_backoff_grows_then_caps() {
        val b = RetryBackoff.Exponential(initialMillis = 100L, multiplier = 2.0, capMillis = 1000L)
        b.delayMillisFor(1) shouldBe 100L
        b.delayMillisFor(2) shouldBe 200L
        b.delayMillisFor(3) shouldBe 400L
        b.delayMillisFor(10) shouldBe 1000L  // capped
        b.delayMillisFor(0) shouldBe 0L
    }

    @Test
    fun fixed_backoff_constant() {
        RetryBackoff.Fixed(250L).delayMillisFor(7) shouldBe 250L
    }
}
