package dev.puklic.platform.macos.bridge

import com.sun.jna.Memory
import com.sun.jna.Pointer

/**
 * Helpers that turn JVM values (String / ByteArray / Boolean / Pointer) into
 * retain-balanced `CFTypeRef` pointers and assemble them into `CFDictionaryRef`
 * instances suitable for the Security framework `SecItem*` APIs.
 *
 * Memory model:
 *  - Every value created here returns a `CFTypeRef` with retain count == 1
 *    (created by `CFStringCreateWithBytes` / `CFDataCreate` /
 *    `CFDictionaryCreate`). Each call site MUST `CFRelease` returned pointers.
 *  - For dictionary construction we rely on `kCFTypeDictionaryKeyCallBacks`
 *    and `kCFTypeDictionaryValueCallBacks`, which retain the keys / values on
 *    insertion and release them on dictionary destruction. The caller still
 *    owns its inputs (we still must release the ones we created locally).
 *
 * Sentinel pointers (`kSecClassGenericPassword`, `kSecAttrSynchronizableAny`,
 * `kCFBooleanTrue`) are CoreFoundation constants — never retained / released.
 */
internal object CfQueryBuilder {

    private val cf = CoreFoundation.INSTANCE

    /** Wrap a Java String into a freshly-created `CFStringRef` (UTF-8 backed). */
    fun cfString(value: String): Pointer {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return cf.CFStringCreateWithBytes(
            CoreFoundation.K_CF_ALLOCATOR_DEFAULT,
            bytes,
            bytes.size.toLong(),
            CoreFoundation.K_CF_STRING_ENCODING_UTF8,
            /* isExternalRepresentation = */ 0,
        ) ?: error("CFStringCreateWithBytes returned NULL for \"$value\"")
    }

    /** Wrap a ByteArray into a freshly-created `CFDataRef`. */
    fun cfData(bytes: ByteArray): Pointer = cf.CFDataCreate(
        CoreFoundation.K_CF_ALLOCATOR_DEFAULT,
        bytes,
        bytes.size.toLong(),
    ) ?: error("CFDataCreate returned NULL for ${bytes.size} bytes")

    /** Copy a `CFStringRef` back into a UTF-8 Java String. */
    fun fromCfString(cfString: Pointer?): String? {
        if (cfString == null) return null
        val length = cf.CFStringGetLength(cfString)
        val maxBytes = cf.CFStringGetMaximumSizeForEncoding(
            length,
            CoreFoundation.K_CF_STRING_ENCODING_UTF8,
        ) + 1
        val buf = ByteArray(maxBytes.toInt())
        val ok = cf.CFStringGetCString(
            cfString,
            buf,
            maxBytes,
            CoreFoundation.K_CF_STRING_ENCODING_UTF8,
        )
        if (ok == 0.toByte()) return null
        val end = buf.indexOf(0.toByte()).let { if (it < 0) buf.size else it }
        return String(buf, 0, end, Charsets.UTF_8)
    }

    /** Copy a `CFDataRef` back into a Java ByteArray. */
    fun fromCfData(cfData: Pointer?): ByteArray? {
        if (cfData == null) return null
        val len = cf.CFDataGetLength(cfData)
        val out = ByteArray(len.toInt())
        cf.CFDataGetBytes(cfData, CoreFoundation.cfRange(0, len), out)
        return out
    }

    /**
     * Build a `CFDictionaryRef` from a list of key→value pointer pairs and
     * release any locally-created intermediates passed in [releaseAfter].
     *
     * Returned dictionary has retain count == 1; caller must `CFRelease` it.
     */
    fun cfDictionary(entries: List<Pair<Pointer, Pointer>>): Pointer {
        val keys = Array<Pointer?>(entries.size) { entries[it].first }
        val values = Array<Pointer?>(entries.size) { entries[it].second }
        return cf.CFDictionaryCreate(
            CoreFoundation.K_CF_ALLOCATOR_DEFAULT,
            keys,
            values,
            entries.size.toLong(),
            CoreFoundation.K_CF_TYPE_DICTIONARY_KEY_CALLBACKS,
            CoreFoundation.K_CF_TYPE_DICTIONARY_VALUE_CALLBACKS,
        ) ?: error("CFDictionaryCreate returned NULL")
    }

    /** Release every non-null pointer in [refs]. Safe to call with sentinels — pass only locally-created ones. */
    fun releaseAll(refs: List<Pointer?>) {
        refs.forEach { if (it != null) cf.CFRelease(it) }
    }

    /** Allocate a small native buffer; caller is responsible for closing via [Memory.close]. */
    fun mem(bytes: Long): Memory = Memory(bytes)
}
