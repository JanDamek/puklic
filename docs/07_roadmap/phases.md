# Roadmap — Phases 1–5

Detailed breakdown of phases from the specification. Per-task status tracking.

Legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[-]` skip / out of scope

---

## Phase 1 — Basic chat client (MVP)

Goal: a usable read+write client for everyday text communication.

- [x] Gradle multimodule setup
- [x] Compose Desktop app skeleton
- [x] Ktor REST client (Discord API v10)
- [x] WebSocket gateway (connect, identify, heartbeat, dispatch)
- [x] Login screen (token paste) + secure storage
- [x] Session persistence (resume vs identify)
- [x] Guild list
- [x] Channel list (text channels only)
- [x] Message list (lazy load, scroll-back)
- [x] Send message (plain text)
- [x] Local SQLite cache (SQLDelight)
- [x] StateFlow architecture wired end-to-end
- [x] Basic RichText AST (paragraph, text run, inline code, code block)
- [x] Basic parser (markdown subset)
- [x] Basic Compose RichText renderer
- [x] Unicode emoji support
- [x] Desktop notifications (libnotify / xdg-notification)
- [x] Settings screen (account info, cache limits, logout)

## Phase 2 — Rich content

- [ ] Mentions (user, channel, role) — parser + renderer + resolve
- [ ] Custom emoji (Discord CDN, disk cache)
- [x] Link detection + preview (OpenGraph fetch)
  - 2026-05-23: Bare URLs already autolinked by `:shared:chat-parser` (autolink pass for `http(s)://`). Discord delivers OG previews server-side as `message.embeds` (type=link/article/website/image/video). Renderer in `:shared:compose-ui` (`MessageRow.EmbedCard`) reworked into a rich card: left color bar (from `embed.color`), site/provider name, author row (avatar + name, clickable if `author.url`), title (semibold, link-coloured + underline, clickable to `embed.url`), description (max 4 lines, ellipsised), right-aligned 80x80dp thumbnail, full image bounded at 400x300dp, fields with two-column grid for consecutive inline fields, footer with icon. Title click + image click + thumbnail click all open via `LocalUriHandler`. No local OG fetcher — Discord's server-side embeds cover this slice (Option A).
- [x] Full markdown (bold, italic, strikethrough, underline, quote, spoiler)
  - 2026-05-23: AST + parser already covered inline styles, spoilers, headings, fenced code, single-line quotes, mentions, links, timestamps, unicode + custom emoji. This pass adds `>>>` triple-quote (consumes to EOF), `- ` bullet lists (parser+renderer), and click-to-reveal spoiler (`SpoilerInline` composable: hidden block flips to revealed on tap).
- [x] Syntax-highlighted code blocks
  - 2026-05-23: Custom KMP tokeniser in `:shared:chat-parser` (`CodeHighlighter` + `CodeToken` + `CodeTokenKind`) covering kotlin/kt, java, python/py, javascript/js, typescript/ts, rust/rs, go, c, cpp/c++, bash/sh/shell, json, yaml/yml, xml/html, sql, swift. Token kinds: Keyword/Type/String/Number/Comment/Function/Literal/Punctuation/Plain. Renderer in `RichTextView` builds an `AnnotatedString` with a dark-theme palette (orange keywords, amber functions, green strings, blue numbers, grey italic comments). Unknown/null language → plain monospace fallback.
- [ ] Attachments (upload, download, image preview, video thumbnail)
  - 2026-05-23: image preview (bounded, aspect-preserving) + full-size viewer dialog + video tile (play icon, duration, click-to-open) + file tile (extension badge, click-to-open via PlatformOpen.openUrl) landed; upload still pending.
- [x] Reactions UI (add/remove, list)
- [x] Message edit/delete sync via gateway
  - 2026-05-23: MESSAGE_UPDATE merges payload while preserving existing reactions; MESSAGE_DELETE + MESSAGE_DELETE_BULK remove from local storage; MessageRow shows "(edited)" next to the timestamp when `editedTimestamp != null`.
- [ ] Email+password login (ADR-0002 Option B)

## Phase 3 — Voice

- [x] Voice gateway protocol
- [x] Audio device enumeration (PipeWire / CoreAudio / AAudio)
- [x] Join/leave voice channel
- [x] Opus integration (libopus binding)
- [x] RTP/UDP voice transport
- [x] Voice encryption (xsalsa20_poly1305_lite)
  - Shipped as `aead_xchacha20_poly1305_rtpsize` (Discord's current mandatory mode since 2024-11). Legacy `xsalsa20_poly1305_lite` not implemented.
- [x] Mute/deafen UI + state sync
- [~] DAVE protocol (E2EE voice, per public spec)
  - 2026-05-23 (3.1a): architect report `docs/03_infrastructure/architect-reports/2026-05-23-dave-e2ee.md`.
  - 2026-05-23 (3.1b): `:shared:voice-dave` module + `MlsClient` interface + Wire `core-crypto-jvm:4.2.0` JVM actual + 3 passing smoke tests (init, KeyPackage, two-client Welcome exporter parity). Binary distribution license bumps to GPL-3.0-or-later (ADR-0007). Known gap: Wire 4.2.0 only exposes the AVS-labelled MLS exporter; DAVE label `"Discord Secure Frames v0"` requires a Wire upgrade or libdave-JNI in Phase 3.2.
  - [ ] 3.1c: gateway opcodes 21-31 wiring (voice gateway client extension).
  - [ ] 3.1d: per-frame ChaCha20-Poly1305 encrypt/decrypt + key ratchet.
  - [ ] 3.1e: pairwise fingerprint UI + lock-icon state.
- [x] Voice state indicators in channel list
  - VoiceStatusBar (slice 10) shows connecting/connected/failed; speaking indicator wired through SSRC ↔ UserId resolver (Op 5 Speaking events). Channel-row click-to-join deferred — `GuildVoiceChannel` data class is not yet in the domain model; users connect through the status bar's Settings affordance for now.

## Phase 4 — Screenshare

macOS MVP (4.0) landed 2026-05-23 via the ffmpeg-subprocess pipeline on top of the existing
voice UDP socket + AEAD + gateway. See architect report
`docs/03_infrastructure/architect-reports/2026-05-23-screenshare.md`. Linux Wayland support
is deferred to 4.1.

- [ ] xdg-desktop-portal D-Bus binding (4.1, Wayland)
- [ ] PipeWire stream capture (4.1, Wayland; macOS uses AVFoundation via ffmpeg, captured 2026-05-23)
- [x] Window picker (macOS via `osascript` enumeration, 4.0.1) — picker shows windows in a
      second tab; capture currently falls back to fullscreen of monitor 0 because avfoundation
      has no per-window input. Per-window capture via ScreenCaptureKit is deferred to 4.0.2.
      Linux Wayland window picker via portal RequestScreenCast remains in 4.1.
- [x] Monitor picker (ScreenSharePickerDialog, slice 6)
- [x] H.264 encoder (libx264 via ffmpeg subprocess; slice 3)
- [ ] VP8 encoder (Linux 4.1)
- [~] Share with audio — macOS routes via BlackHole 2ch (user-installed); PipeWire audio capture in 4.1
- [x] Video send via voice gateway transport (RTP FU-A + AEAD on Ready.video_ssrc; slices 4–5)
- [x] Receive incoming screenshare video (Phase 4.2, 2026-05-23) — VoicePacketDispatcher + H.264
      depacketizer + in-process libavcodec H.264 decoder + Compose `IncomingVideoPane`. H.264 only;
      VP8 receive deferred. See screenshare report §13.

## Distribution — self-contained installers (slice 5/6, landed 2026-05-23)

- [x] Compose Desktop `nativeDistributions` configured (Deb, AppImage on Linux; Dmg on macOS; Msi on Windows)
- [x] Per-OS FFmpeg classifier (`detectFfmpegClassifier()` in `shared/voice/build.gradle.kts`) — ~30 MB natives instead of ~150 MB umbrella
- [x] App icons wired (`icons/linux/512x512/puklic.png`, `icons/macos/puklic.icns`, `icons/windows/puklic.ico`)
- [x] `.dmg` build verified on macOS host — `Puklic-1.0.0.dmg` ≈ 157 MB
- [ ] `.deb` + `.AppImage` build verified on Linux host (CI)
- [ ] `.msi` build verified on Windows host (CI)
- [x] README install instructions

See [docs/03_infrastructure/architect-reports/2026-05-23-self-contained-linux.md](../03_infrastructure/architect-reports/2026-05-23-self-contained-linux.md) §6–§8.

## Phase 5 — Optimisation

- [ ] Memory profiling (JFR baseline + per-screen flamegraph)
- [ ] Image cache tuning (eviction policy, disk size)
- [ ] Disk cache benchmarking
- [ ] Coroutine leak detection (debug agent in dev builds)
- [ ] Compose recomposition optimisation (`Modifier.composed` audit, stable types)
- [ ] SQLite index audit + EXPLAIN QUERY PLAN review
- [ ] Network retry/backoff policy (gateway resume, REST 429 handling)
- [ ] Cold start measurement + optimisation (target < 2 s)

---

## Cross-cutting concerns (ongoing across all phases)

- [x] Crash reporting — local crash dumps to `<logDir>/crashes/crash-<ts>.txt` via `dev.puklic.desktop.crash.CrashReporter`; opt-in remote upload remains TODO
- [x] Logging — SLF4J + Logback on desktop (10 MB rotation, 14-day history, 200 MB cap); Kermit (multiplatform call sites) bridged into SLF4J; token redaction via `RedactingPatternLayout` (Bearer / Authorization / `mfa.*`). Log dir: macOS `~/Library/Logs/Puklic`, Linux `$XDG_DATA_HOME/puklic/logs`, Windows `%LOCALAPPDATA%/Puklic/logs`
- [ ] i18n framework (English + at least one additional locale)
- [ ] Accessibility (Compose semantics, keyboard navigation)
- [x] Update mechanism (auto-update on desktop) — opt-in check against GitHub Releases API
      (`UpdateChecker` + `UpdateCheckerScheduler` in `:desktop:app`), banner notifies user, opens
      release page in browser. No in-app installation; OS-native installer/store handles the
      actual upgrade. Default ON, toggle via `-Dpuklic.update.autoCheck=false`.

## Platforms — when they are added

- Linux desktop: from phase 1
- macOS / Windows desktop: best-effort from phase 1 (Compose Desktop supports it), tested no earlier than phase 2
- Android: phase 2 ship target
- iOS: phase 2/3 (depends on Compose iOS maturity)
