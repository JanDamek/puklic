package dev.puklic.protocol.discord

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Client-identity payload Discord expects on every authenticated request from a desktop
 * client. The JSON shape is fixed by Discord — field names are snake_case.
 *
 * Per ADR-0002 (2026-05-22 amendment): these realistic client headers are required
 * because Discord's REST stack returns `50001 Missing Access` for legitimately accessible
 * channels when the User-Agent / `X-Super-Properties` look like a third-party client. We
 * do NOT perform TLS-level fingerprint impersonation.
 */
@Serializable
public data class DiscordClientProperties(
    val os: String,
    val browser: String = "Discord Client",
    val device: String = "",
    @SerialName("system_locale") val systemLocale: String,
    @SerialName("has_client_mods") val hasClientMods: Boolean = false,
    @SerialName("browser_user_agent") val browserUserAgent: String,
    @SerialName("browser_version") val browserVersion: String,
    @SerialName("os_version") val osVersion: String,
    val referrer: String = "",
    @SerialName("referring_domain") val referringDomain: String = "",
    @SerialName("referrer_current") val referrerCurrent: String = "",
    @SerialName("referring_domain_current") val referringDomainCurrent: String = "",
    @SerialName("release_channel") val releaseChannel: String = "stable",
    // TODO(Phase 2): fetch the current build number from Discord's HTML or a community feed
    // instead of hardcoding. Stale build numbers eventually get flagged.
    @SerialName("client_build_number") val clientBuildNumber: Int = DEFAULT_CLIENT_BUILD_NUMBER,
    @SerialName("client_event_source") val clientEventSource: String? = null,
)

internal const val DEFAULT_CLIENT_BUILD_NUMBER: Int = 380000
internal const val DEFAULT_BROWSER_VERSION: String = "33.4.0"

// TODO(Phase 2): platform-specific UA — Linux/Windows variants. macOS UA is the Phase 1 default.
internal const val DEFAULT_DESKTOP_USER_AGENT: String =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "discord/0.0.350 Chrome/124.0.6367.243 Electron/30.0.6 Safari/537.36"

private val LOCALE_REGEX = Regex("^[a-z]{2}(-[A-Z]{2})?$")

/** Host environment snapshot supplied by each platform. */
public data class HostEnvironment(
    val osName: String?,
    val osVersion: String?,
    val locale: String?,
)

/** Platform-supplied host environment. */
public expect fun currentHostEnvironment(): HostEnvironment

/** Platform-supplied IANA time zone id (e.g. "Europe/Prague"). */
public expect fun currentTimeZoneId(): String

/** Build a [DiscordClientProperties] payload from the platform host environment. */
public fun buildClientProperties(env: HostEnvironment = currentHostEnvironment()): DiscordClientProperties =
    DiscordClientProperties(
        os = mapOsName(env.osName),
        systemLocale = normalizeLocale(env.locale),
        browserUserAgent = DEFAULT_DESKTOP_USER_AGENT,
        browserVersion = DEFAULT_BROWSER_VERSION,
        osVersion = env.osVersion.orEmpty(),
    )

/** Default platform-agnostic locale fallback. */
internal fun normalizeLocale(raw: String?): String =
    raw?.takeIf { LOCALE_REGEX.matches(it) } ?: "en-US"

internal fun mapOsName(raw: String?): String = when {
    raw == null -> "Unknown"
    raw.contains("Mac", ignoreCase = true) -> "Mac OS X"
    raw.contains("Darwin", ignoreCase = true) -> "Mac OS X"
    raw.contains("Linux", ignoreCase = true) -> "Linux"
    raw.contains("Windows", ignoreCase = true) -> "Windows"
    else -> raw
}

// Dedicated Json that emits ALL fields (including defaults and explicit nulls) — Discord's
// X-Super-Properties + Gateway IDENTIFY parser expects the full identity payload, not a
// slimmed default-stripped one. Use this anywhere [DiscordClientProperties] crosses the wire.
internal val SuperPropertiesJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
}

internal fun serializeSuperProperties(properties: DiscordClientProperties): String =
    SuperPropertiesJson.encodeToString(DiscordClientProperties.serializer(), properties)

@OptIn(ExperimentalEncodingApi::class)
internal fun encodeSuperProperties(properties: DiscordClientProperties): String =
    Base64.encode(serializeSuperProperties(properties).encodeToByteArray())
