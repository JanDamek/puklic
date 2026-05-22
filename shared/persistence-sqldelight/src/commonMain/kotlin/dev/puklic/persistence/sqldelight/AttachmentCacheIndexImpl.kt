package dev.puklic.persistence.sqldelight

import dev.puklic.db.PuklicDatabase
import dev.puklic.ids.AttachmentId
import dev.puklic.persistence.repository.AttachmentCacheEntry
import dev.puklic.persistence.repository.AttachmentCacheIndex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import dev.puklic.db.Attachment_cache_index as Row

class AttachmentCacheIndexImpl(
    private val db: PuklicDatabase,
    private val dispatcher: CoroutineDispatcher,
) : AttachmentCacheIndex {

    override suspend fun findById(id: AttachmentId): AttachmentCacheEntry? = withContext(dispatcher) {
        db.attachmentCacheQueries.selectById(id.value).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun persist(entry: AttachmentCacheEntry) {
        withContext(dispatcher) {
            db.attachmentCacheQueries.upsert(
                attachment_id = entry.attachmentId.value,
                url = entry.url,
                local_path = entry.localPath,
                size_bytes = entry.sizeBytes,
                content_type = entry.contentType,
                cached_at = entry.cachedAt.toEpochMilliseconds(),
                last_accessed_at = entry.lastAccessedAt.toEpochMilliseconds(),
            )
        }
    }

    override suspend fun touch(id: AttachmentId, accessedAt: Instant) {
        withContext(dispatcher) {
            db.attachmentCacheQueries.touch(accessedAt.toEpochMilliseconds(), id.value)
        }
    }

    override suspend fun totalSizeBytes(): Long = withContext(dispatcher) {
        db.attachmentCacheQueries.totalSize().executeAsOne()
    }

    override suspend fun selectLruCandidates(limit: Int): List<AttachmentCacheEntry> = withContext(dispatcher) {
        db.attachmentCacheQueries.selectLru(limit.toLong()).executeAsList().map { it.toDomain() }
    }

    override suspend fun delete(id: AttachmentId) {
        withContext(dispatcher) { db.attachmentCacheQueries.delete(id.value) }
    }

    private fun Row.toDomain() = AttachmentCacheEntry(
        attachmentId = AttachmentId(attachment_id),
        url = url,
        localPath = local_path,
        sizeBytes = size_bytes,
        contentType = content_type,
        cachedAt = Instant.fromEpochMilliseconds(cached_at),
        lastAccessedAt = Instant.fromEpochMilliseconds(last_accessed_at),
    )
}
