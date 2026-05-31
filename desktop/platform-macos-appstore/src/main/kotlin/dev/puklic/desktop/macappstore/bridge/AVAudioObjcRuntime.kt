package dev.puklic.desktop.macappstore.bridge

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * Minimal Objective-C runtime bindings via JNA for the Mac App Store JNA
 * AVAudioEngine bridge (FP-14h-3). Direct port of
 * `shared/screencast/src/jvmMain/.../macos/ObjcRuntime.kt` (FP-8). The two
 * copies stay in sync but are duplicated rather than shared because
 * `:shared:screencast` pulls FFmpeg-GPL onto its runtime classpath, which is
 * excluded from the Mac App Store ship by `verifyMacAppStoreNoGplDeps`.
 *
 * Per architect plan §3.2 + §5.1 in
 * `docs/03_infrastructure/architect-reports/2026-05-29-fp14h-3-implementation.md`.
 */
internal object AVAudioObjcRuntime {

    private val objc: ObjcLibrary by lazy {
        Native.load("objc.A", ObjcLibrary::class.java)
    }

    /** `_NSConcreteGlobalBlock` symbol — required `isa` for synchronous Objective-C blocks. */
    val nsConcreteGlobalBlock: Pointer by lazy {
        NativeLibrary.getInstance("System").getGlobalVariableAddress("_NSConcreteGlobalBlock")
            ?: error("libSystem does not expose _NSConcreteGlobalBlock — incompatible macOS runtime")
    }

    fun getClass(name: String): Pointer =
        objc.objc_getClass(name)
            ?: error("Objective-C class '$name' not found — host is not macOS or framework not linked")

    fun sel(name: String): Pointer = objc.sel_registerName(name)

    fun alloc(cls: Pointer): Pointer = objc.objc_msgSend_p(cls, sel("alloc"))

    fun msgSend(target: Pointer, sel: Pointer): Pointer = objc.objc_msgSend_p(target, sel)

    fun msgSend(target: Pointer, sel: Pointer, a: Pointer?): Pointer =
        objc.objc_msgSend_p_p(target, sel, a)

    fun msgSend(target: Pointer, sel: Pointer, a: Pointer?, b: Pointer?): Pointer =
        objc.objc_msgSend_p_p_p(target, sel, a, b)

    fun msgSend(target: Pointer, sel: Pointer, a: Pointer?, b: Pointer?, c: Pointer?): Pointer =
        objc.objc_msgSend_p_p_p_p(target, sel, a, b, c)

    fun msgSendVoid(target: Pointer, sel: Pointer) { objc.objc_msgSend_v(target, sel) }
    fun msgSendVoid(target: Pointer, sel: Pointer, a: Pointer?) { objc.objc_msgSend_v_p(target, sel, a) }
    fun msgSendVoid(target: Pointer, sel: Pointer, a: Pointer?, b: Pointer?) {
        objc.objc_msgSend_v_p_p(target, sel, a, b)
    }
    fun msgSendVoid(target: Pointer, sel: Pointer, a: Pointer?, b: Pointer?, c: Pointer?) {
        objc.objc_msgSend_v_p_p_p(target, sel, a, b, c)
    }

    fun msgSendInt(target: Pointer, sel: Pointer): Int = objc.objc_msgSend_i(target, sel)
    fun msgSendLong(target: Pointer, sel: Pointer): Long = objc.objc_msgSend_l(target, sel)
    fun msgSendDouble(target: Pointer, sel: Pointer): Double = objc.objc_msgSend_d(target, sel)
    fun msgSendBool(target: Pointer, sel: Pointer): Boolean = objc.objc_msgSend_b(target, sel) != 0.toByte()

    fun msgSendLong(target: Pointer, sel: Pointer, idx: Long): Pointer =
        objc.objc_msgSend_p_l(target, sel, idx)

    /**
     * `[AVAudioFormat initWithCommonFormat:sampleRate:channels:interleaved:]` shape —
     * `id method(id self, SEL _cmd, NSUInteger commonFmt, double sampleRate,
     *            UInt32 channels, BOOL interleaved)`.
     */
    fun msgSendInitFormat(
        target: Pointer,
        sel: Pointer,
        commonFormat: Long,
        sampleRate: Double,
        channels: Int,
        interleaved: Byte,
    ): Pointer = objc.objc_msgSend_format(target, sel, commonFormat, sampleRate, channels, interleaved)

    /**
     * `[AVAudioPCMBuffer initWithPCMFormat:frameCapacity:]` —
     * `id method(id self, SEL _cmd, id format, UInt32 frameCapacity)`.
     */
    fun msgSendInitBuffer(target: Pointer, sel: Pointer, format: Pointer?, capacity: Int): Pointer =
        objc.objc_msgSend_p_p_i(target, sel, format, capacity)

    /**
     * `[AVAudioEngine startAndReturnError:]` returns BOOL and writes NSError into a
     * `NSError**` out parameter. Encoded as `objc_msgSend_b_pp` (returns Byte).
     */
    fun msgSendStartAndReturnError(target: Pointer, sel: Pointer, errOut: Pointer?): Boolean =
        objc.objc_msgSend_b_pp(target, sel, errOut) != 0.toByte()

    /** `[AVAudioEngine connect:to:format:]` — `void(id, SEL, id, id, id)`. */
    fun msgSendConnect(
        target: Pointer,
        sel: Pointer,
        from: Pointer?,
        to: Pointer?,
        format: Pointer?,
    ) {
        objc.objc_msgSend_v_p_p_p(target, sel, from, to, format)
    }

    /**
     * `[AVAudioInputNode installTapOnBus:bufferSize:format:block:]` —
     * `void(id, SEL, NSUInteger bus, UInt32 bufferSize, id format, id block)`.
     */
    fun msgSendInstallTap(
        target: Pointer,
        sel: Pointer,
        bus: Long,
        bufferSize: Int,
        format: Pointer?,
        block: Pointer?,
    ) {
        objc.objc_msgSend_v_l_i_p_p(target, sel, bus, bufferSize, format, block)
    }

    /**
     * `[AVAudioPlayerNode scheduleBuffer:completionHandler:]` —
     * `void(id, SEL, id buffer, id block)`.
     */
    fun msgSendScheduleBuffer(target: Pointer, sel: Pointer, buffer: Pointer?, block: Pointer?) {
        objc.objc_msgSend_v_p_p(target, sel, buffer, block)
    }

    /** Resolve a Kotlin [Callback] to a C function pointer for use as an IMP / block invoke. */
    fun fnPointer(callback: Callback): Pointer = CallbackReference.getFunctionPointer(callback)

    @Suppress("FunctionNaming", "TooManyFunctions")
    private interface ObjcLibrary : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer

        fun objc_msgSend_p(receiver: Pointer, sel: Pointer): Pointer
        fun objc_msgSend_p_p(receiver: Pointer, sel: Pointer, a: Pointer?): Pointer
        fun objc_msgSend_p_p_p(receiver: Pointer, sel: Pointer, a: Pointer?, b: Pointer?): Pointer
        fun objc_msgSend_p_p_p_p(
            receiver: Pointer,
            sel: Pointer,
            a: Pointer?,
            b: Pointer?,
            c: Pointer?,
        ): Pointer
        fun objc_msgSend_p_p_i(receiver: Pointer, sel: Pointer, a: Pointer?, i: Int): Pointer

        fun objc_msgSend_v(receiver: Pointer, sel: Pointer)
        fun objc_msgSend_v_p(receiver: Pointer, sel: Pointer, a: Pointer?)
        fun objc_msgSend_v_p_p(receiver: Pointer, sel: Pointer, a: Pointer?, b: Pointer?)
        fun objc_msgSend_v_p_p_p(
            receiver: Pointer,
            sel: Pointer,
            a: Pointer?,
            b: Pointer?,
            c: Pointer?,
        )
        fun objc_msgSend_v_l_i_p_p(
            receiver: Pointer,
            sel: Pointer,
            l: Long,
            i: Int,
            a: Pointer?,
            b: Pointer?,
        )

        fun objc_msgSend_i(receiver: Pointer, sel: Pointer): Int
        fun objc_msgSend_l(receiver: Pointer, sel: Pointer): Long
        fun objc_msgSend_d(receiver: Pointer, sel: Pointer): Double
        fun objc_msgSend_b(receiver: Pointer, sel: Pointer): Byte
        fun objc_msgSend_b_pp(receiver: Pointer, sel: Pointer, errOut: Pointer?): Byte
        fun objc_msgSend_p_l(receiver: Pointer, sel: Pointer, l: Long): Pointer

        fun objc_msgSend_format(
            receiver: Pointer,
            sel: Pointer,
            commonFormat: Long,
            sampleRate: Double,
            channels: Int,
            interleaved: Byte,
        ): Pointer
    }

}

/** Suppress unused JNA Structure import (kept for parity with FP-8 ObjcRuntime). */
@Suppress("unused")
private val keepStructureImport: Class<Structure> = Structure::class.java

/**
 * Tiny Foundation helpers — string/error conversion and NSNumber boxed value access.
 * Mirrors `shared/screencast/.../macos/Foundation.kt` (FP-8).
 */
internal object AVAudioFoundation {
    private val selUTF8String by lazy { AVAudioObjcRuntime.sel("UTF8String") }
    private val selLocalizedDescription by lazy { AVAudioObjcRuntime.sel("localizedDescription") }

    /** Convert `NSString*` to JVM [String]. Returns empty if null. */
    fun fromNSString(ns: Pointer?): String {
        if (ns == null || Pointer.nativeValue(ns) == 0L) return ""
        val cstr = AVAudioObjcRuntime.msgSend(ns, selUTF8String)
        if (Pointer.nativeValue(cstr) == 0L) return ""
        return cstr.getString(0, Charsets.UTF_8.name())
    }

    fun errorMessage(err: Pointer?): String {
        if (err == null || Pointer.nativeValue(err) == 0L) return ""
        return fromNSString(AVAudioObjcRuntime.msgSend(err, selLocalizedDescription))
    }
}
