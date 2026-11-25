package dev.puklic.protocol.discord

private const val MAX_RANK = Int.MAX_VALUE
private const val BYTE_MASK = 0xFF
private const val VARINT_PAYLOAD_MASK = 0x7F
private const val VARINT_CONTINUATION = 0x80
private const val VARINT_PAYLOAD_BITS = 7

/**
 * Rewrites the guild order inside a full `UserSettings` protobuf while preserving every other
 * byte of it: unknown fields, folder ids / names / colours and all non-guild settings are copied
 * verbatim. Folder membership is kept (a guild never changes folder); inside a folder the guilds
 * follow [order], and top-level entries (folders and loose guilds) are arranged by the rank of
 * their best-ranked member. Guilds missing from [order] sort after ranked ones, keeping their
 * original relative position. Throws [IllegalArgumentException] on malformed input.
 */
internal fun rewriteGuildOrderInSettingsProto(bytes: ByteArray, order: List<Long>): ByteArray {
    val ranks = order.withIndex().associate { (index, id) -> id to index }
    val reader = ProtoReader(bytes)
    val out = ProtoBytes()
    while (reader.hasRemaining) {
        val start = reader.position
        val (field, wireType) = reader.readTag()
        if (field == FIELD_GUILD_FOLDER_SETTINGS && wireType == WT_LEN) {
            val rewritten = rewriteFolderSettings(reader.readLengthDelimited(), ranks)
            out.writeLengthDelimited(FIELD_GUILD_FOLDER_SETTINGS, rewritten)
        } else {
            reader.skipField(wireType)
            out.write(reader.sliceFrom(start))
        }
    }
    return out.toByteArray()
}

private class Entry(val raw: ByteArray, val rank: Int)

private fun rewriteFolderSettings(payload: ByteArray, ranks: Map<Long, Int>): ByteArray {
    val reader = ProtoReader(payload)
    val others = ProtoBytes()
    val entries = mutableListOf<Entry>()
    while (reader.hasRemaining) {
        val start = reader.position
        val (field, wireType) = reader.readTag()
        when {
            field == FIELD_FOLDER_OR_GUILD_ID && wireType == WT_FIXED64 -> {
                val id = reader.readFixed64()
                entries += Entry(reader.sliceFrom(start), ranks[id] ?: MAX_RANK)
            }
            field == FIELD_FOLDER_OR_GUILD_ID && wireType == WT_LEN ->
                entries += rewriteFolder(reader.readLengthDelimited(), ranks)
            else -> {
                reader.skipField(wireType)
                others.write(reader.sliceFrom(start))
            }
        }
    }
    return ProtoBytes().apply {
        write(others.toByteArray())
        entries.sortedBy { it.rank }.forEach { write(it.raw) }
    }.toByteArray()
}

private fun rewriteFolder(payload: ByteArray, ranks: Map<Long, Int>): Entry {
    val reader = ProtoReader(payload)
    val others = ProtoBytes()
    val ids = mutableListOf<Long>()
    while (reader.hasRemaining) {
        val start = reader.position
        val (field, wireType) = reader.readTag()
        when {
            field == FIELD_FOLDER_OR_GUILD_ID && wireType == WT_FIXED64 -> ids += reader.readFixed64()
            field == FIELD_FOLDER_OR_GUILD_ID && wireType == WT_LEN ->
                ids += ProtoReader(reader.readLengthDelimited()).readPackedFixed64()
            else -> {
                reader.skipField(wireType)
                others.write(reader.sliceFrom(start))
            }
        }
    }
    val sortedIds = ids.sortedBy { ranks[it] ?: MAX_RANK }
    val body = ProtoBytes().apply {
        writeLengthDelimited(FIELD_FOLDER_OR_GUILD_ID, ProtoBytes().apply { sortedIds.forEach(::writeFixed64) }.toByteArray())
        write(others.toByteArray())
    }.toByteArray()
    val raw = ProtoBytes().apply { writeLengthDelimited(FIELD_FOLDER_OR_GUILD_ID, body) }.toByteArray()
    return Entry(raw, sortedIds.minOfOrNull { ranks[it] ?: MAX_RANK } ?: MAX_RANK)
}

/** Builds the `GuildFolderSettings` update message that the settings-proto PATCH expects. */
internal fun extractGuildFolderSettingsField(settings: ByteArray): ByteArray {
    val reader = ProtoReader(settings)
    val out = ProtoBytes()
    while (reader.hasRemaining) {
        val start = reader.position
        val (field, wireType) = reader.readTag()
        reader.skipField(wireType)
        if (field == FIELD_GUILD_FOLDER_SETTINGS && wireType == WT_LEN) out.write(reader.sliceFrom(start))
    }
    return out.toByteArray()
}

private class ProtoBytes {
    private val buffer = mutableListOf<Byte>()

    fun write(bytes: ByteArray) {
        bytes.forEach(buffer::add)
    }

    fun writeVarint(value: Long) {
        var remaining = value
        while (remaining and VARINT_PAYLOAD_MASK.toLong().inv() != 0L) {
            buffer += ((remaining and VARINT_PAYLOAD_MASK.toLong()).toInt() or VARINT_CONTINUATION).toByte()
            remaining = remaining ushr VARINT_PAYLOAD_BITS
        }
        buffer += remaining.toInt().toByte()
    }

    fun writeFixed64(value: Long) {
        for (i in 0 until FIXED64_BYTES) buffer += ((value ushr (i * 8)).toInt() and BYTE_MASK).toByte()
    }

    fun writeLengthDelimited(field: Int, payload: ByteArray) {
        writeVarint(((field shl 3) or WT_LEN).toLong())
        writeVarint(payload.size.toLong())
        write(payload)
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}
