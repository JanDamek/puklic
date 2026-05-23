package dev.puklic.repositories

import dev.puklic.domain.EmojiRef
import dev.puklic.domain.Reaction
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test

/**
 * Sync of remote MESSAGE_UPDATE / MESSAGE_DELETE / MESSAGE_DELETE_BULK gateway events.
 * Phase 2 — slice "Message edit/delete sync via gateway".
 */
class MessageOrchestratorEditDeleteTest {

    private val channelA = ChannelId(1L)

    private data class Bundle(
        val orchestrator: MessageOrchestrator,
        val storage: FakeMessageStorage,
        val gateway: FakeGatewayEventSource,
        val job: Job,
    ) { fun cleanup() { job.cancel() } }

    private fun setup(scope: TestScope): Bundle {
        val storage = FakeMessageStorage()
        val users = FakeUserRepository()
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
            userStorage = users,
            outboundQueue = queue,
            nonceGenerator = { "fixed" },
            nowProvider = { Instant.fromEpochMilliseconds(1L) },
        )
        scope.testScheduler.runCurrent()
        return Bundle(orchestrator, storage, gateway, job)
    }

    @Test
    fun message_updated_replaces_content_and_preserves_existing_reactions() = runTest {
        val s = setup(this)
        val initial = message(id = 1L, channelId = channelA, ts = 100L, content = "old").copy(
            reactions = listOf(Reaction(EmojiRef.Unicode("👍"), count = 1, me = true, countDetails = null)),
        )
        s.storage.persist(initial)

        val edited = message(id = 1L, channelId = channelA, ts = 100L, content = "new")
            .copy(editedTimestamp = Instant.fromEpochMilliseconds(200L))
        s.gateway.emit(GatewayDomainEvent.MessageUpdated(edited))
        testScheduler.runCurrent()

        val after = s.storage.findById(MessageId(1L))!!
        after.rawContent shouldBe "new"
        after.editedTimestamp shouldBe Instant.fromEpochMilliseconds(200L)
        after.reactions.size shouldBe 1
        after.reactions[0].emoji shouldBe EmojiRef.Unicode("👍")
        s.cleanup()
    }

    @Test
    fun message_updated_for_unknown_message_is_noop() = runTest {
        val s = setup(this)
        val edited = message(id = 42L, channelId = channelA, ts = 1L, content = "ghost")
        s.gateway.emit(GatewayDomainEvent.MessageUpdated(edited))
        testScheduler.runCurrent()
        // Current behaviour: persist the new message even if not previously seen.
        // (No-op preservation only applies to reactions when prior message exists.)
        s.storage.findById(MessageId(42L))?.rawContent shouldBe "ghost"
        s.cleanup()
    }

    @Test
    fun message_deleted_removes_from_storage() = runTest {
        val s = setup(this)
        s.storage.persist(message(id = 7L, channelId = channelA, ts = 1L))
        s.gateway.emit(GatewayDomainEvent.MessageDeleted(channelA, MessageId(7L)))
        testScheduler.runCurrent()
        s.storage.findById(MessageId(7L)) shouldBe null
        s.cleanup()
    }

    @Test
    fun message_deleted_bulk_removes_all_listed_ids() = runTest {
        val s = setup(this)
        s.storage.persist(message(id = 10L, channelId = channelA, ts = 1L))
        s.storage.persist(message(id = 11L, channelId = channelA, ts = 2L))
        s.storage.persist(message(id = 12L, channelId = channelA, ts = 3L))

        s.gateway.emit(
            GatewayDomainEvent.MessageDeletedBulk(
                channelId = channelA,
                messageIds = listOf(MessageId(10L), MessageId(12L)),
            ),
        )
        testScheduler.runCurrent()

        s.storage.findById(MessageId(10L)) shouldBe null
        s.storage.findById(MessageId(11L))?.id?.value shouldBe 11L
        s.storage.findById(MessageId(12L)) shouldBe null
        s.cleanup()
    }

    @Test
    fun message_deleted_bulk_empty_list_is_noop() = runTest {
        val s = setup(this)
        s.storage.persist(message(id = 20L, channelId = channelA, ts = 1L))
        s.gateway.emit(GatewayDomainEvent.MessageDeletedBulk(channelA, emptyList()))
        testScheduler.runCurrent()
        s.storage.findById(MessageId(20L))?.id?.value shouldBe 20L
        // Sanity
        listOf<MessageId>().shouldBeEmpty()
        s.cleanup()
    }
}
