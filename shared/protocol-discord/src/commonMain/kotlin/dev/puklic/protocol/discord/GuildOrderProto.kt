package dev.puklic.protocol.discord

/**
 * Minimal protobuf reader for Discord's UserSettings protobuf, extracting the guild
 * display order that modern accounts store in `user_settings_proto` (the legacy
 * `guild_folders` / `guild_positions` JSON is empty on such accounts).
 *
 * UserSettings proto layout (only the parts we read):
 *   field 3 (wt2) = GuildFolderSettings
 *     field 1 = either
 *       - wt1 (fixed64)            → a guild id directly attached to the settings root
 *       - wt2 (length-delimited)   → a GuildFolder, whose field 1 holds guild_ids:
 *           - wt1 (fixed64, unpacked repeated), or
 *           - wt2 (packed, 8 bytes each, little-endian)
 *
 * Guild ids are flattened across folders in encounter order. Any malformed,
 * truncated or out-of-bounds input yields an empty list and never throws.
 */
internal fun parseGuildOrderFromSettingsProto(bytes: ByteArray): List<Long> =
    runCatching { ProtoReader(bytes).readGuildOrder() }.getOrDefault(emptyList())

private const val WT_VARINT = 0
private const val WT_FIXED64 = 1
private const val WT_LEN = 2
private const val WT_FIXED32 = 5

private const val FIELD_GUILD_FOLDER_SETTINGS = 3
private const val FIELD_FOLDER_OR_GUILD_ID = 1

private const val FIXED64_BYTES = 8
private const val FIXED32_BYTES = 4

private class ProtoReader(private val bytes: ByteArray) {
    private var pos = 0

    fun readGuildOrder(): List<Long> {
        val ids = mutableListOf<Long>()
        while (pos < bytes.size) {
            val (field, wireType) = readTag()
            if (field == FIELD_GUILD_FOLDER_SETTINGS && wireType == WT_LEN) {
                val payload = readLengthDelimited()
                ProtoReader(payload).collectFromGuildFolderSettings(ids)
            } else {
                skipField(wireType)
            }
        }
        return ids
    }

    private fun collectFromGuildFolderSettings(ids: MutableList<Long>) {
        while (pos < bytes.size) {
            val (field, wireType) = readTag()
            when {
                field == FIELD_FOLDER_OR_GUILD_ID && wireType == WT_FIXED64 ->
                    ids.add(readFixed64())
                field == FIELD_FOLDER_OR_GUILD_ID && wireType == WT_LEN ->
                    ProtoReader(readLengthDelimited()).collectFromFolder(ids)
                else -> skipField(wireType)
            }
        }
    }

    private fun collectFromFolder(ids: MutableList<Long>) {
        while (pos < bytes.size) {
            val (field, wireType) = readTag()
            when {
                field == FIELD_FOLDER_OR_GUILD_ID && wireType == WT_FIXED64 ->
                    ids.add(readFixed64())
                field == FIELD_FOLDER_OR_GUILD_ID && wireType == WT_LEN ->
                    ids.addAll(ProtoReader(readLengthDelimited()).readPackedFixed64())
                else -> skipField(wireType)
            }
        }
    }

    private fun readPackedFixed64(): List<Long> {
        val ids = mutableListOf<Long>()
        while (pos < bytes.size) ids.add(readFixed64())
        return ids
    }

    private fun readTag(): Pair<Int, Int> {
        val tag = readVarint()
        return (tag ushr 3).toInt() to (tag and 0x7L).toInt()
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(pos < bytes.size) { "varint truncated" }
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "varint too long" }
        }
    }

    private fun readFixed64(): Long {
        require(pos + FIXED64_BYTES <= bytes.size) { "fixed64 truncated" }
        var result = 0L
        for (i in 0 until FIXED64_BYTES) {
            result = result or ((bytes[pos + i].toLong() and 0xFF) shl (i * 8))
        }
        pos += FIXED64_BYTES
        return result
    }

    private fun readLengthDelimited(): ByteArray {
        val len = readVarint().toInt()
        require(len >= 0 && pos + len <= bytes.size) { "length-delimited truncated" }
        val slice = bytes.copyOfRange(pos, pos + len)
        pos += len
        return slice
    }

    private fun skipField(wireType: Int) {
        when (wireType) {
            WT_VARINT -> readVarint()
            WT_FIXED64 -> advance(FIXED64_BYTES)
            WT_LEN -> readLengthDelimited()
            WT_FIXED32 -> advance(FIXED32_BYTES)
            else -> throw IllegalArgumentException("unknown wire type $wireType")
        }
    }

    private fun advance(n: Int) {
        require(pos + n <= bytes.size) { "field truncated" }
        pos += n
    }
}
