package dev.puklic.persistence.repository

import dev.puklic.domain.ChatMessage
import dev.puklic.domain.MessageFlags
import dev.puklic.domain.MessageMentions
import dev.puklic.domain.RichTextDocument
import dev.puklic.domain.UserSummary
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import dev.puklic.ids.UserId
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test

/**
 * Contract tests for the [MessageRepository] interface, exercised via an in-memory fake.
 * Concrete persistence implementations (SQLDelight, etc.) are validated separately in their
 * own modules; this test pins the public contract observable to downstream callers.
 */
class MessageRepositoryContractTest {

    private val channelId = ChannelId(1L)
    private val authorId = UserId(2L)

    private fun message(id: Long, ts: Long, content: String = "hello"): ChatMessage = ChatMessage(
        id = MessageId(id),
        channelId = channelId,
        author = UserSummary(authorId, "alice", null, null, null, bot = false, system = false),
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

    @Test
    fun observe_emits_persisted_messages_in_ascending_timestamp_order() = runTest {
        val repo: MessageRepository = InMemoryMessageRepository()

        repo.persist(message(id = 10, ts = 1000))
        repo.persist(message(id = 20, ts = 2000))
        repo.persist(message(id = 5, ts = 500))

        val emitted = repo.observe(channelId).first()

        emitted.map { it.id.value } shouldContainExactly listOf(5L, 10L, 20L)
    }

    @Test
    fun loadOlder_returns_messages_strictly_before_pivot() = runTest {
        val repo: MessageRepository = InMemoryMessageRepository()
        listOf(1000L, 2000L, 3000L, 4000L).forEachIndexed { i, ts -> repo.persist(message(id = i.toLong() + 1, ts = ts)) }

        val older = repo.loadOlder(channelId, before = Instant.fromEpochMilliseconds(3000L), limit = 10)

        older.map { it.id.value } shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun delete_removes_message_and_clear_empties_channel() = runTest {
        val repo: MessageRepository = InMemoryMessageRepository()
        repo.persist(message(id = 10, ts = 1000))
        repo.persist(message(id = 20, ts = 2000))

        repo.delete(MessageId(10))
        repo.observe(channelId).first().map { it.id.value } shouldContainExactly listOf(20L)

        repo.clear(channelId)
        repo.observe(channelId).first() shouldBe emptyList()
    }
}

/**
 * Minimal in-memory implementation used to validate the [MessageRepository] contract.
 * Concrete persistence modules supply their own implementations; this fake exists only as a
 * test double, both for this contract test and for downstream repositories that need a stub.
 */
private class InMemoryMessageRepository : MessageRepository {
    private val state = MutableStateFlow<Map<MessageId, ChatMessage>>(emptyMap())

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

    override suspend fun persist(message: ChatMessage) {
        state.update { it + (message.id to message) }
    }

    override suspend fun persistAll(messages: List<ChatMessage>) {
        state.update { current -> current + messages.associateBy { it.id } }
    }

    override suspend fun findById(id: MessageId): ChatMessage? = state.value[id]

    override suspend fun delete(id: MessageId) {
        state.update { it - id }
    }

    override suspend fun clear(channelId: ChannelId) {
        state.update { current -> current.filterValues { it.channelId != channelId } }
    }
}
