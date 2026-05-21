# Linux / Wayland

Primární cílová platforma Puklic.

## Display server strategie

Compose Multiplatform Desktop běží na **Skia + AWT**. AWT na Linuxu má:
- Production-ready **X11** backend
- **XWayland** fallback pro Wayland desktops (Compose běží jako X11 client uvnitř XWaylandu)
- Žádný nativní Wayland backend (JetBrains na něm pracuje, není production)

**Strategie:**
- **Fáze 1–4:** XWayland. Funguje na GNOME, KDE Plasma, Sway, Hyprland. UX rozdíl proti native Wayland minimální pro chat klient.
- **Fáze 5+:** Sledovat JetBrains Wayland progress, switch on native když bude stable.

Známé XWayland nedostatky:
- HiDPI scaling (řeší se `-Dsun.java2d.uiScale=2.0` nebo runtime detekcí)
- Screenshare nepoužitelný přes XWayland (řeší se přes xdg-desktop-portal → PipeWire native, bypassuje display server)
- Drag & drop z native Wayland aplikací — handled by XWayland clipboard bridge

## Distribuce

### AppImage (preferred MVP)

- Bundluje JRE + classes + native libs
- `appimagetool` z `appimage/AppImageKit`
- One file, double-click run, no install
- Velikost: ~80 MB (JRE 21 stripped + Compose)

### Flatpak (později)

- Sandbox isolation
- Závislosti přes Flathub runtime
- Snazší update přes Flathub
- Vyžaduje `org.freedesktop.Platform` runtime

### Native packages (later)

- `.deb` (Debian/Ubuntu) — `jpackage --type deb`
- `.rpm` (Fedora) — `jpackage --type rpm`
- Arch AUR — community-maintained PKGBUILD

### Conveyor

Alternativa k jpackage — JetBrains-friendly, samostatný auto-update mechanismus. Hodnocení později.

## Závislosti systému

| Lib | Účel | Optional? |
|---|---|---|
| `libsecret-1` | Token storage (Secret Service API) | No (fallback file store s passphrase) |
| `libdbus-1` | Notifications, tray | Yes (notifikace fail gracefully) |
| `libayatana-appindicator3-1` | System tray (StatusNotifierItem) | Yes (no tray icon if missing) |
| `libpipewire-0.3` | Audio capture (fáze 3) | Yes (no voice without it) |
| `xdg-desktop-portal` | Screenshare (fáze 4) | Yes |
| `xdg-desktop-portal-{gtk,kde,hyprland}` | Portal backend | Yes |

V AppImage bundlujeme jen JNA glue libs, system libs taháme z hosta (varianta build na starší distro → wider compat).

## Window manager integrace

### Notifications

D-Bus `org.freedesktop.Notifications`:
- Sender app ID: `puklic` (pro grouping)
- Hint `desktop-entry: puklic` (pro icon resolution z .desktop file)
- Actions support (Reply, Mark as read) — pokud capabilities response obsahuje `actions`

### Tray

`StatusNotifierItem` (KDE/GNOME via extension, Cinnamon, Budgie). Fallback na legacy `XEmbed` tray pokud SNI nedostupný.

Tray ikona stavy:
- Connected — base icon
- Disconnected — orange dot
- Unread mentions — red dot s číslem
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

Mime type `x-scheme-handler/discord` — Puklic zaregistruje jako handler pro `discord://` URLs (server invites, channel deep links). Optional, lze vypnout v Settings.

### Autostart

`~/.config/autostart/puklic.desktop` — kopie hlavního .desktop s `Hidden=false`. Toggle přes Settings → „Spustit po přihlášení".

## HiDPI

- Detect přes GTK settings (`gsettings get org.gnome.desktop.interface scaling-factor`) nebo `Xft.dpi`
- Apply via `-Dsun.java2d.uiScale=<factor>` při spuštění
- Compose Desktop respektuje `LocalDensity`

## Wayland-specific testing

CI/dev test na:
- GNOME (Mutter) na Fedora / Ubuntu
- KDE Plasma 6 (KWin)
- Sway / Hyprland (wlroots-based)

Manual smoke test checklist (fáze 1):
- [ ] Window resize hladký
- [ ] HiDPI scale 1.0, 1.5, 2.0
- [ ] Multi-monitor (window placement)
- [ ] Clipboard copy/paste mezi Puklic a Firefox
- [ ] Notifikace zobrazí + akce
- [ ] Tray icon viditelný
- [ ] Drag & drop attachmentu z Nautilus / Dolphin

## Open questions

- **Compose nativní Wayland:** kdy switch? Sledovat [github.com/JetBrains/compose-multiplatform](https://github.com/JetBrains/compose-multiplatform) issues.
- **Tray na GNOME:** vyžaduje user extension (AppIndicator and KStatusNotifierItem Support). Zdokumentovat v onboarding pro GNOME users.
- **Global hotkeys:** Wayland nepovoluje. Workaround: per-DE konfigurace (`gnome-extension`, KWin shortcut) → Puklic D-Bus method na trigger. Mimo MVP.
