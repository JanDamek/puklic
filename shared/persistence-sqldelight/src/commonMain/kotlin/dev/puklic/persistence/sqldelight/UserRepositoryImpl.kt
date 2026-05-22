package dev.puklic.persistence.sqldelight

import dev.puklic.db.PuklicDatabase
import dev.puklic.domain.UserSummary
import dev.puklic.ids.UserId
import dev.puklic.persistence.repository.UserRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class UserRepositoryImpl(
    private val db: PuklicDatabase,
    private val dispatcher: CoroutineDispatcher,
    private val nowEpochMs: () -> Long,
) : UserRepository {

    override suspend fun findById(id: UserId): UserSummary? = withContext(dispatcher) {
        db.userQueries.selectById(id.value).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun findByIds(ids: Collection<UserId>): List<UserSummary> {
        if (ids.isEmpty()) return emptyList()
        return withContext(dispatcher) {
            db.userQueries.selectByIds(ids.map { it.value }).executeAsList().map { it.toDomain() }
        }
    }

    override suspend fun persist(user: UserSummary) {
        withContext(dispatcher) { insert(user) }
    }

    override suspend fun persistAll(users: List<UserSummary>) {
        if (users.isEmpty()) return
        withContext(dispatcher) {
            db.transaction { users.forEach { insert(it) } }
        }
    }

    override suspend fun delete(id: UserId) {
        withContext(dispatcher) { db.userQueries.delete(id.value) }
    }

    private fun insert(user: UserSummary) {
        db.userQueries.upsert(
            id = user.id.value,
            username = user.username,
            global_name = user.globalName,
            discriminator = user.discriminator,
            avatar_hash = user.avatarHash,
            bot = if (user.bot) 1L else 0L,
            system = if (user.system) 1L else 0L,
            updated_at = nowEpochMs(),
        )
    }
}
