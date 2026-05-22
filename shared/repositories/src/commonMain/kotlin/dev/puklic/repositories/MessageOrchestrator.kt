package dev.puklic.repositories

import dev.puklic.domain.ChatMessage
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import dev.puklic.persistence.repository.MessageRepository
import dev.puklic.persistence.repository.OutboundQueue
import dev.puklic.persistence.repository.OutboundMessageRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * High-level orchestrator that composes gateway events + storage + RAM hot/warm cache and exposes
 * reactive streams to the UI. The orchestrator never writes the DB directly: it delegates
 * persistence to the injected storage [MessageRepository] and outbound queueing to [OutboundQueue].
 *
 * RAM cache per ADR-0003:
 *  - hot: [HOT_CHANNEL_LIMIT] messages for the currently active channel (the channel last passed to
 *    [setActiveChannel]). [observeCommitted] returns that many for the active channel.
 *  - warm: any non-active channel observed returns [WARM_CHANNEL_LIMIT] messages.
 *  - cold: SQLite via [MessageRepository].
 *  - older: REST via [MessageGateway.loadOlder].
 *
 * Optimistic sends are tracked in [OutboundQueue] (state = PENDING|SENDING|FAILED). The UI joins
 * [observeCommitted] with [observeOutbound] to render the full stream including pending sends.
 */
@Suppress("LongParameterList")
public class MessageOrchestrator(
    private val sessionScope: CoroutineScope,
    private val gatewaySource: GatewayEventSource,
    private val messageGateway: MessageGateway,
    private val storage: MessageRepository,
    private val outboundQueue: OutboundQueue,
    private val nonceGenerator: () -> String = { defaultNonce() },
    private val nowProvider: () -> Instant = { Clock.System.now() },
) {
    private val activeChannelState = MutableStateFlow<ChannelId?>(null)

    init {
        gatewaySource.events
            .filterIsInstance<GatewayDomainEvent>()
            .onEach { event ->
                when (event) {
                    is GatewayDomainEvent.MessageCreated -> storage.persist(event.message)
                    is GatewayDomainEvent.MessageUpdated -> storage.persist(event.message)
                    is GatewayDomainEvent.MessageDeleted -> storage.delete(event.messageId)
                    else -> Unit
                }
            }
            .catch { t ->
                Logger.w("MessageOrchestrator") {
                    "message orchestrator: flow error caught, will not propagate " +
                        "cause=${t::class.simpleName} msg=${t.message?.take(200)}"
                }
            }
            .launchIn(sessionScope)
    }

    /** Promote [channelId] to the hot slot. */
    public fun setActiveChannel(channelId: ChannelId) {
        activeChannelState.value = channelId
    }

    /** Current active (hot) channel. */
    public val active: StateFlow<ChannelId?> = activeChannelState.asStateFlow()

    /**
     * Reactive view of committed messages for [channelId]. Emits with [HOT_CHANNEL_LIMIT] when
     * [channelId] is the active channel, [WARM_CHANNEL_LIMIT] otherwise. The Flow re-evaluates the
     * limit whenever the active channel changes.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    public fun observeCommitted(channelId: ChannelId): Flow<List<ChatMessage>> =
        activeChannelState.flatMapLatest { active ->
            val limit = if (active == channelId) HOT_CHANNEL_LIMIT else WARM_CHANNEL_LIMIT
            storage.observe(channelId, limit)
        }

    /** Pending / sending / failed outbound messages for [channelId]. */
    public fun observeOutbound(channelId: ChannelId): Flow<List<OutboundMessageRecord>> =
        outboundQueue.observePending().map { records -> records.filter { it.channelId == channelId } }

    /**
     * Enqueue a new send. Inserts into [OutboundQueue] (state = PENDING) and returns the local id.
     * The [OutboundMessageWorker] picks up the work asynchronously.
     */
    public suspend fun send(channelId: ChannelId, content: String, replyTo: MessageId? = null): Result<Long> =
        runCatching {
            outboundQueue.enqueue(
                channelId = channelId,
                content = content,
                referenceMessageId = replyTo,
                nonce = nonceGenerator(),
                createdAt = nowProvider(),
            )
        }

    /** Edit a server-confirmed message. Local mirror updates on success. */
    public suspend fun edit(messageId: MessageId, channelId: ChannelId, newContent: String): Result<Unit> =
        messageGateway.editMessage(channelId, messageId, newContent).map { updated ->
            storage.persist(updated)
        }

    /** Delete a server-confirmed message. Local mirror updates on success. */
    public suspend fun delete(messageId: MessageId, channelId: ChannelId): Result<Unit> =
        messageGateway.deleteMessage(channelId, messageId).map {
            storage.delete(messageId)
        }

    /**
     * Fetch the most recent page from REST and merge into storage. Intended to be called when a
     * channel is first opened so the observer has data. Returns the number of messages merged.
     */
    public suspend fun loadInitial(
        channelId: ChannelId,
        pageSize: Int = DEFAULT_PAGE,
    ): Result<Int> = messageGateway.loadInitial(channelId, pageSize).map { messages ->
        storage.persistAll(messages)
        messages.size
    }

    /** Fetch older messages from REST and merge into storage. Returns count merged. */
    public suspend fun loadOlder(
        channelId: ChannelId,
        beforeId: MessageId,
        pageSize: Int = DEFAULT_PAGE,
    ): Result<Int> = messageGateway.loadOlder(channelId, beforeId, pageSize).map { messages ->
        storage.persistAll(messages)
        messages.size
    }

    public companion object {
        public const val HOT_CHANNEL_LIMIT: Int = 200
        public const val WARM_CHANNEL_LIMIT: Int = 50
        public const val DEFAULT_PAGE: Int = 50

        private fun defaultNonce(): String =
            "puklic-${Clock.System.now().toEpochMilliseconds()}"
    }
}
