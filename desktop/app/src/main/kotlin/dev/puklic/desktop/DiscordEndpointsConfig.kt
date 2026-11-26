package dev.puklic.desktop

import dev.puklic.protocol.discord.DiscordEndpoints

private const val API_BASE_PROPERTY: String = "puklic.discord.apiBase"
private const val GATEWAY_URL_PROPERTY: String = "puklic.discord.gatewayUrl"

/**
 * Reads the Discord endpoints from the `puklic.discord.apiBase` and `puklic.discord.gatewayUrl`
 * system properties, falling back to production. Used by the performance baseline harness
 * (`tools/perf`) to point the app at a local protocol-compatible server.
 */
internal fun discordEndpointsFromSystemProperties(): DiscordEndpoints = DiscordEndpoints(
    apiBase = System.getProperty(API_BASE_PROPERTY) ?: DiscordEndpoints.PRODUCTION_API_BASE,
    gatewayUrl = System.getProperty(GATEWAY_URL_PROPERTY) ?: DiscordEndpoints.PRODUCTION_GATEWAY_URL,
)
