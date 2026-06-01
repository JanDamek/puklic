package dev.puklic.protocol.discord.mapper

import dev.puklic.domain.EmojiRef
import dev.puklic.domain.MessageFlags
import dev.puklic.domain.MessageType
import dev.puklic.protocol.discord.DiscordJsonStrict
import dev.puklic.protocol.discord.dto.DiscordMessageDto
import dev.puklic.protocol.discord.dto.MESSAGE_CREATE_RICH
import dev.puklic.protocol.discord.dto.MESSAGE_CREATE_SIMPLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

class MessageMapperTest {
    @Test
    fun simple_message_to_domain() {
        val dto = DiscordJsonStrict.decodeFromString(DiscordMessageDto.serializer(), MESSAGE_CREATE_SIMPLE)
        val msg = dto.toDomain()
        assertEquals(1000000000000000001L, msg.id.value)
        assertEquals(2000000000000000002L, msg.channelId.value)
        assertEquals("alice", msg.author.username)
        assertNull(msg.author.discriminator) // pomelo "0" → null
        assertEquals("hello world", msg.rawContent)
        assertEquals(Instant.parse("2026-05-21T10:00:00.000000+00:00"), msg.timestamp)
        assertEquals(MessageFlags.NONE, msg.flags)
        assertTrue(msg.parsedContent.blocks.isNotEmpty())
    }

    @Test
    fun call_message_type_is_decoded() {
        val raw = """{
          "id": "1000000000000000003",
          "channel_id": "2000000000000000002",
          "author": {"id":"3000000000000000003","username":"tester","discriminator":"0"},
          "content": "",
          "timestamp": "2026-05-21T10:00:00.000000+00:00",
          "type": 3
        }"""
        val dto = DiscordJsonStrict.decodeFromString(DiscordMessageDto.serializer(), raw)
        val msg = dto.toDomain()
        assertEquals(MessageType.CALL, msg.type)
    }

    @Test
    fun unknown_message_type_falls_back_to_unknown_enum() {
        val raw = """{
          "id": "1000000000000000004",
          "channel_id": "2000000000000000002",
          "author": {"id":"3000000000000000003","username":"tester","discriminator":"0"},
          "content": "",
          "timestamp": "2026-05-21T10:00:00.000000+00:00",
          "type": 999
        }"""
        val dto = DiscordJsonStrict.decodeFromString(DiscordMessageDto.serializer(), raw)
        val msg = dto.toDomain()
        assertEquals(MessageType.UNKNOWN, msg.type)
    }

    @Test
    fun default_message_type_when_absent() {
        val dto = DiscordJsonStrict.decodeFromString(DiscordMessageDto.serializer(), MESSAGE_CREATE_SIMPLE)
        assertEquals(MessageType.DEFAULT, dto.toDomain().type)
    }

    @Test
    fun rich_message_to_domain() {
        val dto = DiscordJsonStrict.decodeFromString(DiscordMessageDto.serializer(), MESSAGE_CREATE_RICH)
        val msg = dto.toDomain()
        assertEquals(1, msg.attachments.size)
        assertEquals("photo.png", msg.attachments[0].filename)
        assertEquals(2, msg.mentions.users.size)
        assertEquals(1, msg.mentions.roles.size)
        assertTrue(msg.mentions.everyone)
        assertEquals(1, msg.reactions.size)
        val emoji = msg.reactions[0].emoji
        assertTrue(emoji is EmojiRef.Unicode)
        assertEquals("👍", (emoji as EmojiRef.Unicode).codepoint)
        assertNotNull(msg.editedTimestamp)
        val ref = msg.referencedMessage
        assertNotNull(ref)
        assertEquals(5000000000000000005L, ref.messageId?.value)
    }
}
