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
    /**
     * Discord packs role colour as a 24-bit RGB integer (0xRRGGBB). A value of 0 means
     * "no colour set" — the official client falls back to the default text colour. Default
     * here covers payloads from older code paths that pre-date this field; live gateway
     * payloads always include it.
     */
    val color: Int = 0,
)
