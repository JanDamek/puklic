package dev.puklic.screencast.macos

import com.sun.jna.Callback
import com.sun.jna.Pointer
import java.util.concurrent.ConcurrentHashMap

/**
 * Registers (once per JVM) a custom Objective-C class
 * `PuklicSCDelegate : NSObject` that implements the relevant
 * `SCStreamOutput` and `SCStreamDelegate` methods.
 *
 * Per-stream routing: the registered IMPs look up the [MacScreenCapture]
 * instance keyed by the delegate `self` pointer in [delegates] and forward
 * the call. The class itself stays unique — only the instances are
 * per-stream.
 *
 * IMPs are JNA Callback instances whose function-pointer trampoline is
 * cast to a C function pointer (`IMP`). Apple's runtime calls into the
 * trampoline with the standard `(id self, SEL _cmd, ...)` ABI.
 */
internal object DelegateClass {

    /** Maps delegate `self` pointer (as `Long`) → owning capture. */
    val delegates: ConcurrentHashMap<Long, MacScreenCapture> = ConcurrentHashMap()

    /** Lazily registered Objective-C class pointer. */
    val cls: Pointer by lazy { register() }

    /** Selector strings — exposed so callers know what to addStreamOutput on. */
    const val SEL_DID_OUTPUT_SAMPLE_BUFFER = "stream:didOutputSampleBuffer:ofType:"
    const val SEL_DID_STOP_WITH_ERROR = "stream:didStopWithError:"

    /**
     * Type encodings per `@encode`:
     *   - `v@:@@i` — void method(id, SEL, id, id, int). The `i` slot is
     *     `SCStreamOutputType` (int).
     *   - `v@:@@`  — void method(id, SEL, id, id).
     *
     * On arm64 the sample-buffer method is dispatched with a 4-arg shape
     * because `OutputType` is a small enum (NSInteger fits in int on 64-bit
     * macOS for this enum's value range). The encoding string drives Apple's
     * runtime introspection — we keep it accurate.
     */
    private const val TYPES_DID_OUTPUT = "v@:@@q"
    private const val TYPES_DID_STOP = "v@:@@"

    // Held in fields so JNA does not GC the trampolines while the runtime
    // still has function pointers pointing at them.
    private lateinit var didOutputCallback: DidOutputCallback
    private lateinit var didStopCallback: DidStopCallback

    private fun register(): Pointer {
        val nsObject = ObjcRuntime.getClass("NSObject")
        val newClass = ObjcRuntime.allocateClassPair(nsObject, CLASS_NAME, 0)
        didOutputCallback = DidOutputCallback()
        didStopCallback = DidStopCallback()
        val ok1 = ObjcRuntime.addMethod(
            newClass,
            ObjcRuntime.sel(SEL_DID_OUTPUT_SAMPLE_BUFFER),
            ObjcRuntime.fnPointer(didOutputCallback),
            TYPES_DID_OUTPUT,
        )
        val ok2 = ObjcRuntime.addMethod(
            newClass,
            ObjcRuntime.sel(SEL_DID_STOP_WITH_ERROR),
            ObjcRuntime.fnPointer(didStopCallback),
            TYPES_DID_STOP,
        )
        check(ok1 && ok2) {
            "Failed to register PuklicSCDelegate methods (didOutput=$ok1, didStop=$ok2)"
        }
        ObjcRuntime.registerClassPair(newClass)
        return newClass
    }

    /** Allocate one delegate instance and register routing for [capture]. */
    fun newInstance(capture: MacScreenCapture): Pointer {
        val obj = ObjcRuntime.msgSend(ObjcRuntime.alloc(cls), ObjcRuntime.sel("init"))
        delegates[Pointer.nativeValue(obj)] = capture
        return obj
    }

    /** Drop the routing entry — call from [MacScreenCapture.close]. */
    fun forget(delegate: Pointer) {
        delegates.remove(Pointer.nativeValue(delegate))
    }

    private const val CLASS_NAME = "PuklicSCDelegate"

    /**
     * `stream:didOutputSampleBuffer:ofType:` IMP. JNA's `Callback` interface
     * marshals based on the method signature; we declare `invoke(...)`
     * with the exact `(self, _cmd, stream, sampleBuffer, outputType)` shape.
     */
    private class DidOutputCallback : Callback {
        @Suppress("unused")
        fun invoke(self: Pointer?, cmd: Pointer?, stream: Pointer?, sampleBuffer: Pointer?, outputType: Long) {
            val key = self?.let { Pointer.nativeValue(it) } ?: return
            val capture = delegates[key] ?: return
            if (sampleBuffer == null || Pointer.nativeValue(sampleBuffer) == 0L) return
            when (outputType.toInt()) {
                ScreenCaptureKitBridge.OUTPUT_TYPE_SCREEN ->
                    capture.onScreenSampleBuffer(sampleBuffer)
                ScreenCaptureKitBridge.OUTPUT_TYPE_AUDIO ->
                    capture.onAudioSampleBuffer(sampleBuffer)
                else -> Unit
            }
        }
    }

    private class DidStopCallback : Callback {
        @Suppress("unused")
        fun invoke(self: Pointer?, cmd: Pointer?, stream: Pointer?, error: Pointer?) {
            val key = self?.let { Pointer.nativeValue(it) } ?: return
            val capture = delegates[key] ?: return
            capture.onStreamStopped(Foundation.errorMessage(error))
        }
    }
}
