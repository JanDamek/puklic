# Roadmap — Phases 1–5

Detailed breakdown of phases from the specification. Per-task status tracking.

Legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[-]` skip / out of scope

---

## Phase 1 — Basic chat client (MVP)

Goal: a usable read+write client for everyday text communication.

- [ ] Gradle multimodule setup
- [ ] Compose Desktop app skeleton
- [ ] Ktor REST client (Discord API v10)
- [ ] WebSocket gateway (connect, identify, heartbeat, dispatch)
- [ ] Login screen (token paste) + secure storage
- [ ] Session persistence (resume vs identify)
- [ ] Guild list
- [ ] Channel list (text channels only)
- [ ] Message list (lazy load, scroll-back)
- [ ] Send message (plain text)
- [ ] Local SQLite cache (SQLDelight)
- [ ] StateFlow architecture wired end-to-end
- [ ] Basic RichText AST (paragraph, text run, inline code, code block)
- [ ] Basic parser (markdown subset)
- [ ] Basic Compose RichText renderer
- [ ] Unicode emoji support
- [ ] Desktop notifications (libnotify / xdg-notification)
- [ ] Settings screen (account info, cache limits, logout)

## Phase 2 — Rich content

- [ ] Mentions (user, channel, role) — parser + renderer + resolve
- [ ] Custom emoji (Discord CDN, disk cache)
- [ ] Link detection + preview (OpenGraph fetch)
- [ ] Full markdown (bold, italic, strikethrough, underline, quote, spoiler)
- [ ] Syntax-highlighted code blocks
- [ ] Attachments (upload, download, image preview, video thumbnail)
- [ ] Reactions UI (add/remove, list)
- [ ] Message edit/delete sync via gateway
- [ ] Email+password login (ADR-0002 Option B)

## Phase 3 — Voice

- [ ] Voice gateway protocol
- [ ] Audio device enumeration (PipeWire / CoreAudio / AAudio)
- [ ] Join/leave voice channel
- [ ] Opus integration (libopus binding)
- [ ] RTP/UDP voice transport
- [ ] Voice encryption (xsalsa20_poly1305_lite)
- [ ] Mute/deafen UI + state sync
- [ ] DAVE protocol (E2EE voice, per public spec)
- [ ] Voice state indicators in channel list

## Phase 4 — Wayland screenshare

- [ ] xdg-desktop-portal D-Bus binding
- [ ] PipeWire stream capture
- [ ] Window picker (via portal RequestScreenCast)
- [ ] Monitor picker
- [ ] H.264 / VP8 encoder (libav / native)
- [ ] Share with audio (PipeWire audio capture)
- [ ] Video send via voice gateway transport

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

- [ ] Crash reporting (local to `$XDG_DATA_HOME/puklic/crashes/`, opt-in upload)
- [ ] Logging (structured, rotation, redact tokens)
- [ ] i18n framework (English + at least one additional locale)
- [ ] Accessibility (Compose semantics, keyboard navigation)
- [ ] Update mechanism (auto-update on desktop?)

## Platforms — when they are added

- Linux desktop: from phase 1
- macOS / Windows desktop: best-effort from phase 1 (Compose Desktop supports it), tested no earlier than phase 2
- Android: phase 2 ship target
- iOS: phase 2/3 (depends on Compose iOS maturity)
