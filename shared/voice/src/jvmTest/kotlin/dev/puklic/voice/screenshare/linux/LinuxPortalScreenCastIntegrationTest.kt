package dev.puklic.voice.screenshare.linux

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Integration test that exercises the real xdg-desktop-portal D-Bus handshake. Gated to
 * Linux + a live session bus. On every other environment (CI on Linux without DBus,
 * macOS/Windows dev boxes) the test self-skips by returning early — JUnit reports it as
 * passed which is acceptable for a manual-only check.
 *
 * To run interactively on a real Linux desktop:
 *   ./gradlew :shared:voice:jvmTest --tests \
 *     dev.puklic.voice.screenshare.linux.LinuxPortalScreenCastIntegrationTest
 *
 * The compositor will pop up its source picker; either choose a monitor (expect Ok) or close
 * the dialog (expect UserCancelled). Errors should surface as PortalResult.Error.
 *
 * Documented in `docs/05_platforms/linux-wayland.md`.
 */
class LinuxPortalScreenCastIntegrationTest {

    @Test
    fun `portal handshake returns a terminal PortalResult on real session`() {
        if (!isLinuxWithSessionBus()) return // gated — self-skip on CI / non-Linux
        runBlocking {
            LinuxPortalScreenCast().use { portal ->
                val result = portal.open(
                    captureMode = LinuxPortalScreenCast.CaptureMode.MonitorsAndWindows,
                    cursorMode = LinuxPortalScreenCast.CursorMode.Hidden,
                    overallTimeoutMs = INTEGRATION_TIMEOUT_MS,
                )
                assertTrue(
                    result is LinuxPortalScreenCast.PortalResult.Ok ||
                        result is LinuxPortalScreenCast.PortalResult.UserCancelled ||
                        result is LinuxPortalScreenCast.PortalResult.Error,
                    "Unexpected result type: $result",
                )
            }
        }
    }

    private companion object {
        const val INTEGRATION_TIMEOUT_MS = 120_000L

        fun isLinuxWithSessionBus(): Boolean {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            return os.contains("linux") && !System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()
        }
    }
}
