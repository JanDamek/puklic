# CLAUDE.md — Puklic (repo-level)

This file **extends** the global `~/.claude/CLAUDE.md`. Global rules (HARD RULE #0 **LOCAL ONLY** since 2026-05-22 — no K8s subagent dispatches, HARD RULE #1 TEST-FIRST pipeline, minimum-complexity, SOLID) apply in full.

---

## HARD RULE #2 — NEVER TEMPORARY, ALWAYS CONCEPTUAL

User explicit 2026-05-25 in capitals: **"NIKDY NIC DOČASNĚ, VŽDY VŠE KONVEPČNĚ PŘEDĚLAT, DEPRECATED A DOČASNÁ, HOST, QUICK FIX NESMÍ SE NIKDY PROVÁDĚT !!!"**

Translation: NEVER ship temporary solutions. ALWAYS redo as a complete conceptual change. **Deprecated / temporary / host-only / quick-fix code MUST NEVER be introduced.**

### Forbidden patterns

- ❌ `// TODO: remove later` — if it needs removing, don't write it
- ❌ `// temporary workaround until X` — fix X first or block until then
- ❌ `// disable for now, re-enable when needed` — if it's needed eventually, leave it; if not, delete it
- ❌ Commenting out matrix entries / config / code "for now"
- ❌ Adding fallback shims for features not yet built
- ❌ `// quick-fix` / `// hot-fix` / `// hacky but works`
- ❌ Renaming `_unused`, leaving dead code, "we'll come back to this"
- ❌ Backwards-compatibility shims that have no current caller (still pre-MVP)
- ❌ Stub method returning fake data "until real impl"
- ❌ Configuration flags toggling between half-built feature and old behavior

### When a "temporary" feels tempting

Step back and ask: **what's the conceptual right answer**? Then either:
1. **Block** — file an issue documenting the prerequisite + stop. Wait for proper unblock.
2. **Do it fully** — implement the complete solution including all platforms / paths / edge cases.

Never option 3 ("ship half now, finish later"). Half-built code rots. Future-you doesn't remember the limitations. Reviewers can't tell what's intentional vs incomplete. CI failures become normalized noise.

### Concrete examples that triggered this rule

- 2026-05-25 commit `10ebe20` (reverted in `d221e4e`): tried to comment out Windows + macOS-x86_64 matrix entries in `build-libdave.yml` to silence CI noise. **WRONG.** Either:
  - Build for those platforms properly (the conceptual goal — multi-platform support), OR
  - Decide officially we never ship those platforms + remove the entries (not comment) + remove the libdave CI complexity that supported them
  - "Comment out for now" was the forbidden middle ground.

### How to apply

1. Before any code-touching dispatch: **does this introduce temporary state?** If yes — REJECT, redesign.
2. Step 2 (architect design) reviews must explicitly call out any "v1 limitation" / "phase 2 follow-up" — if it exists, the design is incomplete; either deliver full or block.
3. Step 3 critic must flag any TODO / temporary / quick-fix vocabulary in code or design.
4. Code review (Step 7) rejects PRs containing forbidden patterns above.

### Acceptable exceptions

NONE. There is no "small" temporary. The rule is absolute.

If you THINK you need temporary code, you're missing a step in the pipeline — go back to Step 1 (architectural analysis) and find the conceptually-correct path.

---

## What Puklic IS

A lightweight native desktop chat client for Discord, built on Kotlin Multiplatform + Compose Multiplatform. Goal: an alternative to the Electron client focused on low RAM usage, **Linux desktop only** (Wayland-first), long-term stability. KMP scaffolding (Android, iOS modules) is kept for a future mobile roadmap phase but is not a current shipping target.

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

## Platforms

**Officially shipped:**
- Linux x86_64 desktop (.deb + .AppImage via Compose Desktop; .pkg.tar.zst via AUR)
- macOS arm64 desktop (.dmg via Compose Desktop, attached to GitHub Releases)

**Out of scope:**
- Windows desktop (any arch)
- macOS x86_64 (Intel Mac)
- Browser / web

Mobile (Android/iOS) — separate roadmap phase, KMP scaffolding ready.

Scope set 2026-05-25 (issue #22) and revised same day per user
"všechny platformy stejně" — macOS arm64 promoted from developer-side
to officially shipped, with the same version string as Linux (single
source of truth in `gradle.properties` → `puklic.version`). Re-adding
Windows or macOS x86_64 requires updating this section and
`docs/07_roadmap/phases.md` before any CI / Gradle change.

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
- **Shipping target:** Linux desktop x86_64 (Wayland via XWayland for now — native Wayland backend for Compose is not ready). See `## Platforms` above.
- **Future roadmap:** Android, iOS (Compose iOS — one UI codebase, see ADR-0001). KMP modules scaffolded, not actively shipped.
- **Voice/media:** separate module, on desktop via PipeWire, on iOS/Android via platform-native audio

---

## Links

- Product vision: `docs/00_overview/product-vision.md`
- ADR index: `docs/01_architecture/adr/`
- Roadmap: `docs/07_roadmap/phases.md`
