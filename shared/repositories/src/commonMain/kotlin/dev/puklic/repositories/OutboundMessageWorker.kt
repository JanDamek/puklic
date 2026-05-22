package dev.puklic.repositories

import dev.puklic.persistence.repository.MessageRepository
import dev.puklic.persistence.repository.OutboundMessageRecord
import dev.puklic.persistence.repository.OutboundQueue
import dev.puklic.persistence.repository.OutboundState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Background worker that consumes pending [OutboundMessageRecord]s and posts them via
 * [MessageGateway]. On success: REST returns the canonical [dev.puklic.domain.ChatMessage] which is
 * persisted via storage and the outbound record is deleted. On failure: record is marked FAILED
 * with retry backoff.
 *
 * Started by [start]; cancelled when [sessionScope] cancels (which happens on session end per
 * `threading-model.md`).
 */
public class OutboundMessageWorker(
    private val sessionScope: CoroutineScope,
    private val outboundQueue: OutboundQueue,
    private val messageGateway: MessageGateway,
    private val storage: MessageRepository,
    private val backoff: RetryBackoff = RetryBackoff.Exponential(),
) {
    private var job: Job? = null

    public fun start() {
        if (job?.isActive == true) return
        job = sessionScope.launch {
            outboundQueue.observePending()
                .distinctUntilChanged()
                .collect { pending ->
                    pending.filter { it.state == OutboundState.PENDING }.forEach { record ->
                        process(record)
                    }
                }
        }
    }

    /** Test-visible single-record execution. */
    internal suspend fun process(record: OutboundMessageRecord) {
        outboundQueue.markSending(record.localId)
        val result = messageGateway.sendMessage(
            channelId = record.channelId,
            content = record.content,
            nonce = record.nonce,
            replyTo = record.referenceMessageId,
        )
        result.fold(
            onSuccess = { confirmed ->
                storage.persist(confirmed)
                outboundQueue.delete(record.localId)
            },
            onFailure = { cause ->
                outboundQueue.markFailed(record.localId, cause.message ?: cause::class.simpleName.orEmpty())
                if (record.attemptCount < MAX_ATTEMPTS) {
                    delay(backoff.delayMillisFor(record.attemptCount + 1))
                }
            },
        )
    }

    public companion object {
        public const val MAX_ATTEMPTS: Int = 5
    }
}

/** Retry-backoff policy for the outbound worker. Pure & deterministic for testability. */
public sealed interface RetryBackoff {
    public fun delayMillisFor(attempt: Int): Long

    public data class Exponential(
        val initialMillis: Long = INITIAL_MILLIS,
        val multiplier: Double = MULTIPLIER,
        val capMillis: Long = CAP_MILLIS,
    ) : RetryBackoff {
        override fun delayMillisFor(attempt: Int): Long {
            if (attempt <= 0) return 0L
            var d = initialMillis.toDouble()
            repeat(attempt - 1) { d *= multiplier }
            return d.toLong().coerceAtMost(capMillis)
        }

        public companion object {
            public const val INITIAL_MILLIS: Long = 500L
            public const val MULTIPLIER: Double = 2.0
            public const val CAP_MILLIS: Long = 30_000L
        }
    }

    public data class Fixed(val millis: Long) : RetryBackoff {
        override fun delayMillisFor(attempt: Int): Long = millis
    }
}
