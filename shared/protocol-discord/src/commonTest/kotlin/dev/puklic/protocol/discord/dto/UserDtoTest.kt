package dev.puklic.protocol.discord.dto

import dev.puklic.protocol.discord.DiscordJsonStrict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserDtoTest {
    @Test
    fun pomelo_user_with_discriminator_zero() {
        val raw = """{"id":"100","username":"newname","global_name":"Display","discriminator":"0","avatar":"hash"}"""
        val dto = DiscordJsonStrict.decodeFromString(DiscordUserDto.serializer(), raw)
        assertEquals("100", dto.id)
        assertEquals("Display", dto.globalName)
        assertEquals("0", dto.discriminator)
    }

    @Test
    fun legacy_user_with_real_discriminator() {
        val raw = """{"id":"200","username":"legacy","discriminator":"1234"}"""
        val dto = DiscordJsonStrict.decodeFromString(DiscordUserDto.serializer(), raw)
        assertEquals("1234", dto.discriminator)
        assertNull(dto.globalName)
    }
}
