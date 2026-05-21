# ADR-0005: Decompose jako navigační knihovna

- **Status:** accepted
- **Date:** 2026-05-21
- **Deciders:** Jan Damek

## Context

Puklic potřebuje navigační knihovnu pro Compose Multiplatform, která zvládne tří-panelový adaptivní layout (guild rail | channel list | messages) — stejný pattern jako Discord desktop. Tento layout vyžaduje:

- Nezávislý back-stack pro každý panel (ne globální stack)
- Přepínání mezi `SINGLE` / `DUAL` / `TRIPLE` zobrazením podle window size class
- Fungující state restoration při Android process death
- Produkční stabilitu na JVM desktop + Android od Phase 1; iOS (Kotlin/Native) od Phase 2

Rozhodnutí je označeno jako "nejzávažnější" v setupu, protože navigační knihovna tvaruje lifecycle každého ViewModelu, ComponentContextu a scope v celém `:shared:compose-ui`.

## Options considered

### Option A — Decompose 3.x

Arkivanov Decompose je KMP navigační framework orientovaný na component-based architekturu.

**Pros:**
- `ChildPanels` API přímo modeluje tří-panelový layout jako `SINGLE` / `DUAL` / `TRIPLE` — přesná shoda s adaptive-layouts.md
- Per-panel `ComponentContext` s vlastním back-stackem (`ChildStack`)
- `instanceKeeper` + `StateKeeper` pro Android process death restoration
- Produkční nasazení: Discord-like aplikace (třetí strany), Kotlin KMP showcase projekty
- Aktivní vývoj a podpora (Arkivanov, 2024–2026)
- Přirozené místo pro ViewModely: Decompose component IS ViewModel — bez ViewModelFactory/ViewModel lifecycle kolizí

**Cons:**
- Větší API surface než Voyager / Compose Navigation
- Learning curve: `ComponentContext`, `ChildStack`, `ChildPanels` jsou nové koncepty
- Nutnost ručního DI do komponent (Koin constructor injection) — žádný `koinViewModel()` shortcut bez boilerplate

### Option B — Voyager

Café Bazaar Voyager je jednoduchá KMP navigace orientovaná na screen stack.

**Pros:**
- Jednodušší API: `Navigator`, `Screen`, push/pop
- Dobrá KMP podpora (Desktop + Android + iOS)

**Cons:**
- Žádný `ChildPanels` ekvivalent — tří-panelový layout by vyžadoval custom navigation coordinator
- Globální stack, ne per-panel — back-stack logika musela by být implementována ručně
- Desktop je sekundární platforma pro Voyager (Android-first)

### Option C — Compose Navigation (Jetpack)

Jetpack Navigation Compose je Android-first navigace portovaná na KMP.

**Pros:**
- Velká komunita (Android ekosystém)
- Native Android deep link podpora
- `ViewModel` integrace (Android Jetpack)

**Cons:**
- KMP podpora je mladá (přidána ~2024), Desktop má mezery v 2026
- Žádný `ChildPanels` ekvivalent
- Globální single-stack — tří-panelový layout není přímou abstrakcí
- Android-centric design: iOS a Desktop jsou second-class citizens

### Option D — Vlastní navigace

Implementace custom navigation coordinatoru bez knihovny třetích stran.

**Pros:**
- Plná kontrola
- Žádná závislost na třetí straně

**Cons:**
- Reimplementuje přesně to, co Decompose nabízí (`ChildPanels`, `ChildStack`, lifecycle)
- Vysoké náklady na vývoj a údržbu
- Validity pouze pokud žádná knihovna nepokrývá potřebu — Decompose ji pokrývá plně

## Decision

**Option A — Decompose 3.x.**

Důvod: `ChildPanels` API je přímou abstrakcí tří-panelového layoutu vyžadovaného v adaptive-layouts.md. Žádná jiná KMP knihovna tuto abstrakci nenabízí — alternativy by vyžadovaly custom implementaci srovnatelné složitosti. Decompose má produkční track record na Desktop + Android, a iOS podpora (Kotlin/Native) je dostupná pro Phase 2.

## Consequences

- ✅ `:shared:compose-ui` obsahuje Decompose `RootComponent` + `ChildPanels` pro tří-panelový layout
- ✅ ViewModely žijí v `:shared:compose-ui` jako Decompose komponenty (presentation layer), ne v `:shared:repositories` (data layer)
- ✅ `ComponentContext` je lifecycle owner každého ViewModelu — přirozené zrušení coroutinů při navigaci pryč z obrazovky (ADR-0004)
- ✅ Android process death restoration: `instanceKeeper` + `StateKeeper` zabudovány do Decompose
- ⚠️ Decompose API je větší — engineer potřebuje přečíst dokumentaci před implementací `:shared:compose-ui`
- ⚠️ Koin + Decompose: nepoužívat `koinViewModel()`, místo toho constructor injection do Decompose komponent
- 🔒 Pin Decompose na verzi `3.3.0` v `libs.versions.toml`; update pouze po ověření `ChildPanels` stability na všech platformách
- 🔒 `ChildPanels` pochází z `decompose` knihovny (ne z `compose-material3-adaptive`, která poskytuje `ThreePaneScaffold`)

## Related

- ADR-0001: Compose Multiplatform jako jednotná UI vrstva
- ADR-0004: Coroutine-first architektura (ComponentContext lifecycle + coroutine scopes)
- `docs/04_ui/adaptive-layouts.md` — tří-panelový layout spec
- Spec: `docs/03_infrastructure/architect-reports/2026-05-21-gradle-setup.md` §Q4
