# Release

Release proces pro Puklic. Draft — finalizace po prvním shippnutém MVP.

## Versioning

**SemVer.** `MAJOR.MINOR.PATCH`:
- `MAJOR` — breaking change v ukládaných datech (incompatible cache wipe required)
- `MINOR` — nová featura, backwards-compatible
- `PATCH` — bugfix only

Pre-release: `0.1.0-alpha.1`, `0.1.0-rc.1`, atd. **Aktuální stav: pre-0.1.0** (žádný code).

## Release cadence

- **Fáze 1 MVP:** `0.1.0` (až bude všech 17 položek fáze 1 done)
- **Fáze 2:** `0.2.0`
- **Fáze 3 (voice):** `0.3.0`
- **Fáze 4 (screenshare):** `0.4.0`
- **Fáze 5 / production-ready:** `1.0.0`

Mezitím patch releases dle potřeby.

## Release checklist

```
[ ] All CI green on main
[ ] CHANGELOG.md updated
[ ] Version bumped v libs.versions.toml + Android versionCode/Name
[ ] Smoke test desktop manually (login, send message, restart)
[ ] Smoke test mobile (fáze 2+)
[ ] Memory profile baseline OK (no leak regression)
[ ] git tag vX.Y.Z + push
[ ] CI release.yml runs and creates GitHub Release
[ ] Edit GitHub Release notes
[ ] Update README install section if needed
[ ] Announce (později — Mastodon, blog?)
```

## Changelog

Konvence: [Keep a Changelog](https://keepachangelog.com).

`CHANGELOG.md` v root, sekce per release:

```
## [0.1.0] - 2026-XX-XX
### Added
- Initial MVP: login, guild list, channel list, message read/send
- ...
### Fixed
- ...
```

Pre-release entries v `## [Unreleased]` sekci, přesouvají se při tagu.

## Distribuce

| Channel | Platforma | Trust |
|---|---|---|
| GitHub Releases | All | Primary, signed |
| Flathub | Linux | Po fázi 2, vetting JE potřeba |
| F-Droid | Android | Po fázi 2, reproducible build needed |
| Google Play | Android | Po fázi 2, risk Discord DMCA |
| App Store | iOS | Po fázi 2/3, risk Apple reject |
| AUR | Arch Linux | Community-maintained, no commitment |

## Signing & integrity

- GitHub Releases s GPG-signed AppImage + checksums (`SHA256SUMS`, `SHA256SUMS.asc`)
- macOS DMG: Apple Developer ID signed + notarized
- Android: Upload key + Play app signing
- iOS: Apple Distribution cert

GPG public key publikován v repo (`docs/06_ops/release-signing-key.asc`) a na keyservers (`keys.openpgp.org`).

## Update mechanism

**Fáze 1:** Žádný auto-update. User stahuje novou verzi z GitHub Releases.

**Fáze 5+:** Možnosti:
- Conveyor's built-in updater (Sparkle-like) pro desktop
- Flathub auto-update pro Linux
- Play/App Store standard pro mobile

In-app notification „New version available" → link na release notes — doimplementovat ve fázi 2.

## Crash reporting

**Fáze 1:** Local-only crashes do `$XDG_DATA_HOME/puklic/crashes/`, user-initiated upload (button v Settings).

**Fáze 5+:** Sentry self-hosted nebo similar — opt-in v Settings.

## Rollback

- AppImage / IPA / APK on GitHub Releases — user může stáhnout starší verzi
- Žádný server-side state, takže downgrade = re-install staršího binary
- Pokud schema migration breaking: user musí wipe cache. UI musí poznat „DB schema z budoucnosti" a nabídnout rollback workflow.

## Marketing / komunikace (out of MVP)

Mimo aktuální scope. Až bude `1.0.0` zvážíme:
- Blog post
- Mastodon thread
- Hacker News / Lobsters submission

Nikdy nepropagovat Puklic způsobem, který Discord vyprovokuje k DMCA — žádné claims „better than official", žádné side-by-side comparisons s Discord brandingem.
