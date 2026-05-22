# CLAUDE.md — Puklic (repo-level)

This file **extends** the global `~/.claude/CLAUDE.md`. Global rules (HARD RULE #0 **LOCAL ONLY** since 2026-05-22 — no K8s subagent dispatches, HARD RULE #1 TEST-FIRST pipeline, minimum-complexity, SOLID) apply in full.

---

## What Puklic IS

A lightweight native desktop/mobile chat client for Discord, built on Kotlin Multiplatform + Compose Multiplatform. Goal: an alternative to the Electron client focused on low RAM usage, Wayland-first Linux, long-term stability, and future mobile platforms (Android, iOS).

## What Puklic IS NOT

- ❌ A bot or bot framework
- ❌ A self-bot / account automation (auto-reply, auto-react, scraping, bulk operations)
- ❌ An AI agent / LLM integration
- ❌ A plugin for Discord
- ❌ A modification of the official client

When a feature idea only makes sense for automated accounts (scheduled messages, auto-translate, bulk delete), the answer is **NO** — that is bot territory.

---

## Performance targets

| Metric | Target |
|---|---|
| RAM idle (logged in, 1 guild) | < 150 MB |
| RAM active (10 guilds, 5 channels cache) | < 300 MB |
| Cold start (Linux) | < 2 s |
| Desktop binary (with JVM) | < 80 MB |

These targets apply to Phase 1 MVP. Voice/screenshare may push RAM higher — to be evaluated in Phase 3+.

---

## Architectural rules (repo-specific)

Global rules from `~/.claude/CLAUDE.md` plus:

1. **The UI must not parse or transform data.** Compose only renders finished state. Rich text parsing belongs in `:shared:chat-parser`.
2. **`:shared:*` modules must not know about the platform.** No direct calls to AWT, JVM-only APIs, Wayland, or PipeWire. Platform-specific code only via `expect/actual` in `:shared:platform-api`.
3. **Discord DTOs must not leak into the UI.** Layers: `Discord DTO → Domain → Persistence → UI state → Compose`. Mapping between layers is explicit.
4. **No global coroutine scope.** Every ViewModel / Repository / Session has its own scope with a defined lifecycle.
5. **No global event bus.** Streams via `StateFlow` / `SharedFlow` / `Channel` with a clear owner.
6. **Cache is always bounded.** No unbounded message buffer, no attachments in RAM.

---

## Documentation — mandatory workflow

The `docs/` directory is the **single source of truth** for architecture and the domain model. Structure:

```
docs/
├── 00_overview/        # Vision, glossary
├── 01_architecture/    # ADR, module map, data flow, threading
├── 02_domain/          # Chat model, RichText AST, Discord protocol
├── 03_infrastructure/  # Persistence, cache, platform abstractions, architect reports
├── 04_ui/              # Design system, screen inventory
├── 05_platforms/       # Linux/Wayland, Android, iOS specifics
├── 06_ops/             # Build, CI, release
└── 07_roadmap/         # Phases 1–5
```

**Rule:** every architectural or domain change must update at least one file in `docs/` in **the same commit** as the code. A PR without a doc update will not be merged.

Architect subagent reports: `docs/03_infrastructure/architect-reports/<YYYY-MM-DD>-<slug>.md`.

---

## Discord protocol — risk acknowledgement

Discord ToS prohibits third-party user clients. Puklic is tolerated only as long as it:
- does not automate the account (no self-bot features, see "What Puklic IS NOT")
- behaves like a real user (heartbeat timing, presence, typing)
- does not implement detection-evasion or crypto wrappers on top of the official protocol (DAVE will be implemented per the public spec, not reverse-engineered)

The risk of account ban is borne by the user. The project README must state this explicitly.

---

## Build & platforms

- **Build:** Gradle multimodule, Compose Multiplatform
- **Primary platform Phase 1:** Linux desktop (Wayland via XWayland for now — native Wayland backend for Compose is not ready)
- **Phase 2+:** Android, iOS (Compose iOS — one UI codebase, see ADR-0001)
- **Voice/media:** separate module, on desktop via PipeWire, on iOS/Android via platform-native audio

---

## Links

- Product vision: `docs/00_overview/product-vision.md`
- ADR index: `docs/01_architecture/adr/`
- Roadmap: `docs/07_roadmap/phases.md`
