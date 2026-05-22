package dev.puklic.protocol.discord.dto

import dev.puklic.protocol.discord.DiscordJsonStrict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuildDtoTest {
    @Test
    fun guild_with_features() {
        val raw = """{"id":"10","name":"Server","owner_id":"5","features":["COMMUNITY","VERIFIED"],"member_count":42}"""
        val dto = DiscordJsonStrict.decodeFromString(DiscordGuildDto.serializer(), raw)
        assertEquals("Server", dto.name)
        assertEquals(42, dto.memberCount)
        assertTrue("COMMUNITY" in dto.features)
        assertTrue("VERIFIED" in dto.features)
    }
}
