package dev.puklic.desktop.macappstore.bridge

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * JNA bindings for the small slice of CoreFoundation (and CoreFoundation-derived
 * Core Media / Core Video / Video Toolbox helpers) used by the Mac App Store
 * variant's codec bridges.
 *
 * Restricted to the truly generic CF symbols here ([CFRetain], [CFRelease],
 * [CFNumberCreate], [CFDictionaryGetValue], CF constants). Service-specific
 * symbols (`VTCompressionSessionCreate`, `nw_connection_create`, …) live in
 * their respective Library interfaces ([VideoToolbox], [Network], …) so each
 * JNA `Native.load` only pulls in symbols that framework actually exports.
 *
 * The companion exposes constants of CoreFoundation that we read by symbol
 * name from `CoreFoundation.framework/CoreFoundation` and statically dereference
 * once (`kCFAllocatorDefault`, `kCFNumberIntType`, `kCFBooleanTrue/False`).
 */
internal interface CoreFoundation : Library {

    fun CFRetain(cf: Pointer?): Pointer?
    fun CFRelease(cf: Pointer?)

    /**
     * `CFNumberRef CFNumberCreate(CFAllocatorRef, CFNumberType, const void *valuePtr)`.
     * For an `int`, [type] is [CF_NUMBER_INT_TYPE] and [valuePtr] points to a 4-byte int.
     */
    fun CFNumberCreate(allocator: Pointer?, type: Int, valuePtr: Pointer): Pointer

    fun CFDictionaryGetValue(dict: Pointer?, key: Pointer?): Pointer?

    fun CFArrayGetCount(array: Pointer?): Long
    fun CFArrayGetValueAtIndex(array: Pointer?, idx: Long): Pointer?

    fun CFBooleanGetValue(b: Pointer?): Byte

    companion object {
        /** `kCFNumberIntType` from `CoreFoundation/CFNumber.h`. */
        const val CF_NUMBER_INT_TYPE: Int = 9

        val INSTANCE: CoreFoundation by lazy {
            Native.load("CoreFoundation", CoreFoundation::class.java)
        }

        private val cfLib: NativeLibrary by lazy {
            NativeLibrary.getInstance("CoreFoundation")
        }

        /** `kCFAllocatorDefault` — a `CFAllocatorRef` global; pass `NULL` for the same effect. */
        val K_CF_ALLOCATOR_DEFAULT: Pointer? = null

        /** `kCFBooleanTrue` global from CoreFoundation. */
        val K_CF_BOOLEAN_TRUE: Pointer by lazy {
            cfLib.getGlobalVariableAddress("kCFBooleanTrue").getPointer(0)
        }

        /** `kCFBooleanFalse` global from CoreFoundation. */
        val K_CF_BOOLEAN_FALSE: Pointer by lazy {
            cfLib.getGlobalVariableAddress("kCFBooleanFalse").getPointer(0)
        }

        fun readGlobalPointer(symbol: String): Pointer =
            cfLib.getGlobalVariableAddress(symbol).getPointer(0)
    }
}
