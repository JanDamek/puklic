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

## Screencast via xdg-desktop-portal (Phase 3 of self-contained refactor)

Puklic captures the desktop on Wayland by talking to the compositor's `org.freedesktop.portal.ScreenCast` interface over the session D-Bus, then handing the resulting PipeWire fd to libavdevice's `pipewire` demuxer (bundled in the JavaCPP FFmpeg GPL build, no separate install).

Implementation entry points:
- `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/screenshare/source/LinuxScreenSourceEnumerator.jvm.kt` — returns a single synthetic "portal" entry (the compositor's own picker handles real selection)
- `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/screenshare/linux/LinuxPortalScreenCast.kt` — drives `CreateSession → SelectSources → Start → OpenPipeWireRemote` via `dbus-java 5.1.1`
- `LibavVideoEncoder` accepts an optional `pipewireFd: Int` parameter; when set it forwards `av_dict_set("fd", "<int>", 0)` so libavdevice uses the portal-allocated PipeWire endpoint
- `DefaultScreenShareClient` detects `source.id == "portal"`, runs the handshake, and substitutes the real PipeWire node id into the encoder's `ScreenSource`

D-Bus runtime dependencies (already on every modern Wayland desktop):
- `xdg-desktop-portal` (≥ 1.16 recommended)
- Compositor-specific backend: `xdg-desktop-portal-gnome`, `xdg-desktop-portal-kde`, `xdg-desktop-portal-hyprland`, or `xdg-desktop-portal-wlr` for Sway
- `pipewire` ≥ 0.3 daemon (used by both the portal and libavdevice)

### Manual smoke test (cannot run in CI — no session bus, no portal)

1. Boot a GNOME or KDE Wayland session.
2. Launch Puklic, sign in, join a voice channel.
3. Click "Share Screen". The compositor's picker (e.g. GNOME's "Share your screen") must pop up.
4. Pick a monitor and confirm. The screencast indicator (red dot / "Cast" badge in the system tray) should appear.
5. On a second machine, open the official Discord client and join the same voice channel. The shared screen must render with sub-second latency and recognisable contents (no green/purple corruption — that would indicate the YUV pixel format negotiation between PipeWire and libavdevice failed).
6. Click "Stop Sharing" in Puklic. The compositor indicator must disappear within ~1 second.

### Known caveats

- The portal flow is currently smoke-tested only; CI runs only the small reflection-based unit tests in `LinuxPortalScreenCastTest`.
- dbus-java's deserialisation of `a(ua{sv})` (the `streams` field on Start's Response) is shape-dependent; `LinuxPortalScreenCast.extractAllStreams` handles both `Object[]` and `List<*>` rows. If a future dbus-java release switches to a typed `DBusStruct` shape, extend that helper.
- No restore-token support yet; every screencast triggers the compositor picker. Adding `restore_token`/`persist_mode` (portal v4+) is a follow-up.

### Audio sub-stream parsing (Phase 4.1 prerequisite)

`SelectSources(audio=true)` is a best-effort request — the compositor may emit a second
PipeWire node id for system audio alongside the video node, embed nothing, or silently
ignore the request. `LinuxPortalScreenCast.extractAllStreams` parses every row of the
portal's `streams: a(ua{sv})` payload into a typed `PortalStream` with `kind = Video |
Audio`. Heuristic: presence of the `size: (ii)` property in the per-stream property dict
marks a stream as video; absence marks it audio. Helpers `streams.videoNodes()` and
`streams.audioNodes()` expose the two sub-lists.

Compositor support matrix (verified May 2026):

| Compositor               | Audio sub-stream emitted?                         |
|--------------------------|---------------------------------------------------|
| GNOME Mutter ≥ 45        | Yes — separate PipeWire node id                    |
| KDE KWin ≥ 6.0           | Partial — gated by user's PipeWire setup           |
| wlroots (Sway, Hyprland) | No — `audio` flag silently ignored                 |

Encoder wiring for the audio node (Opus encode + transmit) is tracked in issue #25
prerequisite (1) "SSRC / mixing model". Until that lands, an audio node returned by the
portal is parsed and exposed via `PipeWireStream.firstAudioNodeId` but not consumed.

## Open questions

- **Compose native Wayland:** when to switch? Track [github.com/JetBrains/compose-multiplatform](https://github.com/JetBrains/compose-multiplatform) issues.
- **Tray on GNOME:** requires user extension (AppIndicator and KStatusNotifierItem Support). Document in onboarding for GNOME users.
- **Global hotkeys:** Wayland does not allow them. Workaround: per-DE configuration (`gnome-extension`, KWin shortcut) → Puklic D-Bus method as trigger. Outside MVP.
