package dev.puklic.persistence.repository

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec: issue #96 — LastPositionCodec encode/decode contract.
 *
 * Wire format:
 *   Empty                                   -> ""
 *   DmHome(null)                            -> "dm"
 *   DmHome(ChannelId(5))                    -> "dm:5"
 *   Guild(GuildId(10), null)                -> "guild:10"
 *   Guild(GuildId(10), ChannelId(20))       -> "guild:10:20"
 *
 * decode is the inverse and is lenient: null, "", or any malformed/unknown
 * input maps to LastPosition.Empty.
 */
class LastPositionCodecTest {

    // 1. Round-trip: encode then decode returns an equal LastPosition.

    @Test
    fun roundTripEmpty() {
        val position: LastPosition = LastPosition.Empty
        assertEquals(position, LastPositionCodec.decode(LastPositionCodec.encode(position)))
    }

    @Test
    fun roundTripDmHomeNull() {
        val position: LastPosition = LastPosition.DmHome(null)
        assertEquals(position, LastPositionCodec.decode(LastPositionCodec.encode(position)))
    }

    @Test
    fun roundTripDmHomeChannel() {
        val position: LastPosition = LastPosition.DmHome(ChannelId(5))
        assertEquals(position, LastPositionCodec.decode(LastPositionCodec.encode(position)))
    }

    @Test
    fun roundTripGuildNullChannel() {
        val position: LastPosition = LastPosition.Guild(GuildId(10), null)
        assertEquals(position, LastPositionCodec.decode(LastPositionCodec.encode(position)))
    }

    @Test
    fun roundTripGuildWithChannel() {
        val position: LastPosition = LastPosition.Guild(GuildId(10), ChannelId(20))
        assertEquals(position, LastPositionCodec.decode(LastPositionCodec.encode(position)))
    }

    // 2. Exact encode strings.

    @Test
    fun encodeEmptyIsEmptyString() {
        assertEquals("", LastPositionCodec.encode(LastPosition.Empty))
    }

    @Test
    fun encodeDmHomeNull() {
        assertEquals("dm", LastPositionCodec.encode(LastPosition.DmHome(null)))
    }

    @Test
    fun encodeDmHomeChannel() {
        assertEquals("dm:5", LastPositionCodec.encode(LastPosition.DmHome(ChannelId(5))))
    }

    @Test
    fun encodeGuildNullChannel() {
        assertEquals("guild:10", LastPositionCodec.encode(LastPosition.Guild(GuildId(10), null)))
    }

    @Test
    fun encodeGuildWithChannel() {
        assertEquals("guild:10:20", LastPositionCodec.encode(LastPosition.Guild(GuildId(10), ChannelId(20))))
    }

    // 3. decode lenient: malformed/unknown -> Empty.

    @Test
    fun decodeNullIsEmpty() {
        assertEquals(LastPosition.Empty, LastPositionCodec.decode(null))
    }

    @Test
    fun decodeEmptyStringIsEmpty() {
        assertEquals(LastPosition.Empty, LastPositionCodec.decode(""))
    }

    @Test
    fun decodeGarbageIsEmpty() {
        assertEquals(LastPosition.Empty, LastPositionCodec.decode("garbage"))
    }

    @Test
    fun decodeGuildMissingIdIsEmpty() {
        assertEquals(LastPosition.Empty, LastPositionCodec.decode("guild:"))
    }

    @Test
    fun decodeGuildNonNumericIdIsEmpty() {
        assertEquals(LastPosition.Empty, LastPositionCodec.decode("guild:notanumber"))
    }

    // 4. decode dm variants.

    @Test
    fun decodeDmIsDmHomeNull() {
        assertEquals(LastPosition.DmHome(null), LastPositionCodec.decode("dm"))
    }

    @Test
    fun decodeDmChannelIsDmHomeChannel() {
        assertEquals(LastPosition.DmHome(ChannelId(5)), LastPositionCodec.decode("dm:5"))
    }
}
