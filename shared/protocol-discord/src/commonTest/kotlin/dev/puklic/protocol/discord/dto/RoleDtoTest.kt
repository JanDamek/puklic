package dev.puklic.protocol.discord.dto

import dev.puklic.ids.GuildId
import dev.puklic.protocol.discord.DiscordJsonStrict
import dev.puklic.protocol.discord.mapper.toDomain
import kotlin.test.Test
import kotlin.test.assertEquals

class RoleDtoTest {
    @Test
    fun role_decodes_color_field_and_maps_to_domain() {
        val raw = """{"id":"42","name":"admins","permissions":"8","position":3,"color":13369344}"""
        val dto = DiscordJsonStrict.decodeFromString(DiscordRoleDto.serializer(), raw)
        assertEquals(0xCC0000, dto.color)
        val role = dto.toDomain(GuildId(99L))
        assertEquals(0xCC0000, role.color)
        assertEquals("admins", role.name)
    }

    @Test
    fun role_color_defaults_to_zero_when_absent() {
        val raw = """{"id":"42","name":"admins","permissions":"0","position":0}"""
        val dto = DiscordJsonStrict.decodeFromString(DiscordRoleDto.serializer(), raw)
        assertEquals(0, dto.color)
        val role = dto.toDomain(GuildId(99L))
        assertEquals(0, role.color)
    }
}
