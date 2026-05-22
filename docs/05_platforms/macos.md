# macOS

Secondary desktop target. Used for MVP development and smoke testing — the deployment
target is Linux Wayland, but the lead developer works on macOS.

## Display server strategy

Compose Multiplatform Desktop on macOS is native (Skia + AppKit). No special handling
required. HiDPI is automatic via the system scaling factor.

## Distribution

### `.dmg` / `.app` (preferred)

- Bundled JRE via `jpackage --type dmg`
- Signed with Developer ID (Apple) for Gatekeeper
- Notarized via `notarytool`
- Size ~95 MB (JRE 21 + Compose + JavaFX-free build)

### Homebrew Cask (later)

- Tap: `puklic/tap` or `homebrew/cask`
- Auto-update via cask version checks

## Phase 1 actuals — `:desktop:platform-macos`

Phase 1 implementation deliberately avoids JNA / native FFI. All OS integrations
shell out to standard macOS CLI tools via `ProcessBuilder` (exec form, no shell).
Trade-off: ~20–50 ms per call (acceptable for token retrieve at startup; not used
in hot paths). Phase 2 swaps to JNA + native frameworks where actions / cancellation /
idle detection matter.

| Interface                | Backend (Phase 1) | macOS framework (Phase 2) |
|---|---|---|
| `SecureStorage`          | `/usr/bin/security` (Keychain CLI) | SecItemAdd / SecItemCopyMatching via JNA |
| `NotificationService`    | `osascript -e 'display notification ...'` | UNUserNotificationCenter |
| `PlatformOpen`           | `/usr/bin/open` (incl. `-R` reveal-in-Finder) | NSWorkspace.openURL |
| `PlatformClipboard`      | `/usr/bin/pbcopy` / `/usr/bin/pbpaste` for text; AWT `Toolkit.systemClipboard` for images | NSPasteboard direct |
| `PlatformPaths`          | Pure JVM (`File`), `~/Library/...` conventions | — |
| `TrayService` / `PlatformPresence` / `PlatformAutoStart` | Phase 1 stubs | NSStatusItem / IOKit IdleTime / LaunchAgent plist |

### Paths

Following Apple's File System Programming Guide:

| Logical | Path |
|---|---|
| `dataDir` | `~/Library/Application Support/Puklic` |
| `cacheDir` | `~/Library/Caches/Puklic` |
| `configDir` | `~/Library/Preferences/Puklic` |
| `crashDir` | `~/Library/Application Support/Puklic/crashes` |
| `databaseFile()` | `~/Library/Application Support/Puklic/puklic.db` |

Directories are created lazily on first access.

### Keychain notes

`security add-generic-password -U -s puklic-client -a <key> -w <value>` writes to the
login keychain. The `-U` flag updates an existing item rather than failing. **Caveat:**
the `-w <value>` form briefly exposes the secret in the argv visible to `ps` for the
current user — acceptable for MVP, Phase 2 SecItemAdd via JNA closes this gap.

First call may pop a Keychain consent dialog ("`Puklic` wants to access ..."). After
"Always Allow" the dialog stops.

### Notifications

`osascript` notifications carry **no actions, no images, and no markup** in Phase 1.
The capability matrix advertises `actions=false, images=false, markup=false` so the UI
falls back to plain text. Phase 2 (UNUserNotificationCenter) adds reply/mark-as-read.

### Manual smoke procedure

```kotlin
import dev.puklic.platform.Notification
import dev.puklic.platform.macos.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val s = MacOsSecureStorage(serviceName = "puklic-smoke")
    s.put("test-token", "abc123")
    check(s.get("test-token") == "abc123")
    s.remove("test-token")

    MacOsNotificationService().show(
        Notification("Puklic", "Smoke OK", null, emptyList(), null, false)
    )

    val clip = MacOsPlatformClipboard()
    clip.setText("hello from puklic")
    println(clip.getText())
}
```

Run via `./gradlew :desktop:platform-macos:test` — the Keychain + pbcopy roundtrip tests
exercise the real CLIs.

## Open questions

- **Notarization automation:** wire `notarytool` into the release pipeline (Phase 5).
- **Apple Silicon vs Intel:** ship a universal2 JRE bundle, or two artifacts? Default
  to universal2 for simpler distribution.
- **Sandboxing:** App Store distribution requires sandboxing — deferred; direct DMG
  distribution does not.
