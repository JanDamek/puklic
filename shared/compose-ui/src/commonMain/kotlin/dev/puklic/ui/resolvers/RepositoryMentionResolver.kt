package dev.puklic.ui.resolvers

import dev.puklic.domain.Channel
import dev.puklic.domain.UserSummary
import dev.puklic.ids.ChannelId
import dev.puklic.ids.RoleId
import dev.puklic.ids.UserId
import dev.puklic.persistence.repository.ChannelRepository
import dev.puklic.persistence.repository.UserRepository
import dev.puklic.repositories.RoleStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * [MentionResolver] backed by the persistence repositories and the in-memory [RoleStore].
 *
 * User and channel lookups hit the repository on first emission; results are cached by the
 * underlying repository layer so subsequent recompositions are cheap and do not block the
 * render path. Roles resolve reactively from the in-memory [RoleStore] populated by the
 * gateway orchestrators — no IO and no DB hit. Role colour is intentionally left `null`
 * (the Discord `roles[].color` field is not yet plumbed through the DTO layer; the renderer
 * already supports a `null` colour by falling back to the theme mention chip colour).
 */
public class RepositoryMentionResolver(
    private val userRepository: UserRepository,
    private val channelRepository: ChannelRepository,
    private val roleStore: RoleStore,
) : MentionResolver {

    override fun resolveUser(id: UserId): Flow<UserSummary?> = flow {
        emit(userRepository.findById(id))
    }

    override fun resolveRole(id: RoleId): Flow<RoleDisplay?> =
        roleStore.state.map { snapshot ->
            for ((_, guildMap) in snapshot) {
                val hit = guildMap[id]
                if (hit != null) return@map RoleDisplay(name = hit.name, colorArgb = null)
            }
            null
        }

    override fun resolveChannel(id: ChannelId): Flow<Channel?> = flow {
        emit(channelRepository.findById(id))
    }
}
