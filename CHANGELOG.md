# Changelog

All notable changes to Puklic will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Phase 1 MVP scaffold complete (Steps 1–19):** end-to-end Discord chat client (Desktop). 374 unit tests green, `:ios:app` compiles for iosSimulatorArm64, Desktop smoke test launches LoginScreen without crash. Steps:
  1. Gradle multimodule + KMP scaffold
  2. Critic review + CRITICAL-1/2/3 scaffold fixes
  3. `:shared:ids` value classes (test-first)
  4. `:shared:domain` data structs + Snowflake helpers
  5. `:shared:platform-api` expect/actual contracts + fake test doubles
  6. `:shared:persistence-api` SQLDelight schema + DAOs
  7. `:shared:persistence-sqldelight` JVM JDBC driver + repos
  8. `:shared:chat-parser` RichText AST + Discord markdown subset (74-test suite)
  9. `:shared:protocol-discord` DTOs, mappers, REST + Gateway clients
  10. `:shared:repositories` Guild/Channel/User/Message orchestrators
  11. `:shared:session` `SessionStateMachine` (resume vs identify)
  12. `:desktop:platform-linux` + `:desktop:platform-macos` actuals (shell-out where needed)
  13. `:shared:compose-ui` Compose UI skeleton (LoginScreen, three-pane MainScreen)
  14. `:desktop:app` entry point + DI wiring
  15. Live gateway dispatch → repositories wiring
  16. UI binding StateFlow exposure
  17. `:ios:app` + `:ios:platform` + `:android:app` + `:android:platform` compile-only stubs
  18. `:tools:parser-fixtures-gen` CLI for golden fixtures
  19. Final verification: PREFER_SETTINGS repositories mode, `kotlin.jvm.JvmInline` import fix unblocking iOS, redundant per-module `repositories {}` blocks removed, Phase 1 completion report committed
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
