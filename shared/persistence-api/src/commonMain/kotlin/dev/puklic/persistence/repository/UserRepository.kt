package dev.puklic.persistence.repository

import dev.puklic.domain.UserSummary
import dev.puklic.ids.UserId

interface UserRepository {
    suspend fun findById(id: UserId): UserSummary?
    suspend fun findByIds(ids: Collection<UserId>): List<UserSummary>
    suspend fun persist(user: UserSummary)
    suspend fun persistAll(users: List<UserSummary>)
    suspend fun delete(id: UserId)
}
