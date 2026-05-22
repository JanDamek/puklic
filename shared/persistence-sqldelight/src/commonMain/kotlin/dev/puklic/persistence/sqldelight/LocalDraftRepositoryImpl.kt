package dev.puklic.persistence.sqldelight

import dev.puklic.db.PuklicDatabase
import dev.puklic.ids.ChannelId
import dev.puklic.persistence.repository.LocalDraft
import dev.puklic.persistence.repository.LocalDraftRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

class LocalDraftRepositoryImpl(
    private val db: PuklicDatabase,
    private val dispatcher: CoroutineDispatcher,
) : LocalDraftRepository {

    override suspend fun findByChannel(channelId: ChannelId): LocalDraft? = withContext(dispatcher) {
        db.localDraftQueries.selectByChannel(channelId.value).executeAsOneOrNull()?.let {
            LocalDraft(
                channelId = ChannelId(it.channel_id),
                content = it.content,
                updatedAt = Instant.fromEpochMilliseconds(it.updated_at),
            )
        }
    }

    override suspend fun persist(draft: LocalDraft) {
        withContext(dispatcher) {
            db.localDraftQueries.upsert(
                channel_id = draft.channelId.value,
                content = draft.content,
                updated_at = draft.updatedAt.toEpochMilliseconds(),
            )
        }
    }

    override suspend fun delete(channelId: ChannelId) {
        withContext(dispatcher) { db.localDraftQueries.delete(channelId.value) }
    }
}
