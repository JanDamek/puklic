# CLAUDE.md — Puklic (repo-level)

This file **extends** the global `~/.claude/CLAUDE.md`. Global rules (HARD RULE #0 **LOCAL ONLY** since 2026-05-22 — no K8s subagent dispatches, HARD RULE #1 TEST-FIRST pipeline, minimum-complexity, SOLID) apply in full.

---

## HARD RULE — Bug triage: low-friction reporting, request details via comments (2026-06-01)

User explicit 2026-06-01: **"Github formulář pro hlášení chyb optimalizuj na mnohem jednoduchší verzi. povinný jen title a description. verze defaul last předvplň. pak to teprve doplníme, takto to potřebujeme co nejjedoduchší pro zadavání chyb."** + *"pokud pak nebudeš vědět doplníš koment, request o informace."*

### Princip

Bariéra pro hlášení bugu = minimum. Detailní info dotahujeme až když je potřeba, formou žádosti v komentech.

### Required v `bug_report.yml`

- **Title** (GitHub default)
- **Popis chyby** (textarea)
- **Verze** (pre-filled na aktuální release, optional)

To je vše. Žádný platform dropdown, žádné Steps to reproduce, žádné Logs jako required field — vše doplníme reaktivně.

### Workflow když přijde issue s minimum info

Když dostanu issue jen s popisem + bez detailů potřebných pro fix:
1. **Nezavírej "needs more info" a neignoruj** — popis je z definice dostatečný k otevření issue
2. **Komentem si vyžádej konkrétní detaily** které pro fix opravdu potřebuju. Příklady:
   - "Pošli prosím verzi a platformu (iOS / macOS / Linux)."
   - "Můžeš poslat screenshot / stack trace z TestFlight crashů?"
   - "Jak často to padá? Při startu, při kliknutí na X?"
   - "Token přes paste tlačítko, nebo přes systémové menu?"
3. **Label `needs-info`** přidat dokud user nedoplní
4. Po doplnění continue s pipeline (Step 1 architectural analysis...)

### Default version sync

`release-all.sh` po každém pushnutí tagu **automaticky updatne `value: "X.Y.Z"`** v `.github/ISSUE_TEMPLATE/bug_report.yml` aby pre-fill seděl s aktuální release. Manuální sync je drift waiting to happen.

---

## HARD RULE #3 — UX/UI design needs explicit user approval BEFORE implementation (2026-05-29)

User explicit 2026-05-29: **"UX návrhy a schválování implementace chci vidět. v pipeline pokud bude UX design, tak mě to předem zobraz pro schválení!"**

Translation: UX designs and implementation approvals must be shown to the user. Any pipeline slice that contains a UX/UI design step MUST surface the design (mockup, wireframe, layout description, screen flow) to the user via `AskUserQuestion` (preferably with `preview:` ASCII mockups) **before** any implementation subagent is dispatched.

### When this rule fires

Any work that touches:
- `:shared:compose-ui/**` (new screens, screen layout changes, navigation flow, new components surface)
- New Composable functions that own a screen, section or modal
- Theming / colour tokens / typography
- Onboarding / login flow visual changes
- Settings screen additions or restructuring
- Any new dialog, sheet, popup, toast or banner
- Component reuse decisions that affect the visible result (e.g. "use the existing MessageBubble vs build a new one")

Does NOT fire for:
- Pure data-layer / persistence / network / codec work (no visible UI change)
- Bug fixes that restore intended behaviour without changing the visual contract
- Refactors that produce identical output (rename, file move, expect/actual extraction)
- Logging / observability changes
- CI / build / packaging changes

### How to apply

The Step 2 architect report for any UI-touching slice MUST:
1. Include an explicit **UX design section** before any code-design discussion. Use ASCII mockups, screen flow diagrams, or a clear textual description of the change (what the user sees, where, when, why).
2. End with an `AskUserQuestion` call summarising the UX choice and offering 2-4 concrete options (recommended option first, marked "Recommended"). Use the `preview` field on options when comparing layouts.
3. Wait for user approval before dispatching any code-writing subagent. Step 4 (user approval) blanket pre-approvals from prior macros do NOT cover UX decisions — they need fresh explicit approval each time.
4. The architect report records the chosen option + reasoning. Implementation subagents read the architect report at Step 1 and treat the locked UX as a hard contract.

### Forbidden

- ❌ Dispatching an implementation subagent for UI work before the user has explicitly chosen the UX direction
- ❌ "Let me just implement the obvious choice" — even when the choice seems obvious, ask first
- ❌ Showing the user code instead of a mockup ("here's the Composable I plan to write" is not the same as "here's what the screen will look like")
- ❌ Combining multiple UX decisions into a single yes/no question — always offer concrete alternatives

### Why

The user has aesthetic ownership of the product. Code review can fix a bug; aesthetic correction after impl is wasted code + visual debt.

---

## HARD RULE #4 — Apple distribution is LOCAL ONLY (2026-05-31)

User explicit 2026-05-31: **"Na Apple store nebude nikdy workflow, buildime vždy lokálně a nasazujeme jen z tohoto macu nebo z jiného, ale jen my. Nikdy GitHub !!!. Nic z tohoo se tam ukládáat nebude."**

No GitHub Actions workflow may build or upload to App Store Connect, ever. No Apple credential (.p8, .p12, .mobileprovision, .provisionprofile) may be added as a GitHub Secret. Apple builds + uploads happen exclusively on the developer's Mac via the `dist/apple/*.sh` scripts.

AUR distribution may stay on GitHub Actions because the AUR pipeline carries no Apple credentials.

### Forbidden

- ❌ `.github/workflows/apple-*.yml`
- ❌ `.github/workflows/mac-app-store.yml`
- ❌ GitHub Secrets matching `ASC_KEY_*`, `APPLE_DIST_*`, `MAC_APP_DIST_*`, `MAC_INSTALLER_DIST_*`, `MAC_PROVISIONING_*`, `APPLE_PROVISIONING_*`
- ❌ Any fastlane lane that uploads from a non-local context

### Allowed

- ✅ `dist/apple/*.sh` scripts invoked from a developer Mac with keychain identities `Apple Distribution: Jan Damek (GR74KSG8M9)`, `3rd Party Mac Developer Application: Jan Damek (GR74KSG8M9)`, `3rd Party Mac Developer Installer: Jan Damek (GR74KSG8M9)` installed
- ✅ ASC API key file at `~/.appstoreconnect/private_keys/AuthKey_<KID>.p8`
- ✅ Provisioning profiles in `~/Library/MobileDevice/Provisioning Profiles/`
- ✅ `.github/workflows/aur-publish.yml` (no Apple credentials)

### Reference

`docs/03_infrastructure/architect-reports/2026-05-31-apple-local-only.md`
`docs/06_ops/apple-release.md` — runbook

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

A lightweight native Discord client, built on Kotlin Multiplatform + Compose Multiplatform. Goal: an alternative to the Electron client focused on low RAM usage, native UX, long-term stability, **and full feature parity (voice, screen sharing, DAVE where licence allows) across every shipping platform — Linux, macOS, Windows desktop and iOS / iPadOS / Mac App Store**.

The "lightweight chat with voice + screencast" identity is non-negotiable. Any per-platform feature reduction is a defect, not a scope decision.

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

**Officially shipped, all with full feature set (voice + screen sharing; DAVE where licence allows):**

| Channel | Target | Distribution | Voice | Screencast | DAVE |
|---|---|---|---|---|---|
| Linux x86_64 | Compose Desktop | .deb + .AppImage (GitHub Releases) + .pkg.tar.zst (AUR) | ✅ PipeWire + libopus | ✅ xdg-desktop-portal + PipeWire + libx264/libvpx (GPL) | ✅ libdave (GPL) |
| macOS arm64 desktop | Compose Desktop | .dmg signed Developer ID (GitHub Releases) | ✅ AVAudioEngine + libopus | ✅ ScreenCaptureKit + libx264 (GPL) | ✅ libdave (GPL) |
| macOS arm64 Mac App Store | JVM Compose Desktop (`:desktop:app` `macAppStore` source set, FP-14 2026-05-29) | Mac App Store (.pkg via jpackage `--mac-app-store`) | ⚠ FP-14c codec primitives present (VideoToolbox + libopus + Network.framework via JNA); voice **ships as `NoOpVoiceClient` until FP-14h** wires `AppleNativeVoiceClient` | ⚠ Same status — ScreenCaptureKit + VideoToolbox primitives present, no client wired until FP-14h | ❌ ship without DAVE (Discord fallback to non-E2EE) |
| Windows x86_64 desktop | Compose Desktop | .exe / .msi (GitHub Releases) | ✅ WASAPI + libopus | ✅ Desktop Duplication + libx264 (GPL) | ✅ libdave (GPL) |
| iOS / iPadOS arm64 | Compose iOS via KMP framework | App Store (also runs on Apple Silicon Mac via Designed for iPad) | ⚠ FP-4..FP-6 codec primitives present (libopus + VideoToolbox + Network.framework UDP); voice **ships as `NoOpVoiceClient` until FP-14h** wires `AppleNativeVoiceClient` | ⚠ FP-11 + FP-12 ReplayKit Broadcast Extension + VideoToolbox primitives present, no client wired until FP-14h | ❌ ship without DAVE (Discord fallback) |

**Out of scope:**
- macOS x86_64 (Intel Mac) — Apple-Silicon-only Mac shipping; Intel users use the Compose Desktop .dmg if needed.
- Browser / web.
- Android — separate roadmap phase after iOS slices stabilise.

**App Store distribution rule:** any module reachable from `:ios:app` or the `:desktop:app` `macAppStore` source set MUST be Apache-2.0 / MIT / BSD (enforced by `verifyIosNoGplDeps` / `verifyMacAppStoreNoGplDeps`). GPL deps (libx264, libdave, FFmpeg) live in `:shared:voice` JVM-only impl; App Store builds use Apple-native equivalents (VideoToolbox, AudioToolbox, Network.framework, ScreenCaptureKit) — JNA-bridged in `:desktop:platform-macos-appstore` (FP-14c) and Kotlin/Native cinterop in `:shared:voice-codec` iosMain (FP-4..FP-6). **FP-14h follow-up** wires `AppleNativeVoiceClient` into both DI graphs; until then App Store builds ship `NoOpVoiceClient` and the codec primitives are dead code.

**DAVE strategy (decided 2026-05-29):** App Store builds (iOS + Mac App Store) ship without DAVE. Discord's voice protocol falls back to the standard xsalsa20_poly1305 RTP transport encryption (still secure on the wire, just no end-to-end key agreement). GPL desktop builds (Linux, macOS .dmg, Windows) keep libdave for full E2EE. Documented in `docs/03_infrastructure/architect-reports/2026-05-29-full-feature-parity.md` §3.

Re-adding macOS x86_64 / Android / Web requires updating this section and `docs/07_roadmap/phases.md` before any CI / Gradle change.

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
