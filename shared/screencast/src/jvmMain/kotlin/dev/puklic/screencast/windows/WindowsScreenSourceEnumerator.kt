package dev.puklic.screencast.windows

import com.sun.jna.Pointer
import dev.puklic.screencast.ScreenSourceEnumerator
import dev.puklic.voice.screenshare.ScreenSource

/**
 * Enumerates capturable monitors via the DXGI 1.2 adapter / output graph. Each
 * `IDXGIAdapter1::EnumOutputs` entry that maps to an attached desktop monitor is emitted
 * as one [ScreenSource.Monitor]. The `id` field encodes `adapterIndex:outputIndex` so the
 * matching [WindowsScreenCapture] can re-acquire the right output without re-running the
 * picker.
 *
 * Top-level windows are intentionally NOT enumerated here — DXGI's Output Duplication
 * captures full monitors only. Window-level capture on Windows requires
 * `WindowsGraphicsCapture` (UWP `GraphicsCaptureItem`), which is a separate
 * Windows-10-1903+ API surface and not in scope for FP-9.
 *
 * Returning an empty list signals "no displays attached" — caller surfaces as a hard
 * failure per the [ScreenSourceEnumerator] kdoc.
 */
public class WindowsScreenSourceEnumerator : ScreenSourceEnumerator {

    override suspend fun list(): List<ScreenSource> {
        val factory = WindowsDxgiBridge.createFactory1()
        val out = mutableListOf<ScreenSource>()
        try {
            var adapterIndex = 0
            while (true) {
                val adapter: Pointer = WindowsDxgiBridge.enumAdapters1(factory, adapterIndex) ?: break
                try {
                    var outputIndex = 0
                    while (true) {
                        val output: Pointer = WindowsDxgiBridge.enumOutputs(adapter, outputIndex) ?: break
                        try {
                            val desc = WindowsDxgiBridge.getOutputDesc(output)
                            if (desc.widthPx > 0 && desc.heightPx > 0) {
                                out += ScreenSource.Monitor(
                                    id = "$adapterIndex:$outputIndex",
                                    displayName = desc.deviceName.ifBlank { "Display $adapterIndex:$outputIndex" },
                                    widthPx = desc.widthPx,
                                    heightPx = desc.heightPx,
                                )
                            }
                        } finally {
                            WindowsDxgiBridge.release(output)
                        }
                        outputIndex++
                    }
                } finally {
                    WindowsDxgiBridge.release(adapter)
                }
                adapterIndex++
            }
        } finally {
            WindowsDxgiBridge.release(factory)
        }
        return out
    }
}
