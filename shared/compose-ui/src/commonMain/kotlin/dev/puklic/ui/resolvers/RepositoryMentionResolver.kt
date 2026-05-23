package dev.puklic.ui.resolvers

import dev.puklic.domain.Channel
import dev.puklic.domain.UserSummary
import dev.puklic.ids.ChannelId
import dev.puklic.ids.RoleId
import dev.puklic.ids.UserId
import dev.puklic.persistence.repository.ChannelRepository
import dev.puklic.persistence.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * [MentionResolver] backed by the persistence repositories. User and channel lookups
 * hit the DB on the first emission; results are cached at the repository layer so
 * subsequent recompositions are cheap. Roles are not yet stored locally — we return
 * `null` until a future `RoleRepository` lands (Phase 2 roadmap).
 */
public class RepositoryMentionResolver(
    private val userRepository: UserRepository,
    private val channelRepository: ChannelRepository,
) : MentionResolver {

    override fun resolveUser(id: UserId): Flow<UserSummary?> = flow {
        emit(userRepository.findById(id))
    }

    override fun resolveRole(id: RoleId): Flow<RoleDisplay?> = flowOf(null)

    override fun resolveChannel(id: ChannelId): Flow<Channel?> = flow {
        emit(channelRepository.findById(id))
    }
}
