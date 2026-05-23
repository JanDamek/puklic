package dev.puklic.repositories

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.ids.UserId

/**
 * Result of parsing a URL that may point to an in-app Discord destination.
 *
 * Issue #7: clicking a Discord link inside a message should switch the active
 * channel inside Puklic instead of opening the system browser.
 */
public sealed interface DiscordRoute {
    public data class Channel(
        val guildId: GuildId,
        val channelId: ChannelId,
        val messageId: MessageId?,
    ) : DiscordRoute

    public data class Dm(
        val channelId: ChannelId,
        val messageId: MessageId?,
    ) : DiscordRoute

    public data class User(val userId: UserId) : DiscordRoute

    public data class External(val url: String) : DiscordRoute
}

/**
 * Parses Discord URLs into structured [DiscordRoute] values. Anything that doesn't
 * match a recognised Discord URL pattern (channel link, DM link, user link) falls
 * through to [DiscordRoute.External] so the caller can defer to the platform
 * browser handler.
 *
 * Supported subdomains: `discord.com`, `www.discord.com`, `canary.discord.com`,
 * `ptb.discord.com`. Both `http` and `https` are accepted. A trailing slash is
 * tolerated.
 */
public object DiscordUrlRouter {
    private val channelRegex = Regex(
        """^https?://(?:www\.)?(?:canary\.|ptb\.)?discord\.com/channels/(\d+|@me)/(\d+)(?:/(\d+))?/?$""",
    )
    private val userRegex = Regex(
        """^https?://(?:www\.)?(?:canary\.|ptb\.)?discord\.com/users/(\d+)/?$""",
    )

    public fun parse(url: String): DiscordRoute {
        val trimmed = url.trim()
        channelRegex.matchEntire(trimmed)?.let { m ->
            val first = m.groupValues[1]
            val channelId = ChannelId(m.groupValues[2].toLong())
            val messageId = m.groupValues[3]
                .takeIf { it.isNotBlank() }
                ?.let { MessageId(it.toLong()) }
            return if (first == "@me") {
                DiscordRoute.Dm(channelId, messageId)
            } else {
                DiscordRoute.Channel(GuildId(first.toLong()), channelId, messageId)
            }
        }
        userRegex.matchEntire(trimmed)?.let { m ->
            return DiscordRoute.User(UserId(m.groupValues[1].toLong()))
        }
        return DiscordRoute.External(trimmed)
    }
}
