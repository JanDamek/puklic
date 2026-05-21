# CLAUDE.md — Puklic (repo-level)

Tento soubor **rozšiřuje** globální `~/.claude/CLAUDE.md`. Globální pravidla (HARD RULE #0 K8s subagenti, HARD RULE #1 TEST-FIRST pipeline, minimum-complexity, SOLID) platí beze zbytku.

---

## Co Puklic JE

Lehký nativní desktop/mobile chat klient pro Discord, postavený na Kotlin Multiplatform + Compose Multiplatform. Cíl: alternativa k Electron klientovi se zaměřením na nízkou spotřebu RAM, Wayland-first Linux, dlouhodobou stabilitu a budoucí mobilní platformy (Android, iOS).

## Co Puklic NENÍ

- ❌ Bot ani bot framework
- ❌ Self-bot / automatizace účtu (auto-reply, auto-react, scraping, bulk operations)
- ❌ AI agent / LLM integrace
- ❌ Plugin do Discordu
- ❌ Modifikace oficiálního klienta

Když nás napadne featura, která dává smysl jen pro automaty (scheduled messages, auto-translate, bulk delete), odpověď je **NE** — to je bot teritorium.

---

## Performance targety

| Metrika | Target |
|---|---|
| RAM idle (přihlášen, 1 guild) | < 150 MB |
| RAM aktivní (10 guilds, 5 channels cache) | < 300 MB |
| Cold start (Linux) | < 2 s |
| Desktop binary (s JVM) | < 80 MB |

Tyto targety platí pro fázi 1 MVP. Voice/screenshare může posunout RAM nahoru — bude vyhodnoceno ve fázi 3+.

---

## Architektonická pravidla (repo-specific)

Globální pravidla z `~/.claude/CLAUDE.md` plus:

1. **UI nesmí parsovat ani transformovat data.** Compose pouze renderuje hotový state. Rich text parsing patří do `:shared:chat-parser`.
2. **`:shared:*` moduly nesmí znát platformu.** Žádné přímé volání AWT, JVM-only API, Wayland, PipeWire. Platform-specific kód jen přes `expect/actual` v `:shared:platform-api`.
3. **Discord DTO nesmí proniknout do UI.** Vrstvy: `Discord DTO → Domain → Persistence → UI state → Compose`. Mapping mezi vrstvami je explicitní.
4. **Žádný globální coroutine scope.** Každý ViewModel / Repository / Session má vlastní scope s definovaným lifecycle.
5. **Žádný globální event bus.** Streamy přes `StateFlow` / `SharedFlow` / `Channel` s jasným vlastníkem.
6. **Cache je vždy bounded.** Žádný unbounded message buffer, žádné attachmenty v RAM.

---

## Dokumentace — povinný workflow

Adresář `docs/` je **single source of truth** pro architekturu a doménový model. Struktura:

```
docs/
├── 00_overview/        # Vize, glossary
├── 01_architecture/    # ADR, module map, data flow, threading
├── 02_domain/          # Chat model, RichText AST, Discord protokol
├── 03_infrastructure/  # Persistence, cache, platform abstrakce, architect reports
├── 04_ui/              # Design system, screen inventory
├── 05_platforms/       # Linux/Wayland, Android, iOS specifika
├── 06_ops/             # Build, CI, release
└── 07_roadmap/         # Fáze 1–5
```

**Pravidlo:** každá architektonická nebo doménová změna povinně updatuje minimálně jeden soubor v `docs/` v **témž commitu** jako kód. PR bez doc updatu = nemerguje se.

Architect subagent reporty: `docs/03_infrastructure/architect-reports/<YYYY-MM-DD>-<slug>.md`.

---

## Discord protokol — risk acknowledgement

Discord ToS zakazuje third-party user klienty. Puklic je tolerován pouze dokud:
- neautomatizuje účet (žádné self-bot funkce, viz „Co Puklic NENÍ")
- chová se jako reálný uživatel (heartbeat timing, presence, typing)
- neimplementuje detection-evasion ani crypto wrappery nad oficiálním protokolem (DAVE bude implementován dle veřejné spec, ne reverse-engineered)

Riziko banu účtu nese uživatel. README projektu toto musí explicitně uvádět.

---

## Build & platformy

- **Build:** Gradle multimodule, Compose Multiplatform
- **Primární platforma fáze 1:** Linux desktop (Wayland přes XWayland zatím — nativní Wayland backend Compose není ready)
- **Fáze 2+:** Android, iOS (Compose iOS — jeden UI codebase, viz ADR-0001)
- **Voice/media:** samostatný modul, na desktop přes PipeWire, na iOS/Android přes platform-native audio

---

## Odkazy

- Produktová vize: `docs/00_overview/product-vision.md`
- ADR index: `docs/01_architecture/adr/`
- Roadmap: `docs/07_roadmap/phases.md`
