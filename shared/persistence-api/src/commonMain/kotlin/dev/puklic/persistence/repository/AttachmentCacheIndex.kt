package dev.puklic.persistence.repository

import dev.puklic.ids.AttachmentId
import kotlinx.datetime.Instant

data class AttachmentCacheEntry(
    val attachmentId: AttachmentId,
    val url: String,
    val localPath: String,
    val sizeBytes: Long,
    val contentType: String?,
    val cachedAt: Instant,
    val lastAccessedAt: Instant,
)

interface AttachmentCacheIndex {
    suspend fun findById(id: AttachmentId): AttachmentCacheEntry?
    suspend fun persist(entry: AttachmentCacheEntry)
    suspend fun touch(id: AttachmentId, accessedAt: Instant)
    suspend fun totalSizeBytes(): Long
    suspend fun selectLruCandidates(limit: Int): List<AttachmentCacheEntry>
    suspend fun delete(id: AttachmentId)
}
