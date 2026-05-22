package dev.puklic.protocol.discord

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class DiscordClientPropertiesTest {

    @Test
    fun serialized_json_uses_snake_case_and_required_fields() {
        val props = DiscordClientProperties(
            os = "Mac OS X",
            systemLocale = "en-US",
            browserUserAgent = "Mozilla/5.0 ...",
            browserVersion = "33.4.0",
            osVersion = "14.5.0",
        )
        val json = serializeSuperProperties(props)
        val obj = Json.parseToJsonElement(json) as JsonObject

        assertEquals("Mac OS X", obj.getValue("os").jsonPrimitive.content)
        assertEquals("Discord Client", obj.getValue("browser").jsonPrimitive.content)
        assertEquals("en-US", obj.getValue("system_locale").jsonPrimitive.content)
        assertEquals(false, obj.getValue("has_client_mods").jsonPrimitive.boolean)
        assertEquals("Mozilla/5.0 ...", obj.getValue("browser_user_agent").jsonPrimitive.content)
        assertEquals("33.4.0", obj.getValue("browser_version").jsonPrimitive.content)
        assertEquals("14.5.0", obj.getValue("os_version").jsonPrimitive.content)
        assertEquals("stable", obj.getValue("release_channel").jsonPrimitive.content)
        assertEquals(DEFAULT_CLIENT_BUILD_NUMBER, obj.getValue("client_build_number").jsonPrimitive.int)
        // explicitNulls=true so the null event source is emitted as JSON null.
        assertTrue("client_event_source" in obj.keys)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun super_properties_base64_roundtrips_to_original_json() {
        val props = DiscordClientProperties(
            os = "Linux",
            systemLocale = "cs-CZ",
            browserUserAgent = "ua",
            browserVersion = "33.4.0",
            osVersion = "6.8.0",
        )
        val expected = serializeSuperProperties(props)
        val decoded = Base64.decode(encodeSuperProperties(props)).decodeToString()
        assertEquals(expected, decoded)
    }

    @Test
    fun locale_falls_back_to_en_us_when_invalid() {
        assertEquals("en-US", normalizeLocale(null))
        assertEquals("en-US", normalizeLocale("garbage"))
        assertEquals("cs-CZ", normalizeLocale("cs-CZ"))
        assertEquals("en", normalizeLocale("en"))
    }

    @Test
    fun map_os_name_handles_common_values() {
        assertEquals("Mac OS X", mapOsName("Mac OS X"))
        assertEquals("Mac OS X", mapOsName("Darwin"))
        assertEquals("Linux", mapOsName("Linux"))
        assertEquals("Windows", mapOsName("Windows 11"))
        assertEquals("Unknown", mapOsName(null))
    }
}
