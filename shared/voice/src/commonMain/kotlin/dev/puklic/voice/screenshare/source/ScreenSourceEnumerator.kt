package dev.puklic.voice.screenshare.source

import dev.puklic.voice.screenshare.ScreenSource

/**
 * Platform-specific enumerator for capturable [ScreenSource]s.
 *
 * Per architect report `docs/03_infrastructure/architect-reports/2026-05-23-screenshare.md` §7
 * (Source picker), the JVM (macOS) implementation parses `ffmpeg -f avfoundation -list_devices
 * true` output. Linux (Phase 4.1) will obtain sources from the PipeWire screencast portal.
 */
internal interface ScreenSourceEnumerator {
    suspend fun list(): List<ScreenSource>
}

internal expect fun screenSourceEnumerator(): ScreenSourceEnumerator
