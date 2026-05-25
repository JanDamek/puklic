package dev.puklic.protocol.discord.dto

import kotlinx.serialization.Serializable

/**
 * Discord Role payload. `permissions` is a decimal bitmask string (Discord switched to string
 * to avoid JS 53-bit overflow). See architect-report 2026-05-24-channel-permission-design.md §3.
 */
@Serializable
internal data class DiscordRoleDto(
    val id: String,
    val name: String,
    val permissions: String,
    val position: Int = 0,
)
