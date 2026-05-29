package dev.puklic.screencast.macos

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * Minimal Objective-C runtime bindings via JNA for the macOS ScreenCaptureKit
 * bridge (FP-8). Loads `libobjc.A.dylib` and exposes the handful of selectors
 * needed by the bridge — `objc_getClass`, `sel_registerName`,
 * `objc_msgSend` (declared once per argument arity), plus the
 * `objc_allocateClassPair` / `class_addMethod` / `objc_registerClassPair`
 * trio needed to register the `SCStreamOutput` delegate class.
 *
 * The class is internal to the macOS bridge — Linux and Windows code paths
 * never reference it. Lazy initialisation guarantees that on a non-Mac host
 * the bridge classes load without throwing (so the module compiles + tests
 * unrelated to mac never trigger `libobjc` resolution).
 *
 * `objc_msgSend` is a variadic C function and JNA cannot model `(...)` in
 * one declaration — declare a distinct overload per Kotlin call site shape.
 *
 * See architect report
 * `docs/03_infrastructure/architect-reports/2026-05-29-fp8-mac-screencapturekit.md` §3.2.
 */
internal object ObjcRuntime {

    /** `libobjc.A.dylib` — loaded lazily so non-Mac JVMs do not pay for it. */
    private val objc: ObjcLibrary by lazy {
        Native.load("objc.A", ObjcLibrary::class.java)
    }

    /**
     * Resolve the `_NSConcreteGlobalBlock` symbol from `libSystem` (linked from
     * `libclosure`). The pointer becomes the `isa` field of every global block
     * we forge for Objective-C completion handlers (`SCShareableContent`). The
     * symbol exists on every macOS since 10.6 — fail fast if missing.
     */
    val nsConcreteGlobalBlock: Pointer by lazy {
        NativeLibrary.getInstance("System").getGlobalVariableAddress("_NSConcreteGlobalBlock")
            ?: error("libSystem does not expose _NSConcreteGlobalBlock — incompatible macOS runtime")
    }

    fun getClass(name: String): Pointer =
        objc.objc_getClass(name)
            ?: error("Objective-C class '$name' not found — host is not macOS or framework not linked")

    fun sel(name: String): Pointer = objc.sel_registerName(name)

    /** `[(Class)cls alloc]`. */
    fun alloc(cls: Pointer): Pointer = objc.objc_msgSend_p(cls, sel("alloc"))

    fun msgSend(target: Pointer, sel: Pointer): Pointer = objc.objc_msgSend_p(target, sel)

    fun msgSend(target: Pointer, sel: Pointer, a: Pointer?): Pointer =
        objc.objc_msgSend_p_p(target, sel, a)

    fun msgSend(target: Pointer, sel: Pointer, a: Pointer?, b: Pointer?): Pointer =
        objc.objc_msgSend_p_p_p(target, sel, a, b)

    fun msgSend(
        target: Pointer,
        sel: Pointer,
        a: Pointer?,
        b: Pointer?,
        c: Pointer?,
    ): Pointer = objc.objc_msgSend_p_p_p_p(target, sel, a, b, c)

    fun msgSendVoid(target: Pointer, sel: Pointer) {
        objc.objc_msgSend_v(target, sel)
    }

    fun msgSendVoid(target: Pointer, sel: Pointer, a: Pointer?) {
        objc.objc_msgSend_v_p(target, sel, a)
    }

    fun msgSendVoid(target: Pointer, sel: Pointer, a: Pointer?, b: Pointer?) {
        objc.objc_msgSend_v_p_p(target, sel, a, b)
    }

    fun msgSendVoid(target: Pointer, sel: Pointer, a: Pointer?, b: Pointer?, c: Pointer?) {
        objc.objc_msgSend_v_p_p_p(target, sel, a, b, c)
    }

    fun msgSendInt(target: Pointer, sel: Pointer): Int = objc.objc_msgSend_i(target, sel)

    fun msgSendLong(target: Pointer, sel: Pointer): Long = objc.objc_msgSend_l(target, sel)

    fun msgSendLong(target: Pointer, sel: Pointer, idx: Long): Pointer =
        objc.objc_msgSend_p_l(target, sel, idx)

    fun msgSendBool(target: Pointer, sel: Pointer, b: Boolean) {
        objc.objc_msgSend_v_b(target, sel, if (b) 1 else 0)
    }

    fun msgSendIntArg(target: Pointer, sel: Pointer, v: Int) {
        objc.objc_msgSend_v_i(target, sel, v)
    }

    fun msgSendLongArg(target: Pointer, sel: Pointer, v: Long) {
        objc.objc_msgSend_v_l(target, sel, v)
    }

    /** `void method(id, SEL, id, NSInteger, id, NSError**)` — used for `addStreamOutput:type:sampleHandlerQueue:error:`. */
    fun msgSendVoid(
        target: Pointer,
        sel: Pointer,
        a: Pointer?,
        type: Long,
        c: Pointer?,
        d: Pointer?,
    ) {
        objc.objc_msgSend_v_p_l_p_p(target, sel, a, type, c, d)
    }

    fun allocateClassPair(superClass: Pointer, name: String, extraBytes: Long): Pointer =
        objc.objc_allocateClassPair(superClass, name, extraBytes)

    fun registerClassPair(cls: Pointer) {
        objc.objc_registerClassPair(cls)
    }

    /**
     * Register an instance method on a class being constructed via
     * [allocateClassPair]. [imp] is a function pointer (typically obtained from
     * [CallbackReference.getFunctionPointer] on a [Callback] instance). [types]
     * is the Objective-C type encoding string — `"v@:@@@"` for
     * `void f(id self, SEL _cmd, id, id, id)`, etc.
     */
    fun addMethod(cls: Pointer, sel: Pointer, imp: Pointer, types: String): Boolean =
        objc.class_addMethod(cls, sel, imp, types)

    fun classGetName(cls: Pointer): String = objc.class_getName(cls)

    /**
     * Resolve a Kotlin [Callback] into a C function pointer (`Pointer`) so it
     * can be installed as an Objective-C method IMP. JNA hands back a
     * trampoline that marshals between Kotlin and the native ABI.
     */
    fun fnPointer(callback: Callback): Pointer = CallbackReference.getFunctionPointer(callback)

    /**
     * JNA Library interface for libobjc + libSystem. Internal because each
     * overload is keyed to a Kotlin call site; callers outside [ObjcRuntime]
     * should use the wrapper methods above.
     */
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

        fun objc_msgSend_i(receiver: Pointer, sel: Pointer): Int
        fun objc_msgSend_l(receiver: Pointer, sel: Pointer): Long
        fun objc_msgSend_p_l(receiver: Pointer, sel: Pointer, l: Long): Pointer
        fun objc_msgSend_v_b(receiver: Pointer, sel: Pointer, b: Byte)
        fun objc_msgSend_v_i(receiver: Pointer, sel: Pointer, v: Int)
        fun objc_msgSend_v_l(receiver: Pointer, sel: Pointer, v: Long)
        fun objc_msgSend_v_p_l_p_p(
            receiver: Pointer,
            sel: Pointer,
            a: Pointer?,
            l: Long,
            c: Pointer?,
            d: Pointer?,
        )

        fun objc_allocateClassPair(superCls: Pointer, name: String, extraBytes: Long): Pointer
        fun objc_registerClassPair(cls: Pointer)
        fun class_addMethod(cls: Pointer, sel: Pointer, imp: Pointer, types: String): Boolean
        fun class_getName(cls: Pointer): String
    }

    /**
     * Objective-C `_NSConcreteGlobalBlock` block literal struct. Layout
     * matches Apple's published Blocks ABI (libclosure):
     *
     * ```c
     * struct Block_literal_1 {
     *     void* isa;
     *     int   flags;
     *     int   reserved;
     *     void* invoke;
     *     struct Block_descriptor_1* descriptor;
     * };
     * ```
     */
    @Structure.FieldOrder("isa", "flags", "reserved", "invoke", "descriptor")
    @Suppress("LongParameterList")
    open class BlockLiteral : Structure() {
        @JvmField var isa: Pointer? = null
        @JvmField var flags: Int = 0
        @JvmField var reserved: Int = 0
        @JvmField var invoke: Pointer? = null
        @JvmField var descriptor: Pointer? = null
    }

    @Structure.FieldOrder("reserved", "size", "signature")
    open class BlockDescriptor : Structure() {
        @JvmField var reserved: Long = 0
        @JvmField var size: Long = 0
        @JvmField var signature: Pointer? = null
    }

    /** `BLOCK_HAS_SIGNATURE | BLOCK_IS_GLOBAL` — required for runtime introspection. */
    const val BLOCK_FLAGS: Int = (1 shl 28) or (1 shl 30)
}
