package dev.puklic.repositories

import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test

class MessageOrchestratorTest {

    private val channelA = ChannelId(1L)
    private val channelB = ChannelId(2L)

    private data class Bundle(
        val orchestrator: MessageOrchestrator,
        val storage: FakeMessageStorage,
        val queue: FakeOutboundQueue,
        val gateway: FakeGatewayEventSource,
        val rest: FakeMessageGateway,
        val job: Job,
    ) {
        fun cleanup() { job.cancel() }
    }

    private fun setup(scope: TestScope): Bundle {
        val storage = FakeMessageStorage()
        val queue = FakeOutboundQueue()
        val gateway = FakeGatewayEventSource()
        val rest = FakeMessageGateway()
        val job = Job()
        val childScope = CoroutineScope(scope.coroutineContext + job)
        val orchestrator = MessageOrchestrator(
            sessionScope = childScope,
            gatewaySource = gateway,
            messageGateway = rest,
            storage = storage,
            outboundQueue = queue,
            nonceGenerator = { "fixed-nonce" },
            nowProvider = { Instant.fromEpochMilliseconds(123L) },
        )
        scope.testScheduler.runCurrent()
        return Bundle(orchestrator, storage, queue, gateway, rest, job)
    }

    @Test
    fun observeCommitted_emits_initial_storage_state() = runTest {
        val s = setup(this)
        s.storage.persist(message(id = 1L, channelId = channelA, ts = 1000L))
        s.storage.persist(message(id = 2L, channelId = channelA, ts = 2000L))

        val list = s.orchestrator.observeCommitted(channelA).first()
        list.map { it.id.value } shouldContainExactly listOf(1L, 2L)
        s.cleanup()
    }

    @Test
    fun gateway_message_created_persists() = runTest {
        val s = setup(this)
        s.gateway.emit(GatewayDomainEvent.MessageCreated(message(id = 5L, channelId = channelA, ts = 500L)))
        testScheduler.runCurrent()
        s.storage.findById(MessageId(5L))?.id?.value shouldBe 5L
        s.cleanup()
    }

    @Test
    fun gateway_message_deleted_removes_from_storage() = runTest {
        val s = setup(this)
        s.storage.persist(message(id = 9L, channelId = channelA, ts = 1L))
        s.gateway.emit(GatewayDomainEvent.MessageDeleted(channelA, MessageId(9L)))
        testScheduler.runCurrent()
        s.storage.findById(MessageId(9L)) shouldBe null
        s.cleanup()
    }

    @Test
    fun send_enqueues_pending_outbound() = runTest {
        val s = setup(this)
        val localId = s.orchestrator.send(channelA, "hi").getOrThrow()
        val pending = s.queue.findById(localId)
        pending?.content shouldBe "hi"
        pending?.nonce shouldBe "fixed-nonce"
        pending?.channelId shouldBe channelA
        s.cleanup()
    }

    @Test
    fun edit_calls_gateway_and_persists() = runTest {
        val s = setup(this)
        val result = s.orchestrator.edit(MessageId(7L), channelA, "edited")
        result.isSuccess shouldBe true
        s.rest.editCalls shouldContainExactly listOf(Triple(channelA, MessageId(7L), "edited"))
        s.storage.findById(MessageId(7L))?.rawContent shouldBe "edited"
        s.cleanup()
    }

    @Test
    fun delete_calls_gateway_and_removes_local() = runTest {
        val s = setup(this)
        s.storage.persist(message(id = 11L, channelId = channelA, ts = 1L))
        s.orchestrator.delete(MessageId(11L), channelA).isSuccess shouldBe true
        s.storage.findById(MessageId(11L)) shouldBe null
        s.cleanup()
    }

    @Test
    fun loadOlder_merges_into_storage_and_returns_count() = runTest {
        val s = setup(this)
        s.rest.loadOlderResponse = { _, _, _ ->
            Result.success(listOf(
                message(id = 100L, channelId = channelA, ts = 10L),
                message(id = 101L, channelId = channelA, ts = 20L),
            ))
        }
        val count = s.orchestrator.loadOlder(channelA, MessageId(999L)).getOrThrow()
        count shouldBe 2
        (s.storage.findById(MessageId(100L)) != null) shouldBe true
        (s.storage.findById(MessageId(101L)) != null) shouldBe true
        s.cleanup()
    }

    @Test
    fun hot_warm_limits_differ_and_active_channel_tracked() = runTest {
        val s = setup(this)
        (MessageOrchestrator.HOT_CHANNEL_LIMIT > MessageOrchestrator.WARM_CHANNEL_LIMIT) shouldBe true
        s.orchestrator.setActiveChannel(channelA)
        s.orchestrator.active.value shouldBe channelA
        s.orchestrator.setActiveChannel(channelB)
        s.orchestrator.active.value shouldBe channelB
        s.cleanup()
    }

    @Test
    fun observeOutbound_filters_to_channel() = runTest {
        val s = setup(this)
        s.orchestrator.send(channelA, "for-a")
        s.orchestrator.send(channelB, "for-b")
        val outA = s.orchestrator.observeOutbound(channelA).first()
        outA.map { it.content } shouldContainExactly listOf("for-a")
        s.cleanup()
    }
}
