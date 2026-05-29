package dev.puklic.desktop.macappstore.bridge

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Helpers for forging Objective-C global blocks (`^{}` literals) that
 * Network.framework, VideoToolbox completion handlers, and ScreenCaptureKit
 * receive as parameters.
 *
 * An Objective-C block is a 5-pointer-sized struct on arm64/x86_64:
 *
 * ```c
 * struct Block_literal_1 {
 *     void* isa;        // _NSConcreteGlobalBlock
 *     int   flags;      // BLOCK_IS_GLOBAL
 *     int   reserved;   // 0
 *     void* invoke;     // C function pointer
 *     void* descriptor; // points at a 3-pointer struct
 * };
 * struct Block_descriptor_1 {
 *     unsigned long reserved;  // 0
 *     unsigned long size;      // sizeof(Block_literal_1)
 *     void*         signature; // NULL when BLOCK_HAS_SIGNATURE is unset
 * };
 * ```
 *
 * The same pattern is used by [`ScreenCaptureKitBridge`][1] in `:shared:screencast`;
 * we cannot reuse that module's `internal` helper from this Apache-2.0 module so
 * the bridge is reproduced here. Each block returned by this helper retains its
 * backing [Keepalive] via the caller — release any reference only after the
 * native side is guaranteed not to invoke it again.
 *
 * [1]: shared/screencast/src/jvmMain/kotlin/dev/puklic/screencast/macos/ScreenCaptureKitBridge.kt
 */
internal object AppleBlock {

    /** `_NSConcreteGlobalBlock` symbol from libSystem (linked via libclosure). */
    private val nsConcreteGlobalBlock: Pointer by lazy {
        NativeLibrary.getInstance("System")
            .getGlobalVariableAddress("_NSConcreteGlobalBlock")
            ?: error("libSystem does not expose _NSConcreteGlobalBlock — incompatible macOS runtime")
    }

    /** Pointer size on arm64/x86_64. */
    private const val POINTER_BYTES: Long = 8
    private const val INT_BYTES: Long = 4

    /** 5 pointer-sized slots (isa, flags+reserved (one 8-byte slot), invoke, descriptor). */
    private const val BLOCK_LITERAL_SIZE: Long = 40

    /** `BLOCK_IS_GLOBAL` only — no signature. */
    private const val BLOCK_FLAGS: Int = 1 shl 28

    /**
     * Forge a global block whose `invoke` slot points at the given [callback].
     *
     * Block ABI requires the [callback] to be a JNA [Callback] whose `invoke`
     * method's parameter list matches the block signature the native side will
     * call with `(block_self, args…)`.
     */
    fun forge(callback: Callback): Keepalive {
        val descriptor = Memory(POINTER_BYTES * 3).apply {
            setLong(0, 0)                         // reserved
            setLong(POINTER_BYTES, BLOCK_LITERAL_SIZE) // size
            setPointer(POINTER_BYTES * 2L, null)  // signature
        }
        val invokePointer: Pointer = CallbackReference.getFunctionPointer(callback)
        val block = Memory(BLOCK_LITERAL_SIZE).apply {
            setPointer(0, nsConcreteGlobalBlock)
            setInt(POINTER_BYTES, BLOCK_FLAGS)
            setInt(POINTER_BYTES + INT_BYTES, 0)
            setPointer(POINTER_BYTES + INT_BYTES * 2L, invokePointer)
            setPointer(POINTER_BYTES + INT_BYTES * 2L + POINTER_BYTES, descriptor)
        }
        return Keepalive(block, descriptor, callback)
    }

    /**
     * Holds the heap memory + Kotlin callback that back a forged block. Callers
     * MUST keep a strong reference to this instance for the entire lifetime
     * during which the native side may invoke the block (typically: until the
     * completion handler has fired, or until the connection / session it was
     * registered on is cancelled).
     */
    data class Keepalive(
        val block: Memory,
        val descriptor: Memory,
        val callback: Callback,
    )
}
