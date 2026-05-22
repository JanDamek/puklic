package dev.puklic.protocol.discord.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DiscordUserDto(
    val id: String,
    val username: String,
    @SerialName("global_name") val globalName: String? = null,
    val discriminator: String? = null,
    val avatar: String? = null,
    val bot: Boolean = false,
    val system: Boolean = false,
    @SerialName("public_flags") val publicFlags: Int = 0,
)
