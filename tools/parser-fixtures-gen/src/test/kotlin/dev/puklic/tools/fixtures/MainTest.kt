package dev.puklic.tools.fixtures

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

class MainTest {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "kind" }

    @Test
    fun `renderJson returns top-level object with blocks array`() {
        val out = renderJson("**hello** _world_")
        out shouldContain "\"blocks\""
        val root = json.parseToJsonElement(out).jsonObject
        root.keys shouldBe setOf("blocks")
        root["blocks"]!!.jsonArray // does not throw
    }

    @Test
    fun `renderJson on blank input emits empty blocks`() {
        val root = json.parseToJsonElement(renderJson("")).jsonObject
        root["blocks"]!!.jsonArray.size shouldBe 0
    }

    @Test
    fun `renderJson tags paragraphs with kind discriminator`() {
        val out = renderJson("just text")
        out shouldContain "\"kind\""
        out shouldContain "paragraph"
    }

    @Test
    fun `renderJson preserves code blocks`() {
        val out = renderJson("```kotlin\nval x = 1\n```")
        val root: JsonObject = json.parseToJsonElement(out).jsonObject
        val firstBlock = root["blocks"]!!.jsonArray[0].jsonObject
        firstBlock["language"].toString() shouldContain "kotlin"
        firstBlock["content"].toString() shouldContain "val x = 1"
    }

    @Test
    fun `readInput help returns null`() {
        readInput(arrayOf("--help")) shouldBe null
        readInput(arrayOf("-h")) shouldBe null
    }

    @Test
    fun `readInput joins args with spaces`() {
        readInput(arrayOf("**a**", "_b_")) shouldBe "**a** _b_"
    }
}
