package dev.puklic.repositories

/**
 * In-memory representation of a file the user has staged for upload alongside a message. Per
 * architect decision Q1 on issue #23, pending attachments are NEVER persisted — if the client
 * crashes mid-upload, the user re-attaches. This mirrors the official Discord client and keeps
 * the persistence layer text-only.
 *
 * `bytes` is the raw file content already read into memory at picker-confirm time. We accept the
 * memory cost (bounded by [AttachmentLimits.maxBytesFor]) because attachments are short-lived:
 * uploaded and discarded within the lifetime of a single send.
 */
public data class PendingAttachment(
    val id: String,
    val filename: String,
    val bytes: ByteArray,
    val contentType: String? = null,
) {
    public val sizeBytes: Long get() = bytes.size.toLong()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingAttachment) return false
        return id == other.id && filename == other.filename && bytes.contentEquals(other.bytes) &&
            contentType == other.contentType
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + filename.hashCode()
        h = 31 * h + bytes.contentHashCode()
        h = 31 * h + (contentType?.hashCode() ?: 0)
        return h
    }
}

/** Error raised by [ComposerViewModel.send] when an attachment exceeds the tier limit. */
public class AttachmentTooLargeError(
    public val filename: String,
    public val sizeBytes: Long,
    public val limitBytes: Long,
) : RuntimeException(
    "Attachment '$filename' is ${sizeBytes} bytes, over the ${limitBytes}-byte limit for this channel.",
)
