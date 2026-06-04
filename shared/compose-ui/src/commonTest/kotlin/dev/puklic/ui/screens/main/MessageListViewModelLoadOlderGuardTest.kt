package dev.puklic.ui.screens.main

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.puklic.domain.ChatMessage
import dev.puklic.domain.EmojiRef
import dev.puklic.domain.MessageFlags
import dev.puklic.domain.MessageMentions
import dev.puklic.domain.RichTextDocument
import dev.puklic.domain.UserSummary
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import dev.puklic.ids.UserId
import dev.puklic.persistence.repository.MessageRepository
import dev.puklic.persistence.repository.OutboundMessageRecord
import dev.puklic.persistence.repository.OutboundQueue
import dev.puklic.persistence.repository.UserRepository
import dev.puklic.repositories.GatewayDomainEvent
import dev.puklic.repositories.GatewayEventSource
import dev.puklic.repositories.MessageGateway
import dev.puklic.repositories.MessageOrchestrator
import dev.puklic.repositories.PendingAttachment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Guard behaviour for [MessageListViewModel.loadOlder] (issue #94).
 *
 * The re-entry guard makes [MessageListViewModel.loadOlder] a no-op when:
 *  - a load is already in flight (`isLoadingOlder == true`), or
 *  - there are no more older messages (`hasMoreOlder == false`).
 *
 * These tests assert the orchestrator's REST `loadOlder` is reached exactly the expected number of
 * times. They fail red against the current (unguarded) implementation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageListViewModelLoadOlderGuardTest {

    private val channel = ChannelId(7L)

    @Test
    fun loadOlder_is_noop_when_already_loading() = runTest(UnconfinedTestDispatcher()) {
        val gateway = RecordingGateway()
        // First loadOlder suspends forever at the gateway, so the VM stays in isLoadingOlder = true.
        val gate = CompletableDeferred<Result<List<ChatMessage>>>()
        gateway.loadOlderHandler = { gate.await() }

        val (vm, _) = loadedVm(backgroundScope, gateway)

        vm.loadOlder() // enters in-flight state, suspends inside the gateway
        assertEquals(1, gateway.loadOlderCalls, "first loadOlder reaches the orchestrator")

        vm.loadOlder() // must be a no-op while a load is in flight
        assertEquals(1, gateway.loadOlderCalls, "loadOlder must not re-enter while already loading")

        gate.complete(Result.success(emptyList())) // let the first call finish so the scope drains
    }

    @Test
    fun loadOlder_is_noop_when_no_more_older() = runTest(UnconfinedTestDispatcher()) {
        val gateway = RecordingGateway()
        // An empty page makes the orchestrator report 0 merged → hasMoreOlder flips to false.
        gateway.loadOlderHandler = { Result.success(emptyList()) }

        val (vm, _) = loadedVm(backgroundScope, gateway)

        vm.loadOlder() // completes, flips hasMoreOlder to false
        assertEquals(1, gateway.loadOlderCalls)

        vm.loadOlder() // must be a no-op now that there is nothing older
        assertEquals(1, gateway.loadOlderCalls, "loadOlder must not fire when hasMoreOlder == false")
    }

    @Test
    fun loadOlder_invokes_orchestrator_when_idle_and_has_more() = runTest(UnconfinedTestDispatcher()) {
        val gateway = RecordingGateway()
        gateway.loadOlderHandler = { Result.success(emptyList()) }

        val (vm, oldestId) = loadedVm(backgroundScope, gateway)

        vm.loadOlder()

        assertEquals(1, gateway.loadOlderCalls, "idle + hasMoreOlder must reach the orchestrator once")
        assertEquals(channel, gateway.lastLoadOlderChannel, "loadOlder targets the VM's channel")
        assertEquals(oldestId, gateway.lastLoadOlderBefore, "loadOlder uses the oldest message id as the before-cursor")
    }

    /**
     * Build a [MessageListViewModel] already promoted to [MessageListState.Loaded] with a single
     * message. Returns the VM and the oldest message id (the before-cursor expected on loadOlder).
     */
    private fun loadedVm(scope: CoroutineScope, gateway: RecordingGateway): Pair<MessageListViewModel, MessageId> {
        val storage = FakeMessageStorage()
        val oldest = sampleMessage(id = 100L, ts = 1_000L)
        // Seed storage so observeCommitted emits a non-empty list → VM becomes Loaded.
        storage.seed(oldest)

        val orchestrator = MessageOrchestrator(
            sessionScope = scope,
            gatewaySource = NoopGatewayEventSource(),
            messageGateway = gateway,
            storage = storage,
            userStorage = FakeUserRepository(),
            outboundQueue = FakeOutboundQueue(),
        )

        val vm = MessageListViewModel(
            componentContext = newComponentContext(),
            orchestrator = orchestrator,
            channelId = channel,
            externalScope = scope,
        )

        val state = vm.state.value
        assertIs<MessageListState>(state)
        assertTrue(state is MessageListState.Loaded, "VM must be Loaded before exercising loadOlder; was $state")
        assertEquals(false, state.isLoadingOlder)
        assertEquals(true, state.hasMoreOlder)

        return vm to oldest.id
    }

    private fun newComponentContext(): DefaultComponentContext {
        val lifecycle = LifecycleRegistry()
        val ctx = DefaultComponentContext(lifecycle = lifecycle)
        lifecycle.resume()
        return ctx
    }

    private fun sampleMessage(id: Long, ts: Long): ChatMessage = ChatMessage(
        id = MessageId(id),
        channelId = channel,
        author = UserSummary(UserId(99L), "alice", null, null, null, bot = false, system = false),
        rawContent = "hello",
        parsedContent = RichTextDocument(emptyList()),
        attachments = emptyList(),
        embeds = emptyList(),
        reactions = emptyList(),
        mentions = MessageMentions(emptyList(), emptyList(), emptyList(), everyone = false),
        flags = MessageFlags.NONE,
        timestamp = Instant.fromEpochMilliseconds(ts),
        editedTimestamp = null,
        referencedMessage = null,
    )
}

/** [MessageGateway] that records loadOlder invocations and delegates the result to a handler. */
private class RecordingGateway : MessageGateway {
    var loadOlderCalls: Int = 0
    var lastLoadOlderChannel: ChannelId? = null
    var lastLoadOlderBefore: MessageId? = null
    var loadOlderHandler: suspend () -> Result<List<ChatMessage>> = { Result.success(emptyList()) }

    override suspend fun loadOlder(
        channelId: ChannelId,
        beforeId: MessageId,
        limit: Int,
    ): Result<List<ChatMessage>> {
        loadOlderCalls++
        lastLoadOlderChannel = channelId
        lastLoadOlderBefore = beforeId
        return loadOlderHandler()
    }

    override suspend fun sendMessage(
        channelId: ChannelId,
        content: String,
        nonce: String,
        replyTo: MessageId?,
        attachments: List<PendingAttachment>,
    ): Result<ChatMessage> = error("unused")

    override suspend fun editMessage(
        channelId: ChannelId,
        messageId: MessageId,
        newContent: String,
    ): Result<ChatMessage> = error("unused")

    override suspend fun deleteMessage(channelId: ChannelId, messageId: MessageId): Result<Unit> =
        error("unused")

    override suspend fun loadInitial(channelId: ChannelId, limit: Int): Result<List<ChatMessage>> =
        Result.success(emptyList())

    override suspend fun addReaction(
        channelId: ChannelId,
        messageId: MessageId,
        emoji: EmojiRef,
    ): Result<Unit> = error("unused")

    override suspend fun removeReaction(
        channelId: ChannelId,
        messageId: MessageId,
        emoji: EmojiRef,
    ): Result<Unit> = error("unused")
}

/** Minimal in-memory [MessageRepository] used to drive the VM into [MessageListState.Loaded]. */
private class FakeMessageStorage : MessageRepository {
    private val state = MutableStateFlow<Map<MessageId, ChatMessage>>(emptyMap())

    fun seed(message: ChatMessage) { state.update { it + (message.id to message) } }

    override fun observe(channelId: ChannelId, limit: Int): Flow<List<ChatMessage>> =
        state.map { snap ->
            snap.values.asSequence()
                .filter { it.channelId == channelId }
                .sortedBy { it.timestamp }
                .toList()
                .takeLast(limit)
        }

    override suspend fun loadOlder(channelId: ChannelId, before: Instant, limit: Int): List<ChatMessage> =
        emptyList()

    override suspend fun persist(message: ChatMessage) { state.update { it + (message.id to message) } }
    override suspend fun persistAll(messages: List<ChatMessage>) {
        state.update { current -> current + messages.associateBy { it.id } }
    }
    override suspend fun findById(id: MessageId): ChatMessage? = state.value[id]
    override suspend fun delete(id: MessageId) { state.update { it - id } }
    override suspend fun clear(channelId: ChannelId) {
        state.update { snap -> snap.filterValues { it.channelId != channelId } }
    }
}

/** Event source that never emits — the VM under test only exercises the loadOlder path. */
private class NoopGatewayEventSource : GatewayEventSource {
    private val _events = MutableSharedFlow<GatewayDomainEvent>(replay = 0, extraBufferCapacity = 8)
    override val events: SharedFlow<GatewayDomainEvent> = _events.asSharedFlow()
}

private class FakeUserRepository : UserRepository {
    private val state = MutableStateFlow<Map<UserId, UserSummary>>(emptyMap())
    override suspend fun findById(id: UserId): UserSummary? = state.value[id]
    override suspend fun findByIds(ids: Collection<UserId>): List<UserSummary> = ids.mapNotNull { state.value[it] }
    override suspend fun searchByName(query: String, limit: Int): List<UserSummary> = emptyList()
    override suspend fun persist(user: UserSummary) { state.update { it + (user.id to user) } }
    override suspend fun persistAll(users: List<UserSummary>) {
        state.update { current -> current + users.associateBy { it.id } }
    }
    override suspend fun delete(id: UserId) { state.update { it - id } }
}

private class FakeOutboundQueue : OutboundQueue {
    private val pending = MutableStateFlow<Map<Long, OutboundMessageRecord>>(emptyMap())
    override fun observePending(): Flow<List<OutboundMessageRecord>> =
        pending.map { it.values.sortedBy { r -> r.localId } }
    override suspend fun enqueue(
        channelId: ChannelId,
        content: String,
        referenceMessageId: MessageId?,
        nonce: String,
        createdAt: Instant,
    ): Long = 0L
    override suspend fun markSending(localId: Long) = Unit
    override suspend fun markFailed(localId: Long, error: String) = Unit
    override suspend fun delete(localId: Long) = Unit
    override suspend fun findById(localId: Long): OutboundMessageRecord? = null
}
