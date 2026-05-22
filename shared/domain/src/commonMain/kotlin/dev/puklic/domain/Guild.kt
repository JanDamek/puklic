package dev.puklic.domain

import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId

data class Guild(
    val id: GuildId,
    val name: String,
    val iconHash: String?,
    val ownerId: UserId,
    val features: Set<GuildFeature>,
    val memberCount: Int?,
)

enum class GuildFeature {
    COMMUNITY,
    VERIFIED,
    PARTNERED,
    DISCOVERABLE,
}
