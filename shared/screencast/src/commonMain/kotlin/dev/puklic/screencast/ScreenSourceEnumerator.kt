package dev.puklic.screencast

import dev.puklic.voice.screenshare.ScreenSource

/**
 * KMP-wide public contract for enumerating capturable [ScreenSource]s.
 *
 * Implementations:
 *   - Linux JVM — `LinuxScreenSourceEnumerator` returns one synthetic
 *     `Monitor` entry; the actual source picking happens in the portal
 *     `Start` dialog (the user picks monitor / window from the system UI).
 *   - macOS JVM — enumerates `SCContentFilter.shareableContent` (FP-8).
 *   - Windows JVM — enumerates `IDXGIOutput` and visible top-level windows
 *     (FP-9).
 *   - iOS iosMain — single synthetic "device screen" entry; ReplayKit's
 *     `RPSystemBroadcastPickerView` performs the real selection out-of-process
 *     (FP-12).
 *
 * Returning an empty list signals "no sources available" — callers MUST handle
 * that as a hard failure (no fallback). Throwing is reserved for unexpected
 * I/O errors.
 */
public interface ScreenSourceEnumerator {
    public suspend fun list(): List<ScreenSource>
}
