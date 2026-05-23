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

A native multiplatform Discord client without Electron. Kotlin + Compose Multiplatform. Target: < 150 MB RAM idle, < 2 s cold start, one codebase for Linux / macOS / Windows / Android / iOS.

## What it is not

Puklic is **not** a bot, automation, AI agent, or plugin for the official Discord client. See [docs/00_overview/product-vision.md](docs/00_overview/product-vision.md).

## ⚠️ Disclaimer

Discord ToS prohibits third-party user clients. Using Puklic is at your own risk — Discord could theoretically ban your account. Puklic behaves like a real user (no automation, no self-bot features), which minimizes the risk but does not guarantee anything.

**Your account token = full access to everything.** Never share it.

## Documentation

Architecture and the domain model live in [`docs/`](docs/). Start at [docs/README.md](docs/README.md).

## Roadmap

See [docs/07_roadmap/phases.md](docs/07_roadmap/phases.md). Currently before Phase 1 (MVP).

## Build

TBD — the Gradle multimodule scaffold is in place (Steps 1–2 landed). Run `./gradlew help` to validate. Application entry points (`:desktop:app`) come online in Phase 1 implementation steps.

### Voice prerequisites (Phase 3)

The `:shared:voice` module loads system **libopus** at runtime via JNA. Install before building or running voice tests:

| Platform | Command |
|---|---|
| macOS (Homebrew) | `brew install opus` |
| Debian / Ubuntu | `sudo apt install libopus0` |
| Fedora | `sudo dnf install opus` |
| Arch | `sudo pacman -S opus` |
| Windows | `vcpkg install opus`, or ship `opus.dll` alongside the binary |

If libopus is missing, `OpusCodecFactory.createEncoder()` throws `OpusException` with installation instructions.

## Contributing

Contributions welcome. Read [`CONTRIBUTING.md`](CONTRIBUTING.md) first, then check the [open issues](https://github.com/JanDamek/puklic/issues) or [discussions](https://github.com/JanDamek/puklic/discussions).

Code of conduct: [Contributor Covenant 2.1](CODE_OF_CONDUCT.md).

## Security

To report a vulnerability, see [`SECURITY.md`](SECURITY.md). **Do not** open a public issue for security topics.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

Puklic is an independent project, **not affiliated with Discord Inc.** "Discord" is a trademark of Discord Inc., used here only descriptively.
