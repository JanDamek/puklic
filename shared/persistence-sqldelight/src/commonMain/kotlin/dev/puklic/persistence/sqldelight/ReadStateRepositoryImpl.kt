package dev.puklic.persistence.sqldelight

import dev.puklic.db.PuklicDatabase
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import dev.puklic.persistence.repository.ReadState
import dev.puklic.persistence.repository.ReadStateRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import dev.puklic.db.Read_state as ReadStateRow

class ReadStateRepositoryImpl(
    private val db: PuklicDatabase,
    private val dispatcher: CoroutineDispatcher,
) : ReadStateRepository {

    override suspend fun findByChannel(channelId: ChannelId): ReadState? = withContext(dispatcher) {
        db.readStateQueries.selectByChannel(channelId.value).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun findAll(): List<ReadState> = withContext(dispatcher) {
        db.readStateQueries.selectAll().executeAsList().map { it.toDomain() }
    }

    override suspend fun persist(state: ReadState) {
        withContext(dispatcher) {
            db.readStateQueries.upsert(
                channel_id = state.channelId.value,
                last_message_id = state.lastMessageId?.value,
                mention_count = state.mentionCount.toLong(),
                last_viewed_at = state.lastViewedAt?.toEpochMilliseconds(),
            )
        }
    }

    override suspend fun delete(channelId: ChannelId) {
        withContext(dispatcher) { db.readStateQueries.delete(channelId.value) }
    }

    private fun ReadStateRow.toDomain() = ReadState(
        channelId = ChannelId(channel_id),
        lastMessageId = last_message_id?.let(::MessageId),
        mentionCount = (mention_count ?: 0L).toInt(),
        lastViewedAt = last_viewed_at?.let(Instant::fromEpochMilliseconds),
    )
}
