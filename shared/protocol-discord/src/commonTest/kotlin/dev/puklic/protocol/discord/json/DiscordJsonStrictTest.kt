package dev.puklic.protocol.discord.json

import dev.puklic.protocol.discord.DiscordJson
import dev.puklic.protocol.discord.DiscordJsonStrict
import dev.puklic.protocol.discord.dto.DiscordUserDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException

class DiscordJsonStrictTest {
    @Test
    fun production_json_tolerates_unknown_fields() {
        val raw = """{"id":"1","username":"u","mystery_new_field":42}"""
        val dto = DiscordJson.decodeFromString(DiscordUserDto.serializer(), raw)
        assertEquals("1", dto.id)
        assertEquals("u", dto.username)
    }

    @Test
    fun strict_json_rejects_unknown_fields() {
        val raw = """{"id":"1","username":"u","mystery_new_field":42}"""
        assertFailsWith<SerializationException> {
            DiscordJsonStrict.decodeFromString(DiscordUserDto.serializer(), raw)
        }
    }
}
