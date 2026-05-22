# Linux / Wayland

The primary target platform for Puklic.

## Display server strategy

Compose Multiplatform Desktop runs on **Skia + AWT**. AWT on Linux has:
- Production-ready **X11** backend
- **XWayland** fallback for Wayland desktops (Compose runs as an X11 client inside XWayland)
- No native Wayland backend (JetBrains is working on one, not yet production-ready)

**Strategy:**
- **Phase 1–4:** XWayland. Works on GNOME, KDE Plasma, Sway, Hyprland. UX difference from native Wayland is minimal for a chat client.
- **Phase 5+:** Track JetBrains Wayland progress, switch to native when stable.

Known XWayland limitations:
- HiDPI scaling (handled via `-Dsun.java2d.uiScale=2.0` or runtime detection)
- Screenshare is unusable through XWayland (handled via xdg-desktop-portal → PipeWire native, bypassing the display server)
- Drag & drop from native Wayland applications — handled by XWayland clipboard bridge

## Distribution

### AppImage (preferred MVP)

- Bundles JRE + classes + native libs
- `appimagetool` from `appimage/AppImageKit`
- One file, double-click to run, no install required
- Size: ~80 MB (JRE 21 stripped + Compose)

### Flatpak (later)

- Sandbox isolation
- Dependencies via Flathub runtime
- Easier updates via Flathub
- Requires `org.freedesktop.Platform` runtime

### Native packages (later)

- `.deb` (Debian/Ubuntu) — `jpackage --type deb`
- `.rpm` (Fedora) — `jpackage --type rpm`
- Arch AUR — community-maintained PKGBUILD

### Conveyor

Alternative to jpackage — JetBrains-friendly, standalone auto-update mechanism. Evaluation deferred.

## Phase 1 actuals — `:desktop:platform-linux`

Phase 1 implementation deliberately avoids JNA / native FFI. All OS integrations
shell out to standard CLI tools via `ProcessBuilder` (exec form, no shell). Trade-off:
~20–50 ms per call (acceptable for token retrieve at startup; not used in hot paths).

| Interface                | Backend (Phase 1) | Required package |
|---|---|---|
| `SecureStorage`          | `secret-tool` (libsecret CLI) | `libsecret-tools` (Debian/Ubuntu) / `libsecret` (Fedora) |
| `NotificationService`    | `notify-send` (libnotify) | `libnotify-bin` / `libnotify` |
| `PlatformOpen`           | `xdg-open` | `xdg-utils` |
| `PlatformClipboard`      | `wl-copy` / `wl-paste` (Wayland), `xclip` fallback (X11) | `wl-clipboard` / `xclip` |
| `PlatformPaths`          | Pure JVM (`File`), XDG-aware | — |
| `TrayService` / `PlatformPresence` / `PlatformAutoStart` | Phase 1 stubs | Phase 2 |

If a CLI is missing the implementation throws `PlatformUnavailable` with the install hint.

### Manual smoke procedure (Linux dev box)

```kotlin
import dev.puklic.platform.Notification
import dev.puklic.platform.linux.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val s = LinuxSecureStorage(serviceName = "puklic-smoke")
    s.put("test-token", "abc123")
    check(s.get("test-token") == "abc123")
    s.remove("test-token")

    LinuxNotificationService().show(
        Notification("Puklic", "Smoke OK", null, emptyList(), null, false)
    )

    val clip = LinuxPlatformClipboard()
    clip.setText("hello from puklic")
    println(clip.getText())
}
```

Phase 2 will swap shell-out for direct D-Bus + libsecret JNA bindings where the
extra latency or feature gap (notification actions, tray icon, idle detection) matters.

## System dependencies

| Lib | Purpose | Optional? |
|---|---|---|
| `libsecret-1` | Token storage (Secret Service API) | No (fallback file store with passphrase) |
| `libdbus-1` | Notifications, tray | Yes (notifications fail gracefully) |
| `libayatana-appindicator3-1` | System tray (StatusNotifierItem) | Yes (no tray icon if missing) |
| `libpipewire-0.3` | Audio capture (Phase 3) | Yes (no voice without it) |
| `xdg-desktop-portal` | Screenshare (Phase 4) | Yes |
| `xdg-desktop-portal-{gtk,kde,hyprland}` | Portal backend | Yes |

In AppImage we bundle only JNA glue libs; system libs are pulled from the host (build on older distro → wider compatibility).

## Window manager integration

### Notifications

D-Bus `org.freedesktop.Notifications`:
- Sender app ID: `puklic` (for grouping)
- Hint `desktop-entry: puklic` (for icon resolution from the .desktop file)
- Actions support (Reply, Mark as read) — if the capabilities response includes `actions`

### Tray

`StatusNotifierItem` (KDE/GNOME via extension, Cinnamon, Budgie). Fallback to legacy `XEmbed` tray if SNI is unavailable.

Tray icon states:
- Connected — base icon
- Disconnected — orange dot
- Unread mentions — red dot with count
- Error — red triangle

### Desktop integration

`puklic.desktop`:

```ini
[Desktop Entry]
Type=Application
Name=Puklic
GenericName=Chat Client
Comment=Lightweight Discord client
Icon=puklic
Exec=puklic %u
Terminal=false
Categories=Network;Chat;
MimeType=x-scheme-handler/discord;
StartupNotify=true
StartupWMClass=Puklic
```

MIME type `x-scheme-handler/discord` — Puklic registers as the handler for `discord://` URLs (server invites, channel deep links). Optional, can be disabled in Settings.

### Autostart

`~/.config/autostart/puklic.desktop` — a copy of the main .desktop with `Hidden=false`. Toggle via Settings → "Launch on login".

## HiDPI

- Detect via GTK settings (`gsettings get org.gnome.desktop.interface scaling-factor`) or `Xft.dpi`
- Apply via `-Dsun.java2d.uiScale=<factor>` at startup
- Compose Desktop respects `LocalDensity`

## Wayland-specific testing

CI/dev test on:
- GNOME (Mutter) on Fedora / Ubuntu
- KDE Plasma 6 (KWin)
- Sway / Hyprland (wlroots-based)

Manual smoke test checklist (Phase 1):
- [ ] Window resize is smooth
- [ ] HiDPI scale 1.0, 1.5, 2.0
- [ ] Multi-monitor (window placement)
- [ ] Clipboard copy/paste between Puklic and Firefox
- [ ] Notification shows + actions work
- [ ] Tray icon is visible
- [ ] Drag & drop attachment from Nautilus / Dolphin

## Open questions

- **Compose native Wayland:** when to switch? Track [github.com/JetBrains/compose-multiplatform](https://github.com/JetBrains/compose-multiplatform) issues.
- **Tray on GNOME:** requires user extension (AppIndicator and KStatusNotifierItem Support). Document in onboarding for GNOME users.
- **Global hotkeys:** Wayland does not allow them. Workaround: per-DE configuration (`gnome-extension`, KWin shortcut) → Puklic D-Bus method as trigger. Outside MVP.
