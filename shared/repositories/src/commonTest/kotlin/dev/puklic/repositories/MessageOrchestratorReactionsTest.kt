package dev.puklic.repositories

import dev.puklic.domain.EmojiRef
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import dev.puklic.ids.UserId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class MessageOrchestratorReactionsTest {

    private val channelA = ChannelId(1L)
    private val messageA = MessageId(10L)
    private val selfId = UserId(42L)
    private val otherId = UserId(99L)
    private val thumbsUp: EmojiRef = EmojiRef.Unicode("👍")
    private val heart: EmojiRef = EmojiRef.Unicode("❤️")

    private data class Bundle(
        val orchestrator: MessageOrchestrator,
        val storage: FakeMessageStorage,
        val gateway: FakeGatewayEventSource,
        val rest: FakeMessageGateway,
        val job: Job,
    ) { fun cleanup() { job.cancel() } }

    private fun setup(scope: TestScope, self: UserId? = selfId): Bundle {
        val storage = FakeMessageStorage()
        val users = FakeUserRepository()
        val queue = FakeOutboundQueue()
        val gateway = FakeGatewayEventSource()
        val rest = FakeMessageGateway()
        val job = Job()
        val childScope = CoroutineScope(scope.coroutineContext + job)
        val orch = MessageOrchestrator(
            sessionScope = childScope,
            gatewaySource = gateway,
            messageGateway = rest,
            storage = storage,
            userStorage = users,
            outboundQueue = queue,
            selfUserIdProvider = { self },
        )
        scope.testScheduler.runCurrent()
        return Bundle(orch, storage, gateway, rest, job)
    }

    @Test
    fun reactionAdded_by_other_user_increments_count_with_me_false() = runTest {
        val s = setup(this)
        s.storage.persist(message(id = messageA.value, channelId = channelA, ts = 1L))

        s.gateway.emit(GatewayDomainEvent.ReactionAdded(channelA, messageA, otherId, thumbsUp))
        testScheduler.runCurrent()

        val msg = s.storage.findById(messageA)!!
        msg.reactions shouldHaveSize 1
        msg.reactions[0].emoji shouldBe thumbsUp
        msg.reactions[0].count shouldBe 1
        msg.reactions[0].me shouldBe false
        s.cleanup()
    }

    @Test
    fun reactionAdded_by_self_sets_me_true() = runTest {
        val s = setup(this)
        s.storage.persist(message(id = messageA.value, channelId = channelA, ts = 1L))

        s.gateway.emit(GatewayDomainEvent.ReactionAdded(channelA, messageA, selfId, thumbsUp))
        testScheduler.runCurrent()

        val msg = s.storage.findById(messageA)!!
        msg.reactions[0].me shouldBe true
        s.cleanup()
    }

    @Test
    fun reactionRemoved_decrements_or_drops_entry() = runTest {
        val s = setup(this)
        s.storage.persist(message(id = messageA.value, channelId = channelA, ts = 1L))

        s.gateway.emit(GatewayDomainEvent.ReactionAdded(channelA, messageA, otherId, thumbsUp))
        testScheduler.runCurrent()
        s.gateway.emit(GatewayDomainEvent.ReactionRemoved(channelA, messageA, otherId, thumbsUp))
        testScheduler.runCurrent()

        s.storage.findById(messageA)!!.reactions.shouldBeEmpty()
        s.cleanup()
    }

    @Test
    fun reactionsClearedAll_empties_reactions() = runTest {
        val s = setup(this)
        s.storage.persist(message(id = messageA.value, channelId = channelA, ts = 1L))
        s.gateway.emit(GatewayDomainEvent.ReactionAdded(channelA, messageA, otherId, thumbsUp))
        s.gateway.emit(GatewayDomainEvent.ReactionAdded(channelA, messageA, otherId, heart))
        testScheduler.runCurrent()

        s.gateway.emit(GatewayDomainEvent.ReactionsClearedAll(channelA, messageA))
        testScheduler.runCurrent()

        s.storage.findById(messageA)!!.reactions.shouldBeEmpty()
        s.cleanup()
    }

    @Test
    fun reactionsClearedEmoji_drops_only_matching() = runTest {
        val s = setup(this)
        s.storage.persist(message(id = messageA.value, channelId = channelA, ts = 1L))
        s.gateway.emit(GatewayDomainEvent.ReactionAdded(channelA, messageA, otherId, thumbsUp))
        s.gateway.emit(GatewayDomainEvent.ReactionAdded(channelA, messageA, otherId, heart))
        testScheduler.runCurrent()

        s.gateway.emit(GatewayDomainEvent.ReactionsClearedEmoji(channelA, messageA, thumbsUp))
        testScheduler.runCurrent()

        val reactions = s.storage.findById(messageA)!!.reactions
        reactions shouldHaveSize 1
        reactions[0].emoji shouldBe heart
        s.cleanup()
    }

    @Test
    fun toggleReaction_add_persists_optimistically_and_calls_gateway() = runTest {
        val s = setup(this)
        s.storage.persist(message(id = messageA.value, channelId = channelA, ts = 1L))

        s.orchestrator.toggleReaction(channelA, messageA, thumbsUp, alreadyReacted = false)

        s.rest.addReactionCalls shouldHaveSize 1
        val msg = s.storage.findById(messageA)!!
        msg.reactions shouldHaveSize 1
        msg.reactions[0].me shouldBe true
        s.cleanup()
    }

    @Test
    fun toggleReaction_failure_rolls_back() = runTest {
        val s = setup(this)
        s.storage.persist(message(id = messageA.value, channelId = channelA, ts = 1L))
        s.rest.addReactionResponse = { _, _, _ -> Result.failure(RuntimeException("boom")) }

        s.orchestrator.toggleReaction(channelA, messageA, thumbsUp, alreadyReacted = false)

        s.storage.findById(messageA)!!.reactions.shouldBeEmpty()
        s.cleanup()
    }
}
