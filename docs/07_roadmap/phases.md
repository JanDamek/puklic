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

- [x] Mentions (user, channel, role) — parser + renderer + resolve
  - 2026-05-27: parser already tokenised `<@id>`, `<@!id>` (legacy nickname), `<#id>`, `<@&id>`, `@everyone`, `@here`. This pass wires `RepositoryMentionResolver` to the app-scoped `RoleStore` (populated by `ChannelOrchestrator` from `GUILD_ROLE_*` gateway events) so role mentions resolve to `@RoleName` reactively with no render-thread IO. User + channel lookups continue via the persistence repositories (one-shot suspending `findById`). Role colour stays `null` (Discord DTO lacks the `color` field; renderer is colour-ready for when the DTO gains it). Unresolved targets fall back to deterministic `@user` / `@role` / `#channel` placeholders — no spinner, no async retry. See `docs/02_domain/richtext-ast.md` §Resolvers.
- [x] Custom emoji (Discord CDN, disk cache)
  - 2026-05-27: Parser already tokenised `<:name:id>` (static) and `<a:name:id>` (animated) into `EmojiRef.Custom`, and `RichTextView.CustomEmojiInline` rendered them inline via `coil3.compose.AsyncImage`. This pass (a) extracted the inline URL builder into the pure, tested object `EmojiCdnUrl` (commonMain, `shared/compose-ui`) — single SSOT for `https://cdn.discordapp.com/emojis/<id>.{png,gif}?size=<n>&quality=lossless`, also adopted by `ReactionsRow.EmojiVisual` (replacing a duplicate inline literal); (b) wired a bounded Coil `DiskCache` (50 MiB cap, under `PlatformPaths.cacheDir/image-cache`, LRU eviction) onto the singleton `ImageLoader` in `desktop/app/.../Main.kt` so repeated emoji/avatar/attachment fetches survive restarts. Library-first per memory rule: no custom HTTP fetcher, no hand-rolled cache. Animated GIF playback is **first-frame-only** on JVM desktop because Coil 3.1 does not yet publish a JVM-desktop animated decoder (the `coil-gif` artifact is Android-only). The URL still resolves, the cache still hits, and animation will switch on automatically when upstream Coil ships the JVM decoder — no temporary code on our side.
- [x] Link detection + preview (OpenGraph fetch)
  - 2026-05-23: Bare URLs already autolinked by `:shared:chat-parser` (autolink pass for `http(s)://`). Discord delivers OG previews server-side as `message.embeds` (type=link/article/website/image/video). Renderer in `:shared:compose-ui` (`MessageRow.EmbedCard`) reworked into a rich card: left color bar (from `embed.color`), site/provider name, author row (avatar + name, clickable if `author.url`), title (semibold, link-coloured + underline, clickable to `embed.url`), description (max 4 lines, ellipsised), right-aligned 80x80dp thumbnail, full image bounded at 400x300dp, fields with two-column grid for consecutive inline fields, footer with icon. Title click + image click + thumbnail click all open via `LocalUriHandler`. No local OG fetcher — Discord's server-side embeds cover this slice (Option A).
- [x] Full markdown (bold, italic, strikethrough, underline, quote, spoiler)
  - 2026-05-23: AST + parser already covered inline styles, spoilers, headings, fenced code, single-line quotes, mentions, links, timestamps, unicode + custom emoji. This pass adds `>>>` triple-quote (consumes to EOF), `- ` bullet lists (parser+renderer), and click-to-reveal spoiler (`SpoilerInline` composable: hidden block flips to revealed on tap).
- [x] Syntax-highlighted code blocks
  - 2026-05-23: Custom KMP tokeniser in `:shared:chat-parser` (`CodeHighlighter` + `CodeToken` + `CodeTokenKind`) covering kotlin/kt, java, python/py, javascript/js, typescript/ts, rust/rs, go, c, cpp/c++, bash/sh/shell, json, yaml/yml, xml/html, sql, swift. Token kinds: Keyword/Type/String/Number/Comment/Function/Literal/Punctuation/Plain. Renderer in `RichTextView` builds an `AnnotatedString` with a dark-theme palette (orange keywords, amber functions, green strings, blue numbers, grey italic comments). Unknown/null language → plain monospace fallback.
- [x] Attachments (upload, download, image preview, video thumbnail)
  - 2026-05-23: image preview (bounded, aspect-preserving) + full-size viewer dialog + video tile (play icon, duration, click-to-open) + file tile (extension badge, click-to-open via PlatformOpen.openUrl) landed.
  - 2026-05-27 (issue #23): upload pipeline complete — `DiscordRestClient.requestUploadUrls` + `uploadFile` (raw PUT, no Authorization header) + `sendMessage(attachments=…)`; `MessageGateway.sendMessage(attachments=…)` single seam (default empty list keeps text-only callsites source-compatible); `MessageOrchestrator.sendWithAttachments` bypasses the persistent outbound queue (in-memory only — mid-upload crash = user re-attaches, per architect Q1); `AttachmentLimits.maxBytesFor(guildPremiumTier)` returns 25/25/50/100 MiB for null/1/2/3; `FilePicker` interface in platform-api with desktop AWT actual; commonMain `Modifier.fileDropTarget` (Compose Desktop awtTransferable on JVM, no-op on Android/iOS); `ComposerViewModel.send()` is the single send path (previous `MessageListViewModel.sendMessage` bypass deleted).
- [x] Reactions UI (add/remove, list)
- [x] Message edit/delete sync via gateway
  - 2026-05-23: MESSAGE_UPDATE merges payload while preserving existing reactions; MESSAGE_DELETE + MESSAGE_DELETE_BULK remove from local storage; MessageRow shows "(edited)" next to the timestamp when `editedTimestamp != null`.
- [x] Email+password login (ADR-0002 Option B)
  - 2026-05-27: LoginScreen has Token/Email-Password tabs; `DiscordLoginClient` calls `POST /api/v10/auth/login`; TOTP MFA handled via `POST /api/v10/auth/mfa/totp`; captcha responses surface an error directing the user to the Token tab (no captcha solver — would be self-bot behavior); SMS/WebAuthn factors documented as token-paste fallback; resulting token persisted via the same `SecureStorage` path as token paste; passwords never logged or persisted.

## Phase 3 — Voice

- [x] Voice gateway protocol
- [x] Audio device enumeration (PipeWire / CoreAudio / AAudio)
- [x] Join/leave voice channel
- [x] Opus integration (libopus binding)
- [x] RTP/UDP voice transport
- [x] Voice encryption (xsalsa20_poly1305_lite)
  - Shipped as `aead_xchacha20_poly1305_rtpsize` (Discord's current mandatory mode since 2024-11). Legacy `xsalsa20_poly1305_lite` not implemented.
- [x] Mute/deafen UI + state sync
- [x] DAVE protocol (E2EE voice, per public spec)
  - 2026-05-23 (3.1a): architect report `docs/03_infrastructure/architect-reports/2026-05-23-dave-e2ee.md`.
  - 2026-05-23 (3.1b): `:shared:voice-dave` module + `MlsClient` interface + Wire `core-crypto-jvm:4.2.0` JVM actual + 3 passing smoke tests (init, KeyPackage, two-client Welcome exporter parity). Binary distribution license bumps to GPL-3.0-or-later (ADR-0007). Known gap: Wire 4.2.0 only exposes the AVS-labelled MLS exporter; DAVE label `"Discord Secure Frames v0"` requires a Wire upgrade or libdave-JNI in Phase 3.2.
  - [x] 3.1c: gateway opcodes 21-31 wiring (voice gateway client extension) — JSON ops 21-24, 31 + binary ops 25-30 dispatch through `DaveSession`.
  - [x] 3.1d: per-frame ChaCha20-Poly1305 encrypt/decrypt + key ratchet — libdave Encryptor/Decryptor + KeyRatchet exposed on `DaveSession.frameEncryptor(ssrc)` / `frameDecryptor(userId, ssrc)`; optional `daveEncrypt` / `daveDecrypt` hooks added to CapturePipeline + PlaybackPipeline (null = pass-through for the non-DAVE fallback); backend default flipped to libdave on macOS arm64. `DefaultVoiceClient` now instantiates a `DaveSession` when `SessionDescription.dave_protocol_version > 0`, plumbs voice-gateway binary + JSON DAVE frames into it, and hooks the capture/playback pipelines to its frame encryptor/decryptor. IDENTIFY advertises `max_dave_protocol_version: 1`. `VoiceClient.daveState: StateFlow<DaveUiState>` drives the lock icon on `VoiceStatusBar`.
  - [x] 3.1e: SAS pairwise fingerprint dialog + Active→{Disabled,Off} downgrade detector with 30s auto-hide banner (commit `07b2472`, issue #24 a+c). Multi-party correctness moves to manual E2E QA against real Discord — libdave's C API is consumer-only (Discord backend is the MLS external sender per RFC 9420 §11), so in-process N-party harness has no driver. Production coverage is wire-level frame crypto + two-client Welcome+exporter parity + SAS + downgrade detector. See issue #24 close comment 2026-05-28.
- [x] Voice state indicators in channel list
  - VoiceStatusBar (slice 10) shows connecting/connected/failed; speaking indicator wired through SSRC ↔ UserId resolver (Op 5 Speaking events). Channel-row click-to-join deferred — `GuildVoiceChannel` data class is not yet in the domain model; users connect through the status bar's Settings affordance for now.

## Phase 4 — Screenshare

macOS MVP (4.0) landed 2026-05-23 via the ffmpeg-subprocess pipeline on top of the existing
voice UDP socket + AEAD + gateway. See architect report
`docs/03_infrastructure/architect-reports/2026-05-23-screenshare.md`. Linux Wayland support
is deferred to 4.1.

- [x] xdg-desktop-portal D-Bus binding (4.1, Wayland) — 2026-05-27, full `CaptureMode` (Monitors/Windows/Both) + `CursorMode` (Hidden/Embedded/Metadata), `PortalResult` sealed type distinguishes Ok/UserCancelled/Error; see `shared/voice/.../linux/LinuxPortalScreenCast.kt`.
- [x] PipeWire stream capture (4.1, Wayland; macOS uses AVFoundation via ffmpeg, captured 2026-05-23)
      Implemented end-to-end by reusing FFmpeg-javacpp's libavdevice `pipewire` demuxer:
      `LinuxPortalScreenCast` performs the xdg-desktop-portal handshake → returns
      `(nodeId, fd)`; `DefaultScreenShareClient` constructs `LibavVideoEncoder` with the
      portal-allocated fd, which is forwarded to libavdevice via `av_dict_set("fd", ...)`
      and `node_id` via the input URL. Video frames are decoded, scaled, and re-encoded to
      H.264 inside the same encoder — no separate raw-frame abstraction is needed (the
      pipeline is unified with the macOS AVFoundation path).
- [x] Window picker (macOS via `osascript` enumeration, 4.0.1) — picker shows windows in a
      second tab; capture currently falls back to fullscreen of monitor 0 because avfoundation
      has no per-window input. Per-window capture via ScreenCaptureKit is deferred to 4.0.2.
      Linux Wayland window picker via portal RequestScreenCast remains in 4.1.
- [x] Monitor picker (ScreenSharePickerDialog, slice 6)
- [x] H.264 encoder (libx264 via ffmpeg subprocess; slice 3)
- [x] VP8 encoder (Linux 4.1) — 2026-05-27, libvpx via bundled `ffmpeg-platform-gpl` 7.1-1.5.11; `LibavVideoEncoder` now takes a `VideoCodec` parameter (`H264` default / `VP8`); `chooseCodec(offered)` helper picks H.264 over VP8 from a Discord codec advertisement. End-to-end SDP-driven negotiation (wiring receiver's `SessionDescription.codecs` to encoder construction + VP8 RTP packetiser per RFC 7741) is a follow-up — see issue.
- [~] Share with audio — macOS audio share dropped 2026-05-28 (BlackHole friction; ScreenCaptureKit out of scope for non-priority platform). Linux/Wayland PipeWire system audio capture remains in scope (issue #25, prereqs 1 + 3 open).
- [x] Video send via voice gateway transport (RTP FU-A + AEAD on Ready.video_ssrc; slices 4–5)
- [x] Receive incoming screenshare video (Phase 4.2, 2026-05-23) — VoicePacketDispatcher + H.264
      depacketizer + in-process libavcodec H.264 decoder + Compose `IncomingVideoPane`. H.264 only;
      VP8 receive deferred. See screenshare report §13.

## Distribution — self-contained installers (slice 5/6, landed 2026-05-23)

- [x] Compose Desktop `nativeDistributions` configured (Deb + AppImage on Linux + Dmg on macOS arm64 — both officially shipped, see CLAUDE.md §Platforms)
- [x] Per-OS FFmpeg classifier (`detectFfmpegClassifier()` in `shared/voice/build.gradle.kts`) — ~30 MB natives instead of ~150 MB umbrella
- [x] App icons wired (`icons/linux/512x512/puklic.png`, `icons/macos/puklic.icns`)
- [x] `.dmg` build verified on macOS arm64 host — version follows `puklic.version` in `gradle.properties` (same string as Linux .deb / .AppImage)
- [x] `.deb` + `.AppImage` build verified on Linux host (CI) — verified by `build-installers.yml` since 2026-05-27 (commit ea511c4); v1.1.0 release ships real `Puklic-1.1.0-x86_64.AppImage` via `appimagetool` step
- [x] README install instructions

See [docs/03_infrastructure/architect-reports/2026-05-23-self-contained-linux.md](../03_infrastructure/architect-reports/2026-05-23-self-contained-linux.md) §6–§8.

## Phase 6 — Apple distribution (iOS + Mac App Store via TestFlight, internal testers)

Scope set 2026-05-28 per architect report
[`2026-05-28-apple-distribution.md`](../03_infrastructure/architect-reports/2026-05-28-apple-distribution.md).
Strategy: single "Designed for iPad on Mac" app (one iOS arm64 binary serves
iPhone, iPad, and Apple Silicon Macs). Chat-only build (no voice, no screenshare)
— GPL-3.0 deps (FFmpeg, libx264, libdave) excluded to satisfy App Store §3.
Desktop builds (GitHub Releases) keep full feature set.

- [x] Slice 1 — iOS dep boundary + `verifyIosNoGplDeps` Gradle task (`e8f1594`)
- [x] Slice 2a — extract `:shared:voice-api` (Apache-2.0 KMP types) from `:shared:voice` (`a3c274e`, #27)
- [x] Slice 2b — iOS `actual` impls in `:ios:platform` (Keychain, FilePicker, Clipboard, NSFileManager, openURL) (`66db86b`, #28)
- [x] Slice 2.5 — shared modules iOS-green sweep (`819a4eb`, #31)
- [x] Slice 3 — `:ios:app` Compose iOS framework + `PuklicAppRootViewController` (`1628db1`, #30)
- [x] Slice 3.5 — `IosDependencyGraph` (NativeSqliteDriver + Ktor Darwin + iOS session bootstrap) (`c0c3417`, #32)
- [x] Slice 4 — `iosApp/iosApp.xcodeproj` (xcodegen-driven) + Swift `AppDelegate` entry (`adfaf2d`, #38)
- [x] Slice 5 — App ID `cz.damek.puklic.app` + Push Notifications cap + Apple Distribution cert + App Store provisioning profile + ASC app record `6774288340` (#40)
- [x] Slice 6 — fastlane Fastfile + Appfile + Gemfile + `.github/workflows/apple-testflight.yml` (`adfaf2d`, #39)
- [ ] Slice 7 — First TestFlight upload + Beta App Review submission (blocked on user registering GH Secrets per #40)
- [ ] Slice 8 — Internal-tester group invite (depends on 7)
- [ ] Slice 9 — APN `.p8` auth key provisioning (push infra prep; no consumer yet)
- [ ] Slice 10 — Firebase project + service-account JSON (Android push prep; no consumer yet)
- [x] Slice 11 — iOS Broadcast Extension target (App Group IPC + RPSystemBroadcastPickerView) (`7a259c5`, FP-11, #51)
- [x] Slice 12 — iOS ReplayKit screencast impl + Material3 confirm dialog (`cbd80f7`, FP-12, #52)
- [-] Slice 13 — macOS Kotlin/Native target (`:macos:app`) — **BLOCKED, redirected.** CMP 1.8 ships no native macOS Compose UI runtime. Mac App Store ship pivoted to the JVM Compose Desktop path delivered by Slice 14. Original plan in `2026-05-29-full-feature-parity.md` §3.6 (SUPERSEDED).
- [x] Slice 14 — Mac App Store target via JVM Compose Desktop (hardened runtime + sandbox + entitlements + jpackage `--type pkg --mac-app-store`):
  - [x] FP-14a — Architect verification + library survey + jpackage probe (`9d183f1`, #54)
  - [x] FP-14b — Red-phase failing tests (`f1651a0`, #55)
  - [x] FP-14c — VideoToolbox + libopus + Network.framework JNA wrappers (`4d3eb38`, #56)
  - [x] FP-14d — Gradle `macAppStore` source set + `packageMacAppStore` + entitlements (`1d5a53b`, #57)
  - [x] FP-14e — fastlane `mac_app_store` lane + `.github/workflows/mac-app-store.yml` (`01a0e30`, #58)
  - [x] FP-14f — Critic findings (`334d26a`, #59) + fixes for F-1, F-3..F-6, F-14, F-15, F-22..F-24 (`73a8922`, #60)
  - [x] FP-14g — Docs closure (this commit, #61)
  - [ ] FP-14h — Voice wiring + critic follow-up (see below)
- [ ] Slice 15 (FP-14h) — Wire `AppleNativeVoiceClient` into Mac App Store + iOS dependency graphs, then resolve the FP-14f deferred findings:
      voice wiring (F-2), `JnaNwConnectionUdpTransport` lifetime (F-7..F-9), VideoToolbox refcount + JMM (F-10, F-11),
      libopus close-race (F-12), split `:shared:voice-codec` into api + libav (F-13), HARD RULE #2 cleanup of `MacAppStoreMain.kt`
      "voice not wired in v1" note (F-16), and JNA / Dispatch / Info.plist / dock-icon NITs (F-17..F-21).
      **Blocking gate:** FP-14h MUST land before any Mac App Store TestFlight submission — until then both iOS and the
      Mac App Store target ship `NoOpVoiceClient` and the FP-1..FP-12 native codec primitives are dead code.

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
- [x] Logging — SLF4J + Logback on desktop (10 MB rotation, 14-day history, 200 MB cap); Kermit (multiplatform call sites) bridged into SLF4J; token redaction via `RedactingPatternLayout` (Bearer / Authorization / `mfa.*`). Log dir: Linux `$XDG_DATA_HOME/puklic/logs` (canonical); macOS `~/Library/Logs/Puklic` (dev-side)
- [ ] i18n framework (English + at least one additional locale)
- [ ] Accessibility (Compose semantics, keyboard navigation)
- [x] Update mechanism (auto-update on desktop) — opt-in check against GitHub Releases API
      (`UpdateChecker` + `UpdateCheckerScheduler` in `:desktop:app`), banner notifies user, opens
      release page in browser. No in-app installation; OS-native installer/store handles the
      actual upgrade. Default ON, toggle via `-Dpuklic.update.autoCheck=false`.

## Platforms — when they are added

Scope set 2026-05-25 (issue #22, HARD RULE #2) and revised same day per
user "všechny platformy stejně":

- Linux desktop x86_64: **officially shipped** from phase 1 (.deb + .AppImage)
- macOS arm64: **officially shipped** (.dmg, same version string as Linux)
- Windows desktop x86_64: **officially shipped** (FP-10 2026-05-29, issue #50) —
  .exe + .msi via Compose Desktop jpackage on `windows-2022` GitHub runners;
  platform actuals in `:desktop:platform-windows` (Credential Manager via JNA
  Advapi32, Shell32 ShellExecuteW, SystemTray balloon, %APPDATA%
  / %LOCALAPPDATA% paths); FP-9 `WindowsScreenCaptureFactory` wired into
  `DependencyGraph` for DXGI screen + WASAPI loopback capture.
- macOS x86_64 (Intel): **out of scope**
- Android: future mobile phase (KMP scaffolding ready, not actively shipping)
- iOS (App Store, chat-only, Designed for iPad on Mac): **planned Phase 6** —
  architect report 2026-05-28, see Phase 6 above.
