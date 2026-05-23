package dev.puklic.repositories

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.ids.UserId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class DiscordUrlRouterTest {

    @Test
    fun parses_channel_link_without_message() {
        val r = DiscordUrlRouter.parse("https://discord.com/channels/111/222")
        r shouldBe DiscordRoute.Channel(GuildId(111L), ChannelId(222L), null)
    }

    @Test
    fun parses_channel_link_with_message() {
        val r = DiscordUrlRouter.parse("https://discord.com/channels/111/222/333")
        r shouldBe DiscordRoute.Channel(GuildId(111L), ChannelId(222L), MessageId(333L))
    }

    @Test
    fun parses_dm_link() {
        val r = DiscordUrlRouter.parse("https://discord.com/channels/@me/444")
        r shouldBe DiscordRoute.Dm(ChannelId(444L), null)
    }

    @Test
    fun parses_dm_link_with_message() {
        val r = DiscordUrlRouter.parse("https://discord.com/channels/@me/444/555")
        r shouldBe DiscordRoute.Dm(ChannelId(444L), MessageId(555L))
    }

    @Test
    fun parses_user_link() {
        val r = DiscordUrlRouter.parse("https://discord.com/users/999")
        r shouldBe DiscordRoute.User(UserId(999L))
    }

    @Test
    fun accepts_canary_subdomain() {
        val r = DiscordUrlRouter.parse("https://canary.discord.com/channels/1/2/3")
        r shouldBe DiscordRoute.Channel(GuildId(1L), ChannelId(2L), MessageId(3L))
    }

    @Test
    fun accepts_ptb_subdomain() {
        val r = DiscordUrlRouter.parse("https://ptb.discord.com/channels/1/2")
        r shouldBe DiscordRoute.Channel(GuildId(1L), ChannelId(2L), null)
    }

    @Test
    fun accepts_www_subdomain() {
        val r = DiscordUrlRouter.parse("https://www.discord.com/channels/1/2")
        r shouldBe DiscordRoute.Channel(GuildId(1L), ChannelId(2L), null)
    }

    @Test
    fun accepts_http_scheme() {
        val r = DiscordUrlRouter.parse("http://discord.com/channels/1/2")
        r shouldBe DiscordRoute.Channel(GuildId(1L), ChannelId(2L), null)
    }

    @Test
    fun tolerates_trailing_slash() {
        val r = DiscordUrlRouter.parse("https://discord.com/channels/1/2/")
        r shouldBe DiscordRoute.Channel(GuildId(1L), ChannelId(2L), null)
    }

    @Test
    fun tolerates_surrounding_whitespace() {
        val r = DiscordUrlRouter.parse("  https://discord.com/channels/1/2  ")
        r shouldBe DiscordRoute.Channel(GuildId(1L), ChannelId(2L), null)
    }

    @Test
    fun non_discord_url_returns_external() {
        val r = DiscordUrlRouter.parse("https://example.com/foo/bar")
        r.shouldBeInstanceOf<DiscordRoute.External>()
        (r as DiscordRoute.External).url shouldBe "https://example.com/foo/bar"
    }

    @Test
    fun discord_url_with_extra_path_returns_external() {
        val r = DiscordUrlRouter.parse("https://discord.com/channels/1/2/3/4")
        r.shouldBeInstanceOf<DiscordRoute.External>()
    }

    @Test
    fun discord_url_with_non_numeric_id_returns_external() {
        val r = DiscordUrlRouter.parse("https://discord.com/channels/abc/def")
        r.shouldBeInstanceOf<DiscordRoute.External>()
    }
}
