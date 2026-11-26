package dev.puklic.protocol.discord

/**
 * Network endpoints of the Discord backend. Production values are the defaults; hosts that need
 * to talk to a local protocol-compatible server (performance baseline, protocol debugging)
 * construct an instance with their own URLs.
 */
public data class DiscordEndpoints(
    val apiBase: String = PRODUCTION_API_BASE,
    val gatewayUrl: String = PRODUCTION_GATEWAY_URL,
) {
    public companion object {
        public const val PRODUCTION_API_BASE: String = "https://discord.com/api/v10"
        public const val PRODUCTION_GATEWAY_URL: String = "wss://gateway.discord.gg/?v=10&encoding=json"
    }
}
