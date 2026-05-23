# Licensing

Puklic ships under a **dual-licensed** model: the source code is Apache-2.0,
but the distributed binary installers are GPL-2.0-or-later because of the
FFmpeg GPL build that is bundled for voice and screenshare.

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

## Distributed binary — GPL-2.0-or-later

The default installers built by CI (`.deb`, `.AppImage`, `.dmg`, `.msi`)
bundle `org.bytedeco:ffmpeg-platform-gpl:7.1-1.5.11`, which includes:

- libx264 — GPL-2.0-or-later
- libx265 — GPL-2.0-or-later
- libopus — BSD-3-Clause
- libav* (FFmpeg core) — LGPL-2.1-or-later

Per the GPL "license of the whole" rule, the combined binary is
distributable only under GPL-2.0-or-later. Recipients of the installer have
the right to receive the corresponding source code; this is satisfied by
linking back to Puklic source on GitHub plus the upstream FFmpeg/JavaCPP
sources (see `LICENSE-third-party.txt` § "Source code availability").

## Why bundle the GPL build?

H.264 and H.265 encoding are required for Discord screenshare interop.
Using the system FFmpeg is not an option (CLAUDE.md mandates a fully
self-contained binary). The LGPL FFmpeg build does not include `libx264`
or `libx265`. Therefore the GPL build is the only viable choice.

## Implications for contributors and forks

Anyone modifying and redistributing the **binary** installer must comply
with GPL-2.0-or-later: ship source, preserve copyright notices, do not add
"further restrictions". Modifying the **source code** is unaffected — pull
requests, forks, and source-only redistributions remain pure Apache-2.0.

## Files of record

- `LICENSE` — Apache-2.0 (project source)
- `LICENSE-third-party.txt` — full list of bundled third-party components
- `docs/06_ops/licensing.md` — this file (explanation)
- Release notes — every GitHub Release reiterates the GPL bundling note
