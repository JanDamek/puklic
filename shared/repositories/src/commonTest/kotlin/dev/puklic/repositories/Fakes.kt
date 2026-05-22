package dev.puklic.repositories

import dev.puklic.domain.ChatMessage
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
import dev.puklic.persistence.repository.OutboundState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Instant

internal fun message(
    id: Long,
    channelId: ChannelId,
    ts: Long,
    content: String = "hello",
    authorId: Long = 99L,
): ChatMessage = ChatMessage(
    id = MessageId(id),
    channelId = channelId,
    author = UserSummary(UserId(authorId), "alice", null, null, null, bot = false, system = false),
    rawContent = content,
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

internal class FakeMessageStorage : MessageRepository {
    private val state = MutableStateFlow<Map<MessageId, ChatMessage>>(emptyMap())
    val persistedAll: MutableList<List<ChatMessage>> = mutableListOf()

    override fun observe(channelId: ChannelId, limit: Int): Flow<List<ChatMessage>> =
        state.map { snap ->
            snap.values.asSequence()
                .filter { it.channelId == channelId }
                .sortedBy { it.timestamp }
                .toList()
                .takeLast(limit)
        }

    override suspend fun loadOlder(channelId: ChannelId, before: Instant, limit: Int): List<ChatMessage> =
        state.value.values.asSequence()
            .filter { it.channelId == channelId && it.timestamp < before }
            .sortedBy { it.timestamp }
            .toList()
            .takeLast(limit)

    override suspend fun persist(message: ChatMessage) { state.update { it + (message.id to message) } }
    override suspend fun persistAll(messages: List<ChatMessage>) {
        persistedAll += messages
        state.update { current -> current + messages.associateBy { it.id } }
    }
    override suspend fun findById(id: MessageId): ChatMessage? = state.value[id]
    override suspend fun delete(id: MessageId) { state.update { it - id } }
    override suspend fun clear(channelId: ChannelId) {
        state.update { it.filterValues { m -> m.channelId != channelId } }
    }
}

internal class FakeOutboundQueue : OutboundQueue {
    private val pending = MutableStateFlow<Map<Long, OutboundMessageRecord>>(emptyMap())
    private var nextId = 1L

    override fun observePending(): Flow<List<OutboundMessageRecord>> =
        pending.map { it.values.sortedBy { r -> r.localId } }

    override suspend fun enqueue(
        channelId: ChannelId,
        content: String,
        referenceMessageId: MessageId?,
        nonce: String,
        createdAt: Instant,
    ): Long {
        val id = nextId++
        val record = OutboundMessageRecord(
            localId = id,
            channelId = channelId,
            content = content,
            referenceMessageId = referenceMessageId,
            nonce = nonce,
            createdAt = createdAt,
            attemptCount = 0,
            lastError = null,
            state = OutboundState.PENDING,
        )
        pending.update { it + (id to record) }
        return id
    }

    override suspend fun markSending(localId: Long) {
        pending.update { current ->
            val r = current[localId] ?: return@update current
            current + (localId to r.copy(state = OutboundState.SENDING, attemptCount = r.attemptCount + 1))
        }
    }

    override suspend fun markFailed(localId: Long, error: String) {
        pending.update { current ->
            val r = current[localId] ?: return@update current
            current + (localId to r.copy(state = OutboundState.FAILED, lastError = error))
        }
    }

    override suspend fun delete(localId: Long) { pending.update { it - localId } }
    override suspend fun findById(localId: Long): OutboundMessageRecord? = pending.value[localId]
}

internal class FakeGatewayEventSource : GatewayEventSource {
    private val _events = MutableSharedFlow<GatewayDomainEvent>(replay = 0, extraBufferCapacity = 64)
    override val events: SharedFlow<GatewayDomainEvent> = _events.asSharedFlow()
    suspend fun emit(event: GatewayDomainEvent) { _events.emit(event) }
}

internal class FakeMessageGateway : MessageGateway {
    var sendResponse: (ChannelId, String, String, MessageId?) -> Result<ChatMessage> =
        { ch, content, _, _ -> Result.success(message(id = 9999L, channelId = ch, ts = 1L, content = content)) }
    var editResponse: (ChannelId, MessageId, String) -> Result<ChatMessage> =
        { ch, id, content -> Result.success(message(id = id.value, channelId = ch, ts = 1L, content = content)) }
    var deleteResponse: (ChannelId, MessageId) -> Result<Unit> = { _, _ -> Result.success(Unit) }
    var loadOlderResponse: (ChannelId, MessageId, Int) -> Result<List<ChatMessage>> =
        { _, _, _ -> Result.success(emptyList()) }

    val sentCalls = mutableListOf<Triple<ChannelId, String, String>>()
    val editCalls = mutableListOf<Triple<ChannelId, MessageId, String>>()
    val deleteCalls = mutableListOf<Pair<ChannelId, MessageId>>()

    override suspend fun sendMessage(
        channelId: ChannelId,
        content: String,
        nonce: String,
        replyTo: MessageId?,
    ): Result<ChatMessage> {
        sentCalls += Triple(channelId, content, nonce)
        return sendResponse(channelId, content, nonce, replyTo)
    }

    override suspend fun editMessage(
        channelId: ChannelId,
        messageId: MessageId,
        newContent: String,
    ): Result<ChatMessage> {
        editCalls += Triple(channelId, messageId, newContent)
        return editResponse(channelId, messageId, newContent)
    }

    override suspend fun deleteMessage(channelId: ChannelId, messageId: MessageId): Result<Unit> {
        deleteCalls += channelId to messageId
        return deleteResponse(channelId, messageId)
    }

    override suspend fun loadOlder(
        channelId: ChannelId,
        beforeId: MessageId,
        limit: Int,
    ): Result<List<ChatMessage>> = loadOlderResponse(channelId, beforeId, limit)
}
