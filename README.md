# Puklic

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Contributor Covenant](https://img.shields.io/badge/Contributor%20Covenant-2.1-4baaaa.svg)](CODE_OF_CONDUCT.md)
[![Status](https://img.shields.io/badge/status-pre--MVP-orange.svg)](docs/07_roadmap/phases.md)

> A lightweight Kotlin Multiplatform desktop chat client focused on native UI, low memory usage, coroutine-first architecture, and first-class Linux Wayland support.

**Status:** early architecture / pre-MVP. Žádný code ještě není.

## Název

**Puklic** je odvozeno od českého slova **puklík** — malý kryt / pukla / drobnost. Pojmenování záměrně **neobsahuje** „Discord" (trademark) a nechává prostor pro budoucí podporu jiných protokolů. Krátké, česky znějící, technicky neutrální.

- **App name:** Puklic
- **Package:** `cz.damek.puklic`
- **Repository:** `puklic`

## Co to je

Nativní multiplatform Discord klient bez Electronu. Kotlin + Compose Multiplatform. Cíl: < 150 MB RAM idle, < 2 s cold start, jeden codebase pro Linux / macOS / Windows / Android / iOS.

## Co to není

Puklic **není** bot, automatizace, AI agent ani plugin do oficiálního Discord klienta. Viz [docs/00_overview/product-vision.md](docs/00_overview/product-vision.md).

## ⚠️ Disclaimer

Discord ToS zakazuje third-party user klienty. Použití Puklic je na vlastní riziko — Discord teoreticky může účet zabanovat. Puklic se chová jako reálný uživatel (neautomatizuje, žádné self-bot funkce), čímž se riziko minimalizuje, ale negarantuje.

**Token tvého účtu = plný přístup ke všemu.** Nikdy ho nesdílej.

## Dokumentace

Architektura a doménový model jsou v [`docs/`](docs/). Začni u [docs/README.md](docs/README.md).

## Roadmap

Viz [docs/07_roadmap/phases.md](docs/07_roadmap/phases.md). Aktuálně před fází 1 (MVP).

## Build

TBD — Gradle multimodule skeleton ještě neexistuje. Bude k dispozici na konci kroku „Gradle multimodule setup" ve fázi 1.

## Contributing

Contributions welcome. Read [`CONTRIBUTING.md`](CONTRIBUTING.md) first, then check the [open issues](https://github.com/JanDamek/puklic/issues) or [discussions](https://github.com/JanDamek/puklic/discussions).

Code of conduct: [Contributor Covenant 2.1](CODE_OF_CONDUCT.md).

## Security

To report a vulnerability, see [`SECURITY.md`](SECURITY.md). **Do not** open a public issue for security topics.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

Puklic is an independent project, **not affiliated with Discord Inc.** "Discord" is a trademark of Discord Inc., used here only descriptively.
