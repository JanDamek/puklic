package dev.puklic.protocol.discord.mapper

import dev.puklic.domain.GuildVoiceChannel
import dev.puklic.protocol.discord.DiscordJsonStrict
import dev.puklic.protocol.discord.dto.DiscordChannelDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Voice channel mapping coverage. Discord types:
 *  - type 2  = GUILD_VOICE       → GuildVoiceChannel
 *  - type 13 = GUILD_STAGE_VOICE → GuildVoiceChannel (Stage collapsed into voice for v1)
 */
class GuildMapperVoiceTest {

    @Test
    fun discord_type_2_with_name_general_voice_maps_to_voice_channel() {
        val dto = decode(
            """{"id":"500","type":2,"guild_id":"1","name":"general-voice","position":3,"bitrate":96000,"user_limit":99}""",
        )
        val channel = dto.toDomain()
        assertIs<GuildVoiceChannel>(channel)
        assertEquals("general-voice", channel.name)
        assertEquals(3, channel.position)
        assertEquals(96000, channel.bitrate)
        assertEquals(99, channel.userLimit)
    }

    @Test
    fun discord_type_13_stage_maps_to_voice_channel() {
        val dto = decode("""{"id":"501","type":13,"guild_id":"1","name":"Town Hall","position":0}""")
        val channel = dto.toDomain()
        assertIs<GuildVoiceChannel>(channel)
        assertEquals("Town Hall", channel.name)
    }

    private fun decode(raw: String): DiscordChannelDto =
        DiscordJsonStrict.decodeFromString(DiscordChannelDto.serializer(), raw)
}
