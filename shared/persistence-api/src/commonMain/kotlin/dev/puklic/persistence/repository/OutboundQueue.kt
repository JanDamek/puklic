package dev.puklic.persistence.repository

import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/** State of an outbound message in the local send-queue. */
enum class OutboundState { PENDING, SENDING, FAILED }

/** A queued, unsent message awaiting delivery to Discord. */
data class OutboundMessageRecord(
    val localId: Long,
    val channelId: ChannelId,
    val content: String,
    val referenceMessageId: MessageId?,
    val nonce: String,
    val createdAt: Instant,
    val attemptCount: Int,
    val lastError: String?,
    val state: OutboundState,
)

interface OutboundQueue {
    fun observePending(): Flow<List<OutboundMessageRecord>>
    suspend fun enqueue(
        channelId: ChannelId,
        content: String,
        referenceMessageId: MessageId?,
        nonce: String,
        createdAt: Instant,
    ): Long
    suspend fun markSending(localId: Long)
    suspend fun markFailed(localId: Long, error: String)
    suspend fun delete(localId: Long)
    suspend fun findById(localId: Long): OutboundMessageRecord?
}
