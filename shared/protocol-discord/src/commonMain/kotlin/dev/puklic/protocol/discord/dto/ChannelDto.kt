package dev.puklic.protocol.discord.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DiscordChannelDto(
    val id: String,
    val type: Int,
    @SerialName("guild_id") val guildId: String? = null,
    val name: String? = null,
    val topic: String? = null,
    val nsfw: Boolean = false,
    val position: Int = 0,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("rate_limit_per_user") val rateLimitPerUser: Int = 0,
    val bitrate: Int? = null,
    @SerialName("user_limit") val userLimit: Int? = null,
    // Bot-mode + Group-DM payloads typically populate `recipients` with full user objects.
    val recipients: List<DiscordUserDto> = emptyList(),
    // User-mode (web/desktop client) READY DM channels carry only `recipient_ids`; the actual
    // user records live in the top-level `users` array and must be joined by the caller.
    @SerialName("recipient_ids") val recipientIds: List<String> = emptyList(),
    @SerialName("last_message_id") val lastMessageId: String? = null,
    // Issue #18 — Discord ships channel-level permission overwrites here. Optional/null on
    // payload variants that omit it (e.g. DM channels, legacy READY shapes).
    @SerialName("permission_overwrites")
    val permissionOverwrites: List<PermissionOverwriteDto>? = null,
)

/**
 * Discord channel-level permission overwrite payload. `id` is a snowflake (role or user id),
 * `type` is `0` for role, `1` for member. `allow` / `deny` are decimal bitmask strings.
 * See architect-report 2026-05-24-channel-permission-design.md §3.
 */
@Serializable
internal data class PermissionOverwriteDto(
    val id: String,
    val type: Int,
    val allow: String,
    val deny: String,
)
