# Changelog

All notable changes to Puklic will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Gradle scaffold (Steps 1–2):** Gradle 8.12 multimodule + Kotlin Multiplatform project scaffold
  - `build-logic/` included build with 7 convention plugins (`puklic.kmp-library`, `puklic.compose-library`, `puklic.ios-library`, `puklic.jvm-library`, `puklic.android-library`, `puklic.android-app`, `puklic.detekt`)
  - `settings.gradle.kts` with all 19 Phase 1 modules (shared, desktop, android, ios, tools)
  - `gradle/libs.versions.toml` — full version catalog (Kotlin 2.1.21, CMP 1.8.0, AGP 8.7.2, Ktor 3.1.3, SQLDelight 2.1.0, Koin 4.1.0, Decompose 3.3.0, Coil 3.1.0, etc.)
  - `gradle.properties` — performance flags, KMP config
  - Gradle wrapper 8.12
  - Per-module `build.gradle.kts` for all 19 modules with empty `src/` dirs
  - `detekt.yml` + `.editorconfig` for static analysis baseline
- Project architecture skeleton: documentation, ADRs, module map, roadmap
- Repository governance: license (Apache-2.0), contributing guide, code of conduct, security policy
- GitHub templates: issue forms, pull request template, dependabot
- UX foundation: design system, adaptive layouts (Compact/Medium/Expanded), screen inventory, component library, keyboard / gesture interactions
- Fixed UX decisions: three-pane Discord-style layout, compact density, dark-only theme for MVP, Material 3 baseline, round avatars, formatting-toolbar composer, full-screen settings overlay, collapsible channel categories, subtle Material animations, `Mod+K` command palette

[Unreleased]: https://github.com/JanDamek/puklic/commits/main
