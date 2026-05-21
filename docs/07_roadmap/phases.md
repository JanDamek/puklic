# Roadmap — fáze 1–5

Detailní rozpis fází ze zadání. Tracking statusů per task.

Legenda: `[ ]` todo · `[~]` in progress · `[x]` done · `[-]` skip / out of scope

---

## Fáze 1 — Základní chat klient (MVP)

Cíl: použitelný read+write klient pro běžnou textovou komunikaci.

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
- [ ] Desktop notifikace (libnotify / xdg-notification)
- [ ] Settings screen (account info, cache limits, logout)

## Fáze 2 — Rich content

- [ ] Mentions (user, channel, role) — parser + renderer + resolve
- [ ] Custom emoji (Discord CDN, disk cache)
- [ ] Link detection + preview (OpenGraph fetch)
- [ ] Full markdown (bold, italic, strikethrough, underline, quote, spoiler)
- [ ] Syntax-highlighted code blocks
- [ ] Attachments (upload, download, image preview, video thumbnail)
- [ ] Reactions UI (add/remove, list)
- [ ] Message edit/delete sync via gateway
- [ ] Email+password login (ADR-0002 Option B)

## Fáze 3 — Voice

- [ ] Voice gateway protokol
- [ ] Audio device enumeration (PipeWire / CoreAudio / AAudio)
- [ ] Join/leave voice channel
- [ ] Opus integration (libopus binding)
- [ ] RTP/UDP voice transport
- [ ] Voice encryption (xsalsa20_poly1305_lite)
- [ ] Mute/deafen UI + state sync
- [ ] DAVE protokol (E2EE voice, dle veřejné spec)
- [ ] Voice state indicators v channel list

## Fáze 4 — Wayland screenshare

- [ ] xdg-desktop-portal D-Bus binding
- [ ] PipeWire stream capture
- [ ] Window picker (přes portal RequestScreenCast)
- [ ] Monitor picker
- [ ] H.264 / VP8 encoder (libav / native)
- [ ] Share with audio (PipeWire audio capture)
- [ ] Video send přes voice gateway transport

## Fáze 5 — Optimalizace

- [ ] Memory profiling (JFR baseline + per-screen flamegraph)
- [ ] Image cache tuning (eviction policy, disk size)
- [ ] Disk cache benchmarking
- [ ] Coroutine leak detection (debug agent v dev buildech)
- [ ] Compose recomposition optimization (`Modifier.composed` audit, stable types)
- [ ] SQLite index audit + EXPLAIN QUERY PLAN review
- [ ] Network retry/backoff policy (gateway resume, REST 429 handling)
- [ ] Cold start measurement + optimalizace (target < 2 s)

---

## Cross-cutting concerns (průběžně přes všechny fáze)

- [ ] Crash reporting (lokálně do `$XDG_DATA_HOME/puklic/crashes/`, opt-in upload)
- [ ] Logging (structured, rotation, redact tokens)
- [ ] i18n framework (česky + EN minimálně)
- [ ] Accessibility (Compose semantics, keyboard navigation)
- [ ] Update mechanism (auto-update na desktop?)

## Platformy — kdy přibývají

- Linux desktop: od fáze 1
- macOS / Windows desktop: best-effort od fáze 1 (Compose Desktop podporuje), tested ne dříve než fáze 2
- Android: fáze 2 ship cíl
- iOS: fáze 2/3 (závisí na zralosti Compose iOS)
