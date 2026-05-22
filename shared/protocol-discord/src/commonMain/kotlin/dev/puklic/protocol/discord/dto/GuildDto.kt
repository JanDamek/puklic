package dev.puklic.protocol.discord.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DiscordGuildDto(
    val id: String,
    val name: String,
    val icon: String? = null,
    @SerialName("owner_id") val ownerId: String? = null,
    val owner: Boolean = false,
    val features: List<String> = emptyList(),
    @SerialName("member_count") val memberCount: Int? = null,
    val permissions: String? = null,
    val unavailable: Boolean = false,
)
