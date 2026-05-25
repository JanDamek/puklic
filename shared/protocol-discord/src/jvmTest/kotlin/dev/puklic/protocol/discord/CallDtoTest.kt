package dev.puklic.protocol.discord

import dev.puklic.protocol.discord.dto.CallCreateDto
import dev.puklic.protocol.discord.dto.CallDeleteDto
import dev.puklic.protocol.discord.dto.CallUpdateDto
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * DTO deserialization for the DM incoming-call gateway dispatches (issue #19). Sample payload
 * shapes captured from `agent://claude-mcp/puklic-issue-19-step1-v2`.
 */
class CallDtoTest {

    private val json = discordJson()

    @Test
    fun call_create_with_voice_states_and_ringing_array_deserializes() {
        val payload = """
            {
              "channel_id": "111",
              "message_id": "222",
              "region": "us-east",
              "ringing": ["7"],
              "voice_states": [
                {"user_id": "42", "session_id": "sess-A", "self_mute": false, "self_deaf": false}
              ],
              "unavailable": false
            }
        """.trimIndent()
        val dto = json.decodeFromString(CallCreateDto.serializer(), payload)
        dto.channelId shouldBe "111"
        dto.messageId shouldBe "222"
        dto.region shouldBe "us-east"
        dto.ringing shouldBe listOf("7")
        dto.voiceStates.size shouldBe 1
        dto.voiceStates.first().userId shouldBe "42"
        dto.unavailable shouldBe false
    }

    @Test
    fun call_create_with_empty_voice_states_deserializes() {
        val payload = """
            {
              "channel_id": "111",
              "message_id": "222",
              "ringing": ["7"]
            }
        """.trimIndent()
        val dto = json.decodeFromString(CallCreateDto.serializer(), payload)
        dto.channelId shouldBe "111"
        dto.voiceStates.size shouldBe 0
        dto.ringing shouldBe listOf("7")
    }

    @Test
    fun call_update_without_voice_states_deserializes() {
        val payload = """
            {
              "channel_id": "111",
              "message_id": "222",
              "ringing": []
            }
        """.trimIndent()
        val dto = json.decodeFromString(CallUpdateDto.serializer(), payload)
        dto.channelId shouldBe "111"
        dto.ringing.isEmpty() shouldBe true
    }

    @Test
    fun call_delete_unavailable_true_deserializes() {
        val payload = """{"channel_id": "111", "unavailable": true}"""
        val dto = json.decodeFromString(CallDeleteDto.serializer(), payload)
        dto.channelId shouldBe "111"
        dto.unavailable shouldBe true
    }

    @Test
    fun call_delete_unavailable_absent_defaults_to_false() {
        val payload = """{"channel_id": "111"}"""
        val dto = json.decodeFromString(CallDeleteDto.serializer(), payload)
        dto.channelId shouldBe "111"
        dto.unavailable shouldBe false
    }
}
