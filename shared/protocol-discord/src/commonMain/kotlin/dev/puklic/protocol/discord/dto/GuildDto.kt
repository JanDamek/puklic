package dev.puklic.protocol.discord.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DiscordGuildDto(
    val id: String,
    // Nullable: READY for user accounts may contain unavailable-guild stubs ({id, unavailable: true})
    // without a name. Full guild payloads always carry a non-null name.
    val name: String? = null,
    val icon: String? = null,
    @SerialName("owner_id") val ownerId: String? = null,
    val owner: Boolean = false,
    val features: List<String> = emptyList(),
    @SerialName("member_count") val memberCount: Int? = null,
    val permissions: String? = null,
    val unavailable: Boolean = false,
)
