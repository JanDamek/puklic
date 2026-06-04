package dev.puklic.protocol.discord

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [parseGuildOrderFromSettingsProto] — extraction of guild display order
 * from Discord's UserSettings protobuf.
 *
 * UserSettings proto layout:
 *   field 3 (wt2) = GuildFolderSettings
 *     field 1 (wt2, repeated) = folders (GuildFolder)
 *       field 1 = guild_ids: fixed64 (wt1, unpacked) OR packed (wt2, 8 bytes each)
 */
class GuildOrderProtoTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        require(clean.length % 2 == 0) { "odd hex length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Little-endian 8-byte encoding of a Long, as a hex string. */
    private fun le64(value: Long): String {
        val sb = StringBuilder()
        for (i in 0 until 8) {
            val b = ((value ushr (i * 8)) and 0xFF).toInt()
            sb.append(b.toString(16).padStart(2, '0'))
        }
        return sb.toString()
    }

    @Test
    fun realFixtureSingleGuild() {
        // Full UserSettings proto: field 3 tag 0x1a, len 0x0e, 14 bytes payload.
        val bytes = hex("1a0e09d22084d229a3690f12032a0110")
        assertEquals(listOf(1110598183144399058L), parseGuildOrderFromSettingsProto(bytes))
    }

    @Test
    fun multiGuildUnpacked() {
        // Two folders, each holding one unpacked fixed64 guild id (100, 200).
        // Folder = tag 0x0a (field1 wt2), len 0x09, then 0x09 (guild_ids fixed64) + 8 LE bytes.
        val folder1 = "0a09" + "09" + le64(100L) // 0a09 09 6400000000000000
        val folder2 = "0a09" + "09" + le64(200L) // 0a09 09 c800000000000000
        val content = folder1 + folder2          // 22 bytes
        val proto = "1a16" + content             // field3 tag, len 0x16 (22)
        // Bytes used: 1a16 0a0909 6400000000000000 0a0909 c800000000000000
        assertEquals(listOf(100L, 200L), parseGuildOrderFromSettingsProto(hex(proto)))
    }

    @Test
    fun packedGuildIds() {
        // One folder whose field 1 is packed (wt2) holding two fixed64 ids (7, 8).
        // guild_ids packed = tag 0x0a (folder field1 wt2), len 0x10 (16), then 16 LE bytes.
        val packed = "0a10" + le64(7L) + le64(8L)
        // Folder = tag 0x0a (GuildFolderSettings field1 wt2), len 0x12 (18), then packed.
        val folder = "0a12" + packed
        val proto = "1a14" + folder // field3 tag, len 0x14 (20)
        // Bytes used: 1a14 0a12 0a10 0700000000000000 0800000000000000
        assertEquals(listOf(7L, 8L), parseGuildOrderFromSettingsProto(hex(proto)))
    }

    @Test
    fun emptyInputReturnsEmpty() {
        assertEquals(emptyList(), parseGuildOrderFromSettingsProto(ByteArray(0)))
    }

    @Test
    fun noField3ReturnsEmpty() {
        // Only field 1 varint (08 01), no GuildFolderSettings.
        assertEquals(emptyList(), parseGuildOrderFromSettingsProto(hex("0801")))
    }

    @Test
    fun truncatedField3ReturnsEmptyNoCrash() {
        // field 3 tag claims len 0x0e but payload is short → no crash, emptyList.
        assertEquals(emptyList(), parseGuildOrderFromSettingsProto(hex("1a0e0901")))
    }
}
