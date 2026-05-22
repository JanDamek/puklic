package dev.puklic.persistence.sqldelight

import app.cash.sqldelight.coroutines.asFlow
import dev.puklic.db.PuklicDatabase
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import dev.puklic.persistence.repository.OutboundMessageRecord
import dev.puklic.persistence.repository.OutboundQueue
import dev.puklic.persistence.repository.OutboundState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import dev.puklic.db.Outbound_message as OutboundRow

class OutboundQueueImpl(
    private val db: PuklicDatabase,
    private val dispatcher: CoroutineDispatcher,
) : OutboundQueue {

    override fun observePending(): Flow<List<OutboundMessageRecord>> =
        db.outboundMessageQueries.selectPending().asFlow()
            .map { withContext(dispatcher) { it.executeAsList().map { row -> row.toDomain() } } }
            .flowOn(dispatcher)

    override suspend fun enqueue(
        channelId: ChannelId,
        content: String,
        referenceMessageId: MessageId?,
        nonce: String,
        createdAt: Instant,
    ): Long = withContext(dispatcher) {
        db.transactionWithResult {
            db.outboundMessageQueries.enqueue(
                channel_id = channelId.value,
                content = content,
                attachments_json = "[]",
                reference_message_id = referenceMessageId?.value,
                nonce = nonce,
                created_at = createdAt.toEpochMilliseconds(),
            )
            db.outboundMessageQueries.lastInsertRowId().executeAsOne()
        }
    }

    override suspend fun markSending(localId: Long) {
        withContext(dispatcher) { db.outboundMessageQueries.markSending(localId) }
    }

    override suspend fun markFailed(localId: Long, error: String) {
        withContext(dispatcher) { db.outboundMessageQueries.markFailed(error, localId) }
    }

    override suspend fun delete(localId: Long) {
        withContext(dispatcher) { db.outboundMessageQueries.delete(localId) }
    }

    override suspend fun findById(localId: Long): OutboundMessageRecord? = withContext(dispatcher) {
        db.outboundMessageQueries.selectById(localId).executeAsOneOrNull()?.toDomain()
    }

    private fun OutboundRow.toDomain() = OutboundMessageRecord(
        localId = local_id,
        channelId = ChannelId(channel_id),
        content = content,
        referenceMessageId = reference_message_id?.let(::MessageId),
        nonce = nonce,
        createdAt = Instant.fromEpochMilliseconds(created_at),
        attemptCount = attempt_count.toInt(),
        lastError = last_error,
        state = when (state) {
            0L -> OutboundState.PENDING
            1L -> OutboundState.SENDING
            2L -> OutboundState.FAILED
            else -> error("Unknown outbound_message.state=$state")
        },
    )
}
