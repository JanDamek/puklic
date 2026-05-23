package dev.puklic.voice.screenshare.source

import dev.puklic.voice.screenshare.ScreenSource

/**
 * Linux Wayland enumerator. Unlike macOS, we cannot enumerate monitors ahead of time:
 * xdg-desktop-portal explicitly does NOT expose a window/monitor list (privacy by design).
 * The compositor shows its own picker when `org.freedesktop.portal.ScreenCast.Start` runs.
 *
 * Hence this enumerator returns a single synthetic entry; selecting it triggers the portal
 * picker at capture-start time. The picker's chosen PipeWire node id is then surfaced into
 * [LibavVideoEncoder] via the `pipewireFd` + node id from
 * [dev.puklic.voice.screenshare.linux.LinuxPortalScreenCast].
 *
 * See `docs/03_infrastructure/architect-reports/2026-05-23-self-contained-linux.md` §4 phase 3
 * and `docs/05_platforms/linux-wayland.md`.
 */
internal class LinuxScreenSourceEnumerator : ScreenSourceEnumerator {

    override suspend fun list(): List<ScreenSource> = listOf(
        ScreenSource.Monitor(
            id = PORTAL_PICKER_ID,
            displayName = "System picker (xdg-desktop-portal)",
            widthPx = UNKNOWN_DIMENSION,
            heightPx = UNKNOWN_DIMENSION,
        ),
    )

    internal companion object {
        /** Sentinel id recognised by [dev.puklic.voice.screenshare.DefaultScreenShareClient]. */
        const val PORTAL_PICKER_ID = "portal"
        private const val UNKNOWN_DIMENSION = 0
    }
}
