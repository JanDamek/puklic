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
 * gateway orchestrators — no IO and no DB hit. Role colour is mapped from the Discord
 * `roles[].color` 24-bit RGB integer into a full 32-bit ARGB int (opaque alpha) for Compose's
 * `Color(argb)`. A Discord `color` of 0 means "no colour set" and surfaces here as
 * `colorArgb = null`, instructing the renderer to fall back to the theme mention chip colour.
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
                if (hit != null) return@map RoleDisplay(
                    name = hit.name,
                    colorArgb = roleColorToArgb(hit.color),
                )
            }
            null
        }

    override fun resolveChannel(id: ChannelId): Flow<Channel?> = flow {
        emit(channelRepository.findById(id))
    }

    private companion object {
        /** Opaque alpha channel ORed onto Discord's 24-bit RGB role colour to produce ARGB. */
        private const val ROLE_COLOR_ALPHA: Int = 0xFF shl 24

        /**
         * Convert Discord's 24-bit RGB role colour to a full 32-bit ARGB int. Zero (no colour
         * set) returns `null` so renderers can fall back to a theme default.
         */
        fun roleColorToArgb(color: Int): Int? =
            if (color == 0) null else ROLE_COLOR_ALPHA or (color and 0x00FFFFFF)
    }
}
