# Licensing

Puklic ships under a **dual-licensed** model: the source code is Apache-2.0,
but the distributed binary installers are **GPL-3.0-or-later** because they
bundle (a) the GPL build of FFmpeg for voice / screenshare encoding and
(b) Wire's `core-crypto-jvm` MLS implementation for DAVE end-to-end voice
encryption. The GPL-3.0 bump (from the prior GPL-2.0 baseline) is recorded
in ADR-0007 (`docs/01_architecture/adr/0007-dave-licensing.md`).

## Source code — Apache-2.0

Everything under this repository (Kotlin/Compose code, Gradle build scripts,
docs, icons authored for the project) is licensed under the Apache License,
Version 2.0 — see `LICENSE`.

If you only want the Apache-2.0 parts (e.g. to fork the UI without the
media stack), it suffices to:

- check out the repo
- swap the `ffmpeg-platform-gpl` dependency in `desktop/app/build.gradle.kts`
  for the LGPL build `ffmpeg-platform` (audio-only — H.264/H.265 encode will
  not be available)
- rebuild with `./gradlew :desktop:app:packageDistributionForCurrentOS`

That binary is then Apache-2.0 + LGPL (FFmpeg LGPL build).

## Distributed binary — GPL-3.0-or-later

The default installers built by CI (`.deb`, `.AppImage`, `.dmg`, `.msi`)
bundle two GPL components:

1. `org.bytedeco:ffmpeg-platform-gpl:7.1-1.5.11`:
   - libx264 — GPL-2.0-or-later
   - libx265 — GPL-2.0-or-later
   - libopus — BSD-3-Clause
   - libav* (FFmpeg core) — LGPL-2.1-or-later
2. `com.wire:core-crypto-jvm:4.2.0` (since 2026-05-23, Phase 3.1b):
   - MLS (RFC 9420) implementation backing DAVE E2EE voice
   - GPL-3.0-or-later
   - Brings ~16 MB of Rust JNI natives (`core-crypto-uniffi-jvm`)
     across linux-x64, macOS-arm64/x64, win-x64. No iOS artifact.

Per the GPL "license of the whole" rule, the combined binary is
distributable only under **GPL-3.0-or-later** (the higher of the two
bundled GPL versions). Recipients of the installer have the right to
receive the corresponding source code; this is satisfied by linking back to
Puklic source on GitHub plus upstream FFmpeg/JavaCPP/core-crypto sources
(see `LICENSE-third-party.txt`).

## Why bundle the GPL build?

H.264 and H.265 encoding are required for Discord screenshare interop.
Using the system FFmpeg is not an option (CLAUDE.md mandates a fully
self-contained binary). The LGPL FFmpeg build does not include `libx264`
or `libx265`. Therefore the GPL build is the only viable choice.

## Implications for contributors and forks

Anyone modifying and redistributing the **binary** installer must comply
with GPL-3.0-or-later: ship source, preserve copyright notices, do not add
"further restrictions", and (compared to GPL-2.0) honour GPL-3.0's
anti-tivoization and patent-grant clauses. Modifying the **source code** is
unaffected — pull requests, forks, and source-only redistributions remain
pure Apache-2.0.

## Files of record

- `LICENSE` — Apache-2.0 (project source)
- `LICENSE-third-party.txt` — full list of bundled third-party components
- `docs/06_ops/licensing.md` — this file (explanation)
- Release notes — every GitHub Release reiterates the GPL bundling note
