package dev.puklic.protocol.discord.mapper

import dev.puklic.domain.GuildCategoryChannel
import dev.puklic.domain.GuildTextChannel
import dev.puklic.domain.GuildVoiceChannel
import dev.puklic.protocol.discord.DiscordJsonStrict
import dev.puklic.protocol.discord.dto.DiscordChannelDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Phase 1 channel filtering:
 *  - type 0 (GUILD_TEXT)   → GuildTextChannel (clickable)
 *  - type 5 (GUILD_ANNOUNCEMENT) → GuildTextChannel (clickable)
 *  - type 4 (GUILD_CATEGORY) → GuildCategoryChannel (header, not clickable)
 *  - type 2/13/15 (voice / stage / forum) → null (filtered out)
 */
class ChannelMapperTest {

    @Test
    fun type_0_maps_to_guild_text_channel() {
        val dto = decode("""{"id":"100","type":0,"guild_id":"1","name":"general","position":2}""")
        val channel = dto.toDomain()
        assertIs<GuildTextChannel>(channel)
        assertEquals("general", channel.name)
        assertEquals(2, channel.position)
    }

    @Test
    fun type_5_announcement_maps_to_guild_text_channel() {
        val dto = decode("""{"id":"101","type":5,"guild_id":"1","name":"announcements","position":0}""")
        val channel = dto.toDomain()
        assertIs<GuildTextChannel>(channel)
        assertEquals("announcements", channel.name)
    }

    @Test
    fun type_4_category_maps_to_guild_category_channel() {
        val dto = decode("""{"id":"102","type":4,"guild_id":"1","name":"Start Here","position":0}""")
        val channel = dto.toDomain()
        assertIs<GuildCategoryChannel>(channel)
        assertEquals("Start Here", channel.name)
        assertEquals(0, channel.position)
    }

    @Test
    fun type_2_voice_maps_to_guild_voice_channel() {
        val dto = decode(
            """{"id":"103","type":2,"guild_id":"1","name":"general-voice","bitrate":64000,"user_limit":10}""",
        )
        val channel = dto.toDomain()
        assertIs<GuildVoiceChannel>(channel)
        assertEquals("general-voice", channel.name)
        assertEquals(64000, channel.bitrate)
        assertEquals(10, channel.userLimit)
    }

    @Test
    fun type_13_stage_voice_maps_to_guild_voice_channel() {
        val dto = decode("""{"id":"104","type":13,"guild_id":"1","name":"Stage"}""")
        val channel = dto.toDomain()
        assertIs<GuildVoiceChannel>(channel)
        assertEquals("Stage", channel.name)
    }

    @Test
    fun type_15_forum_is_filtered_out() {
        val dto = decode("""{"id":"105","type":15,"guild_id":"1","name":"forum"}""")
        assertNull(dto.toDomain())
    }

    private fun decode(raw: String): DiscordChannelDto =
        DiscordJsonStrict.decodeFromString(DiscordChannelDto.serializer(), raw)
}
