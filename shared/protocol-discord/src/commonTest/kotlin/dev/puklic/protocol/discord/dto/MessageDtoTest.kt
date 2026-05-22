package dev.puklic.protocol.discord.dto

import dev.puklic.protocol.discord.DiscordJsonStrict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException

class MessageDtoTest {
    @Test
    fun simple_message_strict_roundtrip() {
        val raw = MESSAGE_CREATE_SIMPLE
        val dto = DiscordJsonStrict.decodeFromString(DiscordMessageDto.serializer(), raw)
        assertEquals("1000000000000000001", dto.id)
        assertEquals("2000000000000000002", dto.channelId)
        assertEquals("hello world", dto.content)
        assertEquals("3000000000000000003", dto.author.id)
        assertTrue(dto.attachments.isEmpty())
        assertTrue(dto.embeds.isEmpty())
    }

    @Test
    fun rich_message_with_attachment_and_embed() {
        val dto = DiscordJsonStrict.decodeFromString(DiscordMessageDto.serializer(), MESSAGE_CREATE_RICH)
        assertEquals(1, dto.attachments.size)
        assertEquals("photo.png", dto.attachments[0].filename)
        assertEquals(2048L, dto.attachments[0].size)
        assertEquals(1, dto.embeds.size)
        assertEquals("Embed title", dto.embeds[0].title)
        assertEquals(1, dto.reactions.size)
        assertEquals(2, dto.mentions.size)
        assertTrue(dto.mentionEveryone)
        assertEquals("4000000000000000004", dto.mentionRoles[0])
        val ref = dto.messageReference
        assertNotNull(ref)
        assertEquals("5000000000000000005", ref.messageId)
    }

    @Test
    fun edited_timestamp_is_nullable() {
        val raw = MESSAGE_CREATE_SIMPLE
        val dto = DiscordJsonStrict.decodeFromString(DiscordMessageDto.serializer(), raw)
        assertNull(dto.editedTimestamp)
    }

    @Test
    fun strict_rejects_unknown_field_in_message() {
        val raw = """{"id":"1","channel_id":"2","author":{"id":"3","username":"u"},"content":"x","timestamp":"2026-01-01T00:00:00.000Z","mystery_field":true}"""
        assertFailsWith<SerializationException> {
            DiscordJsonStrict.decodeFromString(DiscordMessageDto.serializer(), raw)
        }
    }
}

internal const val MESSAGE_CREATE_SIMPLE = """{
  "id": "1000000000000000001",
  "channel_id": "2000000000000000002",
  "author": {
    "id": "3000000000000000003",
    "username": "alice",
    "discriminator": "0"
  },
  "content": "hello world",
  "timestamp": "2026-05-21T10:00:00.000000+00:00"
}"""

internal const val MESSAGE_CREATE_RICH = """{
  "id": "1000000000000000001",
  "channel_id": "2000000000000000002",
  "guild_id": "9000000000000000009",
  "author": {
    "id": "3000000000000000003",
    "username": "alice",
    "global_name": "Alice",
    "discriminator": "0",
    "avatar": "abcd"
  },
  "content": "hey @everyone look",
  "timestamp": "2026-05-21T10:00:00.000000+00:00",
  "edited_timestamp": "2026-05-21T10:01:00.000000+00:00",
  "mention_everyone": true,
  "mention_roles": ["4000000000000000004"],
  "mentions": [
    {"id":"3000000000000000003","username":"alice"},
    {"id":"3000000000000000099","username":"bob"}
  ],
  "attachments": [
    {
      "id": "7000000000000000007",
      "filename": "photo.png",
      "size": 2048,
      "url": "https://cdn.discordapp.com/.../photo.png",
      "proxy_url": "https://media.discordapp.net/.../photo.png",
      "content_type": "image/png",
      "width": 100,
      "height": 200
    }
  ],
  "embeds": [
    {
      "title": "Embed title",
      "description": "desc",
      "type": "rich",
      "color": 65280
    }
  ],
  "reactions": [
    {
      "count": 2,
      "me": false,
      "emoji": {"name":"👍"}
    }
  ],
  "message_reference": {
    "type": 0,
    "message_id": "5000000000000000005",
    "channel_id": "2000000000000000002"
  },
  "flags": 0
}"""
