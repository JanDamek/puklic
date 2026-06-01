package dev.puklic.repositories

import dev.puklic.domain.ChatMessage
import dev.puklic.domain.EmojiRef
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import dev.puklic.ids.UserId
import dev.puklic.persistence.repository.MessageRepository
import dev.puklic.persistence.repository.OutboundQueue
import dev.puklic.persistence.repository.OutboundMessageRecord
import dev.puklic.persistence.repository.UserRepository
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
    private val userStorage: UserRepository,
    private val outboundQueue: OutboundQueue,
    private val nonceGenerator: () -> String = { defaultNonce() },
    private val nowProvider: () -> Instant = { Clock.System.now() },
    private val selfUserIdProvider: () -> UserId? = { null },
) {
    private val activeChannelState = MutableStateFlow<ChannelId?>(null)

    init {
        gatewaySource.events
            .filterIsInstance<GatewayDomainEvent>()
            .onEach { event ->
                when (event) {
                    is GatewayDomainEvent.MessageCreated -> persistWithAuthor(event.message)
                    is GatewayDomainEvent.MessageUpdated -> {
                        // MESSAGE_UPDATE payloads may omit reactions; preserve any existing
                        // reactions on the locally cached copy so they don't visually vanish.
                        val existing = storage.findById(event.message.id)
                        val merged = if (existing != null && event.message.reactions.isEmpty()) {
                            event.message.copy(reactions = existing.reactions)
                        } else {
                            event.message
                        }
                        persistWithAuthor(merged)
                    }
                    is GatewayDomainEvent.MessageUpdatedPartial -> {
                        // Issue #83: merge partial fields onto the cached message. No-op if the
                        // message is not in storage (e.g. older than the warm window).
                        val existing = storage.findById(event.messageId) ?: return@onEach
                        val mergedFlags = when {
                            event.pinned == true -> dev.puklic.domain.MessageFlags.PINNED
                            event.flags != null -> {
                                val raw = event.flags
                                when {
                                    raw and PARTIAL_FLAG_EPHEMERAL != 0 ->
                                        dev.puklic.domain.MessageFlags.EPHEMERAL
                                    raw and PARTIAL_FLAG_SUPPRESS_EMBEDS != 0 ->
                                        dev.puklic.domain.MessageFlags.SUPPRESS_EMBEDS
                                    else -> existing.flags
                                }
                            }
                            else -> existing.flags
                        }
                        val merged = existing.copy(
                            rawContent = event.content ?: existing.rawContent,
                            parsedContent = event.content?.let { dev.puklic.chatparser.parseRichText(it) }
                                ?: existing.parsedContent,
                            embeds = event.embeds ?: existing.embeds,
                            attachments = event.attachments ?: existing.attachments,
                            flags = mergedFlags,
                            editedTimestamp = event.editedTimestampEpochMs
                                ?.let(kotlinx.datetime.Instant::fromEpochMilliseconds)
                                ?: existing.editedTimestamp,
                        )
                        storage.persist(merged)
                    }
                    is GatewayDomainEvent.MessageDeleted -> storage.delete(event.messageId)
                    is GatewayDomainEvent.MessageDeletedBulk -> event.messageIds.forEach { id ->
                        storage.delete(id)
                    }
                    is GatewayDomainEvent.ReactionAdded -> mutate(event.messageId) { msg ->
                        msg.copy(reactions = ReactionMutator.add(
                            msg.reactions,
                            event.emoji,
                            isMe = event.userId == selfUserIdProvider(),
                        ))
                    }
                    is GatewayDomainEvent.ReactionRemoved -> mutate(event.messageId) { msg ->
                        msg.copy(reactions = ReactionMutator.remove(
                            msg.reactions,
                            event.emoji,
                            isMe = event.userId == selfUserIdProvider(),
                        ))
                    }
                    is GatewayDomainEvent.ReactionsClearedAll -> mutate(event.messageId) { msg ->
                        msg.copy(reactions = emptyList())
                    }
                    is GatewayDomainEvent.ReactionsClearedEmoji -> mutate(event.messageId) { msg ->
                        msg.copy(reactions = ReactionMutator.clearEmoji(msg.reactions, event.emoji))
                    }
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

    /**
     * Persist [message] after ensuring its author exists in the users table. The DB schema
     * declares `message.author_id` as FK -> users.id; without this upsert MESSAGE_CREATE events
     * for previously unseen authors fail with SQLITE_CONSTRAINT_FOREIGNKEY.
     */
    private suspend fun persistWithAuthor(message: dev.puklic.domain.ChatMessage) {
        userStorage.persist(message.author)
        storage.persist(message)
    }

    /**
     * Read-modify-write for a single message by id. No-op if the message is not in storage
     * (event arrived for a message we never cached — e.g. older than the warm window). The
     * persist call re-emits via [MessageRepository.observe], so the UI recomposes naturally.
     */
    private suspend inline fun mutate(id: MessageId, transform: (ChatMessage) -> ChatMessage) {
        val current = storage.findById(id) ?: return
        storage.persist(transform(current))
    }

    /**
     * Optimistically toggle the current user's reaction on [messageId]. Persists the mutated
     * local state immediately, then issues the REST call. On failure the local state is
     * rolled back so the UI returns to the pre-toggle reactions list.
     */
    public suspend fun toggleReaction(
        channelId: ChannelId,
        messageId: MessageId,
        emoji: EmojiRef,
        alreadyReacted: Boolean,
    ) {
        val current = storage.findById(messageId) ?: return
        val mutated = if (alreadyReacted) {
            current.copy(reactions = ReactionMutator.remove(current.reactions, emoji, isMe = true))
        } else {
            current.copy(reactions = ReactionMutator.add(current.reactions, emoji, isMe = true))
        }
        storage.persist(mutated)
        val result = if (alreadyReacted) {
            messageGateway.removeReaction(channelId, messageId, emoji)
        } else {
            messageGateway.addReaction(channelId, messageId, emoji)
        }
        result.onFailure {
            // Rollback to the pre-toggle reactions snapshot.
            storage.persist(current)
        }
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

    /**
     * Send a message that carries one or more in-memory [attachments] (issue #23). Per architect
     * decision Q1, attachment sends bypass the persistent outbound queue: pending attachments are
     * NEVER stored on disk, so a mid-upload crash means the user re-attaches. The gateway runs
     * the full upload protocol synchronously; on success the canonical message is persisted to
     * storage so the UI's normal observe path picks it up.
     */
    public suspend fun sendWithAttachments(
        channelId: ChannelId,
        content: String,
        attachments: List<PendingAttachment>,
        replyTo: MessageId? = null,
    ): Result<Unit> {
        val nonce = nonceGenerator()
        return messageGateway.sendMessage(
            channelId = channelId,
            content = content,
            nonce = nonce,
            replyTo = replyTo,
            attachments = attachments,
        ).map { confirmed ->
            storage.persist(confirmed)
        }
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

        // Discord message-flag bits referenced by partial MESSAGE_UPDATE merge (issue #83).
        // Source of truth for the canonical decode lives in MessageMapper.kt; mirrored here so
        // the orchestrator can interpret the raw int without importing protocol DTOs.
        internal const val PARTIAL_FLAG_SUPPRESS_EMBEDS: Int = 1 shl 2
        internal const val PARTIAL_FLAG_EPHEMERAL: Int = 1 shl 6

        private fun defaultNonce(): String =
            "puklic-${Clock.System.now().toEpochMilliseconds()}"
    }
}
