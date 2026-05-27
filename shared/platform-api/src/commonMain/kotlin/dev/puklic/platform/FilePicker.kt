package dev.puklic.platform

/**
 * Platform-neutral file-picker abstraction for attachment uploads (issue #23). Implementations
 * live in the desktop / mobile platform modules. The picker reads the entire file synchronously
 * into memory — the size budget enforced by `dev.puklic.repositories.AttachmentLimits` keeps
 * this from blowing past the project RAM target.
 */
public interface FilePicker {

    /**
     * Show a system file-picker dialog and return the picked files. Returns an empty list if
     * the user cancels. Multi-select is allowed; the caller may impose its own count limit.
     */
    public suspend fun pick(allowMultiple: Boolean = true): List<PickedFile>
}

/** A file the user picked. [bytes] is the full file content already loaded into memory. */
public data class PickedFile(
    val filename: String,
    val bytes: ByteArray,
    val contentType: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PickedFile) return false
        return filename == other.filename && bytes.contentEquals(other.bytes) && contentType == other.contentType
    }
    override fun hashCode(): Int {
        var h = filename.hashCode()
        h = 31 * h + bytes.contentHashCode()
        h = 31 * h + (contentType?.hashCode() ?: 0)
        return h
    }
}
