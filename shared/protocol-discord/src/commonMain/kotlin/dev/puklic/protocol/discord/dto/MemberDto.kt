package dev.puklic.protocol.discord.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Discord guild member payload. `user` may be absent when the event already nests user info
 * elsewhere (e.g. GUILD_MEMBER_UPDATE). See architect-report 2026-05-24 §3.
 */
@Serializable
internal data class DiscordMemberDto(
    val user: DiscordUserDto? = null,
    val roles: List<String> = emptyList(),
    val nick: String? = null,
    @SerialName("joined_at") val joinedAt: String? = null,
)
