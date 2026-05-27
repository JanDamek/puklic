# Puklic

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Contributor Covenant](https://img.shields.io/badge/Contributor%20Covenant-2.1-4baaaa.svg)](CODE_OF_CONDUCT.md)
[![Status](https://img.shields.io/badge/status-pre--MVP-orange.svg)](docs/07_roadmap/phases.md)

![Puklic](docs/00_overview/puklic-showcase.png)

> A lightweight Kotlin Multiplatform desktop chat client focused on native UI, low memory usage, coroutine-first architecture, and first-class Linux Wayland support.

**Status:** early architecture / pre-MVP. No code yet.

## Name

**Puklic** is derived from the Czech word *puklík* — a small cover / hub cap / trinket. The name deliberately **does not contain** "Discord" (trademark) and leaves room for future support of other protocols. Short, Czech-sounding, technically neutral.

- **App name:** Puklic
- **Package:** `cz.damek.puklic`
- **Repository:** `puklic`

## What it is

A native Discord desktop client without Electron. Kotlin + Compose Multiplatform. Target: < 150 MB RAM idle, < 2 s cold start.

**Shipping targets:** Linux x86_64 desktop (.deb + .AppImage) and macOS arm64
desktop (.dmg), both attached to GitHub Releases with the same version string.
Windows and macOS x86_64 are out of scope. Mobile (Android/iOS) is a future
roadmap phase — KMP scaffolding ready, not yet shipping. See
[CLAUDE.md](CLAUDE.md) §Platforms.

## What it is not

Puklic is **not** a bot, automation, AI agent, or plugin for the official Discord client. See [docs/00_overview/product-vision.md](docs/00_overview/product-vision.md).

## ⚠️ Disclaimer

Discord ToS prohibits third-party user clients. Using Puklic is at your own risk — Discord could theoretically ban your account. Puklic behaves like a real user (no automation, no self-bot features), which minimizes the risk but does not guarantee anything.

**Your account token = full access to everything.** Never share it.

## Documentation

Architecture and the domain model live in [`docs/`](docs/). Start at [docs/README.md](docs/README.md).

## Roadmap

See [docs/07_roadmap/phases.md](docs/07_roadmap/phases.md). Currently before Phase 1 (MVP).

## Installation

Self-contained native installers (bundled JRE 21 + FFmpeg/Opus natives + dbus-java) are
produced from `:desktop:app` via Compose Desktop's `jpackage` integration. Each installer
is ~150 MB and runs without any system dependencies (no `apt install libopus0`, no JRE).

### Arch Linux (AUR) — recommended

```bash
yay -S puklic-bin
# or with paru
paru -S puklic-bin
```

### Linux (Ubuntu / Debian / Mint, x86_64)

```bash
wget https://github.com/JanDamek/puklic/releases/download/v1.0.0/puklic_1.0.0-1_amd64.deb
sudo apt install ./puklic_1.0.0-1_amd64.deb   # pulls libsecret-tools automatically
puklic
```

The `.deb` declares `libsecret-tools` as a runtime dependency. It is required
for secure token storage (the app shells out to `secret-tool` to talk to the
Secret Service API — GNOME Keyring / KWallet). `apt install ./file.deb` resolves
it transitively; plain `dpkg -i` does not — follow it with `sudo apt-get install -f`
if you use `dpkg` directly.

### Linux (other distros, x86_64)

Download `Puklic-1.0.0-x86_64.AppImage`, `chmod +x`, run.

AppImage does not honor `.deb` dependencies, so install `libsecret-tools` (or
your distro's equivalent providing the `secret-tool` binary, e.g. `libsecret`
on Arch) manually before first login.

### macOS (Apple Silicon)

Download `Puklic-1.0.0.dmg` from the latest GitHub Release, double-click, drag
to Applications. Same version string as the Linux build (single source of
truth in `gradle.properties`).

Developers on Apple Silicon can also run from source:

```bash
./gradlew :desktop:app:run
# or build a local .dmg for testing
./gradlew :desktop:app:packageDistributionForCurrentOS
```

### Build installers from source

```bash
# Produces the host platform's installer (.deb + app-image dir on Linux, .dmg on macOS).
# To also produce the final .AppImage on Linux, run the wrapper script below.
./gradlew :desktop:app:packageDistributionForCurrentOS

# Linux only — wrap the jpackage app-image directory into a real .AppImage.
# Requires Linux host (uses appimagetool, x86_64).
./gradlew :desktop:app:createDistributable
PUKLIC_VERSION="$(grep -E '^puklic\.version=' gradle.properties | cut -d= -f2)" \
  APP_IMAGE_DIR="desktop/app/build/compose/binaries/main/app/Puklic" \
  OUT_DIR="desktop/app/build/appimage" \
  ICON_PATH="icons/linux/512x512/puklic.png" \
  bash desktop/app/src/main/appimage/build-appimage.sh
```

Output:
- `.deb` — `desktop/app/build/compose/binaries/main/deb/`
- `.dmg` — `desktop/app/build/compose/binaries/main/dmg/`
- `app/Puklic/` (jpackage app-image runtime tree, used as input to the AppImage wrapper) — `desktop/app/build/compose/binaries/main/app/Puklic/`
- `.AppImage` — `desktop/app/build/appimage/`

Per-OS FFmpeg natives are selected automatically by `detectFfmpegClassifier()` in
`shared/voice/build.gradle.kts` to keep each installer slim (~30 MB of natives instead
of ~150 MB for the umbrella `ffmpeg-platform-gpl` artifact).

## Build

TBD — the Gradle multimodule scaffold is in place (Steps 1–2 landed). Run `./gradlew help` to validate. Application entry points (`:desktop:app`) come online in Phase 1 implementation steps.

### Voice prerequisites (Phase 3)

None — the `:shared:voice` module bundles its native dependencies via JavaCPP. The Opus
encoder/decoder are loaded from the FFmpeg GPL build
(`org.bytedeco:ffmpeg-platform-gpl:7.1-1.5.11`, which ships `libopus` inside). Natives are
extracted to a per-user cache (`$HOME/.javacpp/cache/` by default) on first use; tests use
the project `build/javacpp-cache/` to keep them hermetic. No `brew install opus` / `apt
install libopus0` is needed.

GPL note: because `ffmpeg-platform-gpl` bundles libx264 (GPL-2.0), distributed binaries
that include it must comply with GPL-2.0+. The Puklic source itself stays Apache-2.0; see
`docs/06_ops/licensing.md` (to be added) for the bundle disclosure.

## Contributing

Contributions welcome. Read [`CONTRIBUTING.md`](CONTRIBUTING.md) first, then check the [open issues](https://github.com/JanDamek/puklic/issues) or [discussions](https://github.com/JanDamek/puklic/discussions).

Code of conduct: [Contributor Covenant 2.1](CODE_OF_CONDUCT.md).

## Security

To report a vulnerability, see [`SECURITY.md`](SECURITY.md). **Do not** open a public issue for security topics.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

Puklic is an independent project, **not affiliated with Discord Inc.** "Discord" is a trademark of Discord Inc., used here only descriptively.
