package dev.puklic.screencast.macos

import com.sun.jna.Pointer

/**
 * Tiny Foundation helpers for the ScreenCaptureKit bridge (FP-8) — just the
 * pieces needed for string interop, array iteration, and `NSError` message
 * extraction.
 */
internal object Foundation {

    private val nsString: Pointer by lazy { ObjcRuntime.getClass("NSString") }
    private val selStringWithUTF8 by lazy { ObjcRuntime.sel("stringWithUTF8String:") }
    private val selUTF8String by lazy { ObjcRuntime.sel("UTF8String") }
    private val selCount by lazy { ObjcRuntime.sel("count") }
    private val selObjectAtIndex by lazy { ObjcRuntime.sel("objectAtIndex:") }
    private val selLocalizedDescription by lazy { ObjcRuntime.sel("localizedDescription") }
    private val selRetain by lazy { ObjcRuntime.sel("retain") }
    private val selRelease by lazy { ObjcRuntime.sel("release") }

    /**
     * Convert a JVM [String] to an autoreleased `NSString*`. Caller does NOT
     * own the returned pointer; do not release it. Use [retain] if it must
     * outlive the current autorelease pool.
     */
    fun toNSString(s: String): Pointer {
        val utf8 = s.toByteArray(Charsets.UTF_8) + 0
        val mem = com.sun.jna.Memory(utf8.size.toLong())
        mem.write(0, utf8, 0, utf8.size)
        return ObjcRuntime.msgSend(nsString, selStringWithUTF8, mem)
    }

    /** Convert `NSString*` to JVM [String]. Returns empty string if the input is null. */
    fun fromNSString(ns: Pointer?): String {
        if (ns == null || Pointer.nativeValue(ns) == 0L) return ""
        val cstr = ObjcRuntime.msgSend(ns, selUTF8String)
        if (Pointer.nativeValue(cstr) == 0L) return ""
        return cstr.getString(0, Charsets.UTF_8.name())
    }

    fun arrayCount(array: Pointer?): Long {
        if (array == null || Pointer.nativeValue(array) == 0L) return 0
        return ObjcRuntime.msgSendLong(array, selCount)
    }

    fun arrayObjectAt(array: Pointer, index: Long): Pointer =
        ObjcRuntime.msgSendLong(array, selObjectAtIndex, index)

    /** Read `[(NSError*)err localizedDescription]` as a Java string, or empty if null. */
    fun errorMessage(err: Pointer?): String {
        if (err == null || Pointer.nativeValue(err) == 0L) return ""
        return fromNSString(ObjcRuntime.msgSend(err, selLocalizedDescription))
    }

    fun retain(obj: Pointer): Pointer = ObjcRuntime.msgSend(obj, selRetain)

    fun release(obj: Pointer) {
        ObjcRuntime.msgSendVoid(obj, selRelease)
    }
}
