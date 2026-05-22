package dev.puklic.protocol.discord.dto

import dev.puklic.protocol.discord.DiscordJsonStrict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EmbedDtoTest {
    @Test
    fun full_embed() {
        val raw = """{
          "title":"t","description":"d","url":"https://x","type":"rich","color":255,
          "footer":{"text":"f"},
          "image":{"url":"https://i","width":1,"height":2},
          "fields":[{"name":"k","value":"v","inline":true}]
        }"""
        val dto = DiscordJsonStrict.decodeFromString(DiscordEmbedDto.serializer(), raw)
        assertEquals("t", dto.title)
        assertEquals(255, dto.color)
        assertNotNull(dto.footer)
        assertEquals(1, dto.fields.size)
        assertEquals(true, dto.fields[0].inline)
    }
}
