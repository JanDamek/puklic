# Puklic — Documentation

Single source of truth pro architekturu a doménový model. Každá architektonická / doménová změna povinně updatuje minimálně jeden soubor zde v témž commitu jako kód.

## Mapa

| Sekce | Obsah |
|---|---|
| [`00_overview/`](00_overview/) | Produktová vize, glossary |
| [`01_architecture/`](01_architecture/) | ADR, module map, data flow, threading model |
| [`02_domain/`](02_domain/) | Chat model, RichText AST, Discord protokol |
| [`03_infrastructure/`](03_infrastructure/) | Persistence schema, cache policy, platform abstrakce, architect-reports/ |
| [`04_ui/`](04_ui/) | Design system, screen inventory, component library |
| [`05_platforms/`](05_platforms/) | Linux/Wayland, Android, iOS specifika |
| [`06_ops/`](06_ops/) | Build, CI, release |
| [`07_roadmap/`](07_roadmap/) | Fáze 1–5 |

## Quick links

- [Produktová vize](00_overview/product-vision.md)
- [Glossary](00_overview/glossary.md)
- [ADR index](01_architecture/adr/README.md)
- [Module map](01_architecture/module-map.md)
- [Data flow](01_architecture/data-flow.md)
- [Threading model](01_architecture/threading-model.md)
- [Chat doménový model](02_domain/chat-model.md)
- [RichText AST + parser](02_domain/richtext-ast.md)
- [Discord protokol](02_domain/discord-protocol.md)
- [Persistence schema](03_infrastructure/persistence-schema.md)
- [Cache policy](03_infrastructure/cache-policy.md)
- [Platform abstractions](03_infrastructure/platform-abstractions.md)
- [UI / UX overview](04_ui/README.md) · [Design system](04_ui/design-system.md) · [Adaptive layouts](04_ui/adaptive-layouts.md) · [Screens](04_ui/screens.md) · [Component library](04_ui/component-library.md) · [Interactions](04_ui/interactions.md)
- [Linux/Wayland specifika](05_platforms/linux-wayland.md)
- [Android specifika](05_platforms/android.md)
- [iOS specifika](05_platforms/ios.md)
- [Build](06_ops/build.md) · [CI](06_ops/ci.md) · [Release](06_ops/release.md)
- [Roadmap](07_roadmap/phases.md)
- [Repo-level CLAUDE.md](../CLAUDE.md)
