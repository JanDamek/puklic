package dev.puklic.protocol.discord.dto

import dev.puklic.protocol.discord.DiscordJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceDtoTest {
    @Test
    fun voice_state_update_parses_required_fields() {
        // Realistic shape per Discord docs example for VOICE_STATE_UPDATE dispatch.
        val raw = """
            {
              "guild_id": "41771983423143937",
              "channel_id": "157733188964188161",
              "user_id": "104694319306248192",
              "session_id": "90326bd25d71d39b9ef95b299e3872ff",
              "deaf": false,
              "mute": false,
              "self_deaf": false,
              "self_mute": true,
              "suppress": false,
              "request_to_speak_timestamp": null
            }
        """.trimIndent()
        val dto = DiscordJson.decodeFromString(VoiceStateUpdateDto.serializer(), raw)
        assertEquals("41771983423143937", dto.guildId)
        assertEquals("157733188964188161", dto.channelId)
        assertEquals("104694319306248192", dto.userId)
        assertEquals("90326bd25d71d39b9ef95b299e3872ff", dto.sessionId)
        assertTrue(dto.selfMute)
        assertFalse(dto.selfDeaf)
        assertFalse(dto.mute)
        assertFalse(dto.deaf)
    }

    @Test
    fun voice_state_update_tolerates_null_channel_id_leave_event() {
        val raw = """
            {
              "guild_id": "41771983423143937",
              "channel_id": null,
              "user_id": "104694319306248192",
              "session_id": "abc"
            }
        """.trimIndent()
        val dto = DiscordJson.decodeFromString(VoiceStateUpdateDto.serializer(), raw)
        assertNull(dto.channelId)
        assertEquals("abc", dto.sessionId)
    }

    @Test
    fun voice_server_update_parses_token_endpoint_guild() {
        val raw = """
            {
              "token": "my_token",
              "guild_id": "41771983423143937",
              "endpoint": "smart.loyal.discord.gg"
            }
        """.trimIndent()
        val dto = DiscordJson.decodeFromString(VoiceServerUpdateDto.serializer(), raw)
        assertEquals("my_token", dto.token)
        assertEquals("41771983423143937", dto.guildId)
        assertEquals("smart.loyal.discord.gg", dto.endpoint)
    }

    @Test
    fun voice_server_update_endpoint_null_during_region_migration() {
        val raw = """
            {
              "token": "tok",
              "guild_id": "41771983423143937",
              "endpoint": null
            }
        """.trimIndent()
        val dto = DiscordJson.decodeFromString(VoiceServerUpdateDto.serializer(), raw)
        assertNull(dto.endpoint)
        assertEquals("tok", dto.token)
    }
}
