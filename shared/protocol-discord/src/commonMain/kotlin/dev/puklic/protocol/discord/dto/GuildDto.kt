package dev.puklic.protocol.discord.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DiscordGuildDto(
    val id: String,
    // Nullable: READY for user accounts may contain unavailable-guild stubs ({id, unavailable: true})
    // without a name. Bot-mode full guild payloads carry a non-null name at the top level.
    // User-mode "lazy" guild payloads (Discord client gateway) put name/owner_id/icon/features
    // inside a nested `properties` object instead — see [properties].
    val name: String? = null,
    val icon: String? = null,
    @SerialName("owner_id") val ownerId: String? = null,
    val owner: Boolean = false,
    val features: List<String> = emptyList(),
    @SerialName("member_count") val memberCount: Int? = null,
    val permissions: String? = null,
    val unavailable: Boolean = false,
    /**
     * User-mode READY guild shape: Discord's web/desktop client gateway nests guild metadata
     * here instead of duplicating it at the top level. When non-null, its fields override the
     * top-level fallbacks (which are absent in user-mode). See GuildMapper.toDomainOrNull.
     */
    val properties: DiscordGuildPropertiesDto? = null,
)

/**
 * Nested guild properties payload sent by the user-mode Discord gateway. All fields are optional
 * because Discord adds/removes them between client builds; only fields actually used by the
 * domain mapper are modeled. Unknown fields are tolerated by [DiscordJson] (`ignoreUnknownKeys`).
 */
@Serializable
internal data class DiscordGuildPropertiesDto(
    val name: String? = null,
    val icon: String? = null,
    @SerialName("owner_id") val ownerId: String? = null,
    val features: List<String> = emptyList(),
)
