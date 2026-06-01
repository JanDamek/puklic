package dev.puklic.protocol.discord

import dev.puklic.ids.GuildId
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test

/**
 * Issue #85 — READY user_settings extraction. guild_folders (modern) wins over guild_positions
 * (legacy fallback). Returns null when neither shape is present.
 */
class GuildPositionsExtractorTest {

    private fun parseReady(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    @Test
    fun guild_folders_flattened_in_folder_order() {
        val ready = parseReady("""
            {
              "user_settings": {
                "guild_folders": [
                  { "id": null, "guild_ids": ["10", "20"] },
                  { "id": 42, "guild_ids": ["30"] }
                ]
              }
            }
        """.trimIndent())
        val ev = extractGuildPositionsFromReady(ready)
        ev?.positions shouldBe listOf(GuildId(10L), GuildId(20L), GuildId(30L))
    }

    @Test
    fun guild_positions_legacy_fallback_used_when_folders_absent() {
        val ready = parseReady("""
            { "user_settings": { "guild_positions": ["5", "7", "9"] } }
        """.trimIndent())
        val ev = extractGuildPositionsFromReady(ready)
        ev?.positions shouldBe listOf(GuildId(5L), GuildId(7L), GuildId(9L))
    }

    @Test
    fun returns_null_when_user_settings_missing() {
        val ready = parseReady("""{ "session_id": "s" }""")
        extractGuildPositionsFromReady(ready) shouldBe null
    }

    @Test
    fun returns_null_when_both_arrays_empty() {
        val ready = parseReady("""
            { "user_settings": { "guild_folders": [], "guild_positions": [] } }
        """.trimIndent())
        extractGuildPositionsFromReady(ready) shouldBe null
    }
}
