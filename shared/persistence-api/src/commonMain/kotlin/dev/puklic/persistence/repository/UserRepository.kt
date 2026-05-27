package dev.puklic.persistence.repository

import dev.puklic.domain.UserSummary
import dev.puklic.ids.UserId

interface UserRepository {
    suspend fun findById(id: UserId): UserSummary?
    suspend fun findByIds(ids: Collection<UserId>): List<UserSummary>

    /**
     * Case-insensitive substring search across `username` and `global_name`. Used by the
     * New-DM picker (issue #17) — sources the user's already-cached people (READY users,
     * message authors, mentions, DM recipients, mutual-guild participants) for client-side
     * filtering. Discord ToS / "What Puklic IS NOT" forbids server-side user enumeration,
     * so the search is always bounded by the local cache.
     *
     * The implementation must be case-insensitive (LIKE with LOWER on both sides). Results
     * are ordered by most-recently-updated user first (proxy for "people you've seen lately")
     * and capped by [limit].
     */
    suspend fun searchByName(query: String, limit: Int): List<UserSummary>

    suspend fun persist(user: UserSummary)
    suspend fun persistAll(users: List<UserSummary>)
    suspend fun delete(id: UserId)
}
