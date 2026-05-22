package dev.puklic.persistence.sqldelight

import app.cash.sqldelight.coroutines.asFlow
import dev.puklic.db.PuklicDatabase
import dev.puklic.domain.ChatMessage
import dev.puklic.domain.UserSummary
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import dev.puklic.ids.UserId
import dev.puklic.persistence.repository.MessageRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

class MessageRepositoryImpl(
    private val db: PuklicDatabase,
    private val dispatcher: CoroutineDispatcher,
    private val nowEpochMs: () -> Long = { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() },
) : MessageRepository {

    override fun observe(channelId: ChannelId, limit: Int): Flow<List<ChatMessage>> =
        db.messageQueries.messagesByChannel(channelId.value, limit.toLong())
            .asFlow()
            .map { query ->
                withContext(dispatcher) {
                    val rows = query.executeAsList()
                    // Query orders DESC for LIMIT semantics; UI consumes ascending.
                    rows.asReversed().map { row -> row.toDomain(loadAuthor(row.author_id)) }
                }
            }
            .flowOn(dispatcher)

    override suspend fun loadOlder(channelId: ChannelId, before: Instant, limit: Int): List<ChatMessage> =
        withContext(dispatcher) {
            val rows = db.messageQueries.messagesByChannelBefore(
                channel_id = channelId.value,
                timestamp = before.toEpochMilliseconds(),
                value_ = limit.toLong(),
            ).executeAsList()
            rows.asReversed().map { row -> row.toDomain(loadAuthor(row.author_id)) }
        }

    override suspend fun persist(message: ChatMessage) {
        persistAll(listOf(message))
    }

    override suspend fun persistAll(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        withContext(dispatcher) {
            val authors = messages.map { it.author }.distinctBy { it.id.value }
            val ts = nowEpochMs()
            db.transaction {
                // Upsert authors first to satisfy FK constraint on message.author_id.
                authors.forEach { upsertAuthor(it, ts) }
                messages.forEach { insert(it) }
            }
        }
    }

    private fun upsertAuthor(user: UserSummary, updatedAt: Long) {
        db.userQueries.upsert(
            id = user.id.value,
            username = user.username,
            global_name = user.globalName,
            discriminator = user.discriminator,
            avatar_hash = user.avatarHash,
            bot = if (user.bot) 1L else 0L,
            system = if (user.system) 1L else 0L,
            updated_at = updatedAt,
        )
    }

    override suspend fun findById(id: MessageId): ChatMessage? = withContext(dispatcher) {
        val row = db.messageQueries.selectById(id.value).executeAsOneOrNull() ?: return@withContext null
        row.toDomain(loadAuthor(row.author_id))
    }

    override suspend fun delete(id: MessageId) {
        withContext(dispatcher) {
            db.messageQueries.delete(id.value)
        }
    }

    override suspend fun clear(channelId: ChannelId) {
        withContext(dispatcher) {
            db.messageQueries.clearChannel(channelId.value)
        }
    }

    private fun insert(message: ChatMessage) {
        val cols = message.toColumns()
        db.messageQueries.upsert(
            id = cols.id,
            channel_id = cols.channelId,
            author_id = cols.authorId,
            raw_content = cols.rawContent,
            timestamp = cols.timestamp,
            edited_timestamp = cols.editedTimestamp,
            flags = cols.flags,
            reference_message_id = cols.referenceMessageId,
            reference_channel_id = cols.referenceChannelId,
            reference_type = cols.referenceType,
            mentions_everyone = cols.mentionsEveryone,
            attachments_json = cols.attachmentsJson,
            embeds_json = cols.embedsJson,
            reactions_json = cols.reactionsJson,
            mentions_users_json = cols.mentionsUsersJson,
            mentions_roles_json = cols.mentionsRolesJson,
            mentions_channels_json = cols.mentionsChannelsJson,
        )
    }

    /**
     * Resolve the author for a message row from the `user` table. If the user is not cached
     * (which should not happen in normal flow — the gateway populates users with every event),
     * return a minimal placeholder rather than crash. Higher layers may upgrade this on the next
     * USER_UPDATE event.
     */
    private fun loadAuthor(userId: Long): UserSummary {
        val row = db.userQueries.selectById(userId).executeAsOneOrNull()
        return row?.toDomain() ?: UserSummary(
            id = UserId(userId),
            username = "unknown",
            globalName = null,
            discriminator = null,
            avatarHash = null,
            bot = false,
            system = false,
        )
    }
}

