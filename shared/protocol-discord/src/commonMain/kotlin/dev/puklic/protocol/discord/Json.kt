package dev.puklic.protocol.discord

import kotlinx.serialization.json.Json

/**
 * Lenient JSON instance for Discord REST + Gateway payloads.
 *
 * Per ADR-0006: `ignoreUnknownKeys = true` is the architectural exception for the
 * external Discord API only. Discord adds fields without notice; strict deserialization
 * would crash users on every rollout. Test fixtures use the strict counterpart.
 *
 * MUST NOT be reused outside `:shared:protocol-discord`.
 */
internal val DiscordJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    isLenient = false
    classDiscriminator = "_t"
}
