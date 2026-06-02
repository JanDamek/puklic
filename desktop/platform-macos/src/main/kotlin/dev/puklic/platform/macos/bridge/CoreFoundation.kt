package dev.puklic.platform.macos.bridge

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * JNA bindings for the slice of `CoreFoundation.framework` needed to build
 * `CFDictionaryRef` queries for `SecItemAdd` / `SecItemCopyMatching` /
 * `SecItemDelete` (Issue #74 — iCloud Keychain sync).
 *
 * Scope intentionally minimal: only the CF symbols required to construct
 * string / data / dictionary / array values and to read result-array contents
 * back. CoreFoundation globals (`kCFAllocatorDefault`, `kCFBooleanTrue`,
 * `kCFTypeDictionaryKeyCallBacks`, `kCFTypeDictionaryValueCallBacks`) are
 * dereferenced once via [NativeLibrary.getGlobalVariableAddress].
 */
internal interface CoreFoundation : Library {

    fun CFRetain(cf: Pointer?): Pointer?
    fun CFRelease(cf: Pointer?)

    fun CFStringCreateWithBytes(
        allocator: Pointer?,
        bytes: ByteArray,
        numBytes: Long,
        encoding: Int,
        isExternalRepresentation: Byte,
    ): Pointer?

    /**
     * Copies the contents of `CFStringRef` into a freshly-allocated UTF-8
     * Java string via `CFStringGetCString`. Returns `null` if the conversion
     * fails or `cfString` is `null`.
     */
    fun CFStringGetLength(theString: Pointer?): Long
    fun CFStringGetMaximumSizeForEncoding(length: Long, encoding: Int): Long
    fun CFStringGetCString(
        theString: Pointer?,
        buffer: ByteArray,
        bufferSize: Long,
        encoding: Int,
    ): Byte

    fun CFDataCreate(allocator: Pointer?, bytes: ByteArray, length: Long): Pointer?
    fun CFDataGetLength(theData: Pointer?): Long

    /**
     * `CFRange` is a struct passed by VALUE (two CFIndex, 16 bytes on 64-bit).
     * JNA must receive a `Structure.ByValue` here — passing it as `LongArray`
     * sends a pointer instead, and CFDataGetBytes then reads garbage for
     * `range.length`, triggering an internal assert in `CFDataGetBytes.cold.8`
     * and aborting the process. (Issue #74 follow-up — v1.2.6 crash regression.)
     */
    fun CFDataGetBytes(theData: Pointer?, range: CFRangeByValue, buffer: ByteArray)

    fun CFDictionaryCreate(
        allocator: Pointer?,
        keys: Array<Pointer?>,
        values: Array<Pointer?>,
        numValues: Long,
        keyCallBacks: Pointer?,
        valueCallBacks: Pointer?,
    ): Pointer?

    fun CFDictionaryGetValue(theDict: Pointer?, key: Pointer?): Pointer?

    fun CFArrayGetCount(theArray: Pointer?): Long
    fun CFArrayGetValueAtIndex(theArray: Pointer?, idx: Long): Pointer?

    fun CFGetTypeID(cf: Pointer?): Long
    fun CFDictionaryGetTypeID(): Long
    fun CFArrayGetTypeID(): Long

    companion object {
        /** `kCFStringEncodingUTF8` from CFString.h. */
        const val K_CF_STRING_ENCODING_UTF8: Int = 0x08000100

        /** `CFRange` as a JNA `Structure.ByValue` so it lands in registers / stack
         * per Apple's ABI rather than being passed as a pointer. */
        fun cfRange(start: Long, length: Long): CFRangeByValue =
            CFRangeByValue().apply { location = start; this.length = length }

        val INSTANCE: CoreFoundation by lazy {
            Native.load("CoreFoundation", CoreFoundation::class.java)
        }

        private val lib: NativeLibrary by lazy {
            NativeLibrary.getInstance("CoreFoundation")
        }

        val K_CF_ALLOCATOR_DEFAULT: Pointer? = null

        val K_CF_BOOLEAN_TRUE: Pointer by lazy {
            lib.getGlobalVariableAddress("kCFBooleanTrue").getPointer(0)
        }

        val K_CF_TYPE_DICTIONARY_KEY_CALLBACKS: Pointer by lazy {
            lib.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks")
        }

        val K_CF_TYPE_DICTIONARY_VALUE_CALLBACKS: Pointer by lazy {
            lib.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks")
        }

        fun readGlobalPointer(symbol: String): Pointer =
            lib.getGlobalVariableAddress(symbol).getPointer(0)
    }
}

/**
 * `CFRange` is `{CFIndex location; CFIndex length;}` (16 bytes on LP64). Apple's
 * ABI passes it BY VALUE — JNA needs a `Structure.ByValue` to do that. Passing
 * `LongArray` here would send a pointer to the array and cause CFDataGetBytes
 * to read garbage for `length`, hitting an internal assert in
 * `CFDataGetBytes.cold.8` and aborting the process (v1.2.6 crash regression).
 */
@Structure.FieldOrder("location", "length")
internal open class CFRange : Structure() {
    @JvmField var location: Long = 0
    @JvmField var length: Long = 0
}

internal class CFRangeByValue : CFRange(), Structure.ByValue
