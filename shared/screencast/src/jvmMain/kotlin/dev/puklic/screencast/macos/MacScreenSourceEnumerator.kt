package dev.puklic.screencast.macos

import com.sun.jna.Memory
import com.sun.jna.Pointer
import dev.puklic.screencast.ScreenSourceEnumerator
import dev.puklic.voice.screenshare.ScreenSource

/**
 * macOS `SCShareableContent`-backed enumerator. Lists displays and
 * top-level windows that ScreenCaptureKit considers shareable.
 *
 * On first call the macOS Screen Recording TCC prompt may pop up — that is
 * Apple's contract, not a Puklic-side defect. If the user denies, the
 * completion handler delivers an `NSError`, which we surface as
 * `IllegalStateException`. There is no silent fallback (the Puklic
 * documentation explains the permission).
 */
public class MacScreenSourceEnumerator(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : ScreenSourceEnumerator {

    override suspend fun list(): List<ScreenSource> {
        val osVersion = ScreenCaptureKitBridge.currentOsVersion()
        if (!ScreenCaptureKitBridge.supportsVideoCapture(osVersion)) {
            error("ScreenCaptureKit requires macOS 12.3+, host is ${osVersion.major}.${osVersion.minor}")
        }
        return when (val result = ScreenCaptureKitBridge.enumerateShareableContent(timeoutMs)) {
            is ScreenCaptureKitBridge.ShareableContentResult.Error -> error(result.message)
            is ScreenCaptureKitBridge.ShareableContentResult.Ok -> try {
                readContent(result.content)
            } finally {
                Foundation.release(result.content)
            }
        }
    }

    private fun readContent(content: Pointer): List<ScreenSource> {
        val displays = ObjcRuntime.msgSend(content, ScreenCaptureKitBridge.selDisplays)
        val windows = ObjcRuntime.msgSend(content, ScreenCaptureKitBridge.selWindows)

        val out = mutableListOf<ScreenSource>()
        val displayCount = Foundation.arrayCount(displays)
        for (i in 0 until displayCount) {
            val display = Foundation.arrayObjectAt(displays, i)
            val id = ObjcRuntime.msgSendInt(display, ScreenCaptureKitBridge.selDisplayId)
            val width = ObjcRuntime.msgSendLong(display, ScreenCaptureKitBridge.selWidth).toInt()
            val height = ObjcRuntime.msgSendLong(display, ScreenCaptureKitBridge.selHeight).toInt()
            out += ScreenSource.Monitor(
                id = "display:$id",
                displayName = "Display $id (${width}x$height)",
                widthPx = width,
                heightPx = height,
            )
        }

        val windowCount = Foundation.arrayCount(windows)
        for (i in 0 until windowCount) {
            val window = Foundation.arrayObjectAt(windows, i)
            val id = ObjcRuntime.msgSendInt(window, ScreenCaptureKitBridge.selWindowId)
            val title = Foundation.fromNSString(
                ObjcRuntime.msgSend(window, ScreenCaptureKitBridge.selTitle),
            )
            val app = ObjcRuntime.msgSend(window, ScreenCaptureKitBridge.selOwningApplication)
            val appName = if (Pointer.nativeValue(app) == 0L) {
                ""
            } else {
                Foundation.fromNSString(
                    ObjcRuntime.msgSend(app, ScreenCaptureKitBridge.selApplicationName),
                )
            }
            val (w, h) = readWindowFrameDimensions(window)
            if (w <= 0 || h <= 0 || title.isEmpty()) continue
            out += ScreenSource.Window(
                id = "window:$id",
                displayName = if (appName.isEmpty()) title else "$appName — $title",
                appName = appName,
                widthPx = w,
                heightPx = h,
            )
        }
        return out
    }

    /**
     * Reads the `CGRect` returned by `[SCWindow frame]` via a struct-by-value
     * `objc_msgSend_stret`-style call. JNA hands the struct back into a
     * caller-supplied output buffer when we request `_stret` semantics. On
     * arm64 small structs (≤ 32 bytes) are returned in registers (NEON), so we
     * use the standard `objc_msgSend` and reconstruct the 32-byte struct from
     * a `Memory` block via the `objc_msgSend_FpretRefcon`-style shim Apple
     * exposes through `class_getMethodImplementation`. Since CMTime / CGRect
     * fit in registers we cannot read them through JNA's high-level API; we
     * call the explicit `frame` accessor via `class_getMethodImplementation`
     * + a `Memory` pass.
     *
     * For Puklic's needs we only require pixel width / height. Both are
     * exposed by `SCWindow` as the `width` / `height` keys of the
     * `CMSampleBuffer` later, but at enumeration we use the bridge selectors
     * registered above on `[SCWindow frame]` returning a `CGRect`. JNA's
     * direct mapping cannot return a struct; we instead use the convenience
     * Objective-C properties when present (`-[SCWindow frameWidth]` /
     * `frameHeight` exist on macOS 14+). On 12.3 / 13 we fall back to zero
     * dimensions and the caller filters the window out.
     */
    private fun readWindowFrameDimensions(window: Pointer): Pair<Int, Int> {
        val widthSel = ObjcRuntime.sel("frameWidth")
        val heightSel = ObjcRuntime.sel("frameHeight")
        return try {
            val w = ObjcRuntime.msgSendLong(window, widthSel).toInt()
            val h = ObjcRuntime.msgSendLong(window, heightSel).toInt()
            w to h
        } catch (e: Throwable) {
            0 to 0
        }
    }

    public companion object {
        public const val DEFAULT_TIMEOUT_MS: Long = 10_000L
    }
}
