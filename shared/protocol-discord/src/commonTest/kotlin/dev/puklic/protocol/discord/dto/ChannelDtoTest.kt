package dev.puklic.protocol.discord.dto

import dev.puklic.protocol.discord.DiscordJsonStrict
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelDtoTest {
    @Test
    fun guild_text_channel() {
        val raw = """{"id":"100","type":0,"guild_id":"10","name":"general","position":1,"nsfw":false}"""
        val dto = DiscordJsonStrict.decodeFromString(DiscordChannelDto.serializer(), raw)
        assertEquals(0, dto.type)
        assertEquals("general", dto.name)
    }

    @Test
    fun dm_channel_with_recipients() {
        val raw = """{"id":"200","type":1,"recipients":[{"id":"3","username":"u"}]}"""
        val dto = DiscordJsonStrict.decodeFromString(DiscordChannelDto.serializer(), raw)
        assertEquals(1, dto.type)
        assertEquals(1, dto.recipients.size)
    }
}
