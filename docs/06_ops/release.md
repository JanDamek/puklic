# Release

Release process for Puklic. Draft — finalize after the first shipped MVP.

## Versioning

**SemVer.** `MAJOR.MINOR.PATCH`:
- `MAJOR` — breaking change in stored data (incompatible cache wipe required)
- `MINOR` — new feature, backwards-compatible
- `PATCH` — bugfix only

Pre-release: `0.1.0-alpha.1`, `0.1.0-rc.1`, etc. **Current state: pre-0.1.0** (no code yet).

## Release cadence

- **Phase 1 MVP:** `0.1.0` (when all 17 Phase 1 items are done)
- **Phase 2:** `0.2.0`
- **Phase 3 (voice):** `0.3.0`
- **Phase 4 (screenshare):** `0.4.0`
- **Phase 5 / production-ready:** `1.0.0`

Patch releases in between as needed.

## Release checklist

```
[ ] All CI green on main
[ ] CHANGELOG.md updated
[ ] Version bumped in libs.versions.toml + Android versionCode/Name
[ ] Smoke test desktop manually (login, send message, restart)
[ ] Smoke test mobile (phase 2+)
[ ] Memory profile baseline OK (no leak regression)
[ ] git tag vX.Y.Z + push
[ ] CI release.yml runs and creates GitHub Release
[ ] Edit GitHub Release notes
[ ] Update README install section if needed
[ ] Announce (later — Mastodon, blog?)
```

## Changelog

Convention: [Keep a Changelog](https://keepachangelog.com).

`CHANGELOG.md` in root, one section per release:

```
## [0.1.0] - 2026-XX-XX
### Added
- Initial MVP: login, guild list, channel list, message read/send
- ...
### Fixed
- ...
```

Pre-release entries go in the `## [Unreleased]` section and are moved when tagging.

## Distribution

| Channel | Platform | Trust |
|---|---|---|
| GitHub Releases | All | Primary, signed |
| Flathub | Linux | After phase 2, vetting required |
| F-Droid | Android | After phase 2, reproducible build needed |
| Google Play | Android | After phase 2, Discord DMCA risk |
| App Store | iOS | After phase 2/3, Apple rejection risk |
| AUR | Arch Linux | Community-maintained, no commitment |

## Signing & integrity

- GitHub Releases with GPG-signed AppImage + checksums (`SHA256SUMS`, `SHA256SUMS.asc`)
- macOS DMG: Apple Developer ID signed + notarized
- Android: Upload key + Play app signing
- iOS: Apple Distribution cert

GPG public key published in the repo (`docs/06_ops/release-signing-key.asc`) and on keyservers (`keys.openpgp.org`).

## Update mechanism

**Phase 1:** No auto-update. Users download new versions from GitHub Releases.

**Phase 5+:** Options:
- Conveyor's built-in updater (Sparkle-like) for desktop
- Flathub auto-update for Linux
- Play / App Store standard for mobile

In-app notification "New version available" → link to release notes — implement in phase 2.

## Crash reporting

**Phase 1:** Local-only crashes written to `$XDG_DATA_HOME/puklic/crashes/`, user-initiated upload (button in Settings).

**Phase 5+:** Sentry self-hosted or similar — opt-in in Settings.

## Rollback

- AppImage / IPA / APK on GitHub Releases — users can download older versions
- No server-side state, so downgrade = reinstall an older binary
- If a schema migration is breaking: users must wipe the cache. The UI must detect "DB schema from the future" and offer a rollback workflow.

## Marketing / communications (out of MVP scope)

Outside current scope. When `1.0.0` ships, consider:
- Blog post
- Mastodon thread
- Hacker News / Lobsters submission

Never promote Puklic in a way that provokes Discord into filing a DMCA — no claims of "better than official", no side-by-side comparisons using Discord branding.
