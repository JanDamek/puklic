# Module map

Gradle multimodule layout. **Draft** — final structure will go through architect subagent review before `gradle init`.

## Kotlin Multiplatform topology

Puklic is a Gradle multi-project build with Kotlin Multiplatform (KMP) projects. Most modules have targets `[jvm, android, iosArm64, iosX64, iosSimulatorArm64]`; some are JVM-only (desktop).

## Module tree

```
puklic/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/
│   └── libs.versions.toml
│
├── shared/
│   ├── ids/                          # :shared:ids
│   ├── domain/                       # :shared:domain
│   ├── platform-api/                 # :shared:platform-api
│   ├── chat-parser/                  # :shared:chat-parser
│   ├── protocol-discord/             # :shared:protocol-discord
│   ├── persistence-api/              # :shared:persistence-api
│   ├── persistence-sqldelight/       # :shared:persistence-sqldelight
│   ├── repositories/                 # :shared:repositories
│   ├── session/                      # :shared:session
│   ├── media-api/                    # :shared:media-api  (Phase 3)
│   └── compose-ui/                   # :shared:compose-ui
│
├── desktop/
│   ├── app/                          # :desktop:app
│   ├── platform-linux/               # :desktop:platform-linux
│   ├── platform-macos/               # :desktop:platform-macos     (stub Phase 1)
│   ├── platform-windows/             # :desktop:platform-windows   (stub Phase 1)
│   ├── media-pipewire/               # :desktop:media-pipewire     (Phase 3)
│   └── media-portal/                 # :desktop:media-portal       (Phase 4, xdg-desktop-portal)
│
├── android/
│   ├── app/                          # :android:app                (Phase 2)
│   └── platform/                     # :android:platform           (Phase 2)
│
├── ios/
│   ├── app/                          # :ios:app                    (Phase 2/3)
│   └── platform/                     # :ios:platform               (Phase 2/3)
│
└── tools/
    └── parser-fixtures-gen/          # :tools:parser-fixtures-gen  (CLI tool)
```

## Per-module description

### `:shared:ids`

- **Purpose:** Type-safe ID value classes (UserId, GuildId, ChannelId, ...)
- **Targets:** JVM, Android, iOS
- **Dependencies:** kotlinx-datetime (only for Snowflake → Instant extension)
- **Depends on:** nothing

### `:shared:domain`

- **Purpose:** Domain types (ChatMessage, Guild, Channel, UserSummary, ...). Pure data classes.
- **Targets:** JVM, Android, iOS
- **Dependencies:** kotlinx-datetime, kotlinx-serialization (for Json serialization annotations)
- **Depends on:** `:shared:ids`

### `:shared:platform-api`

- **Purpose:** `expect` interfaces for SecureStorage, NotificationService, etc.
- **Targets:** JVM, Android, iOS
- **Dependencies:** kotlinx-coroutines
- **Depends on:** nothing

### `:shared:chat-parser`

- **Purpose:** RichText AST parser (raw String → RichTextDocument). Pure functions.
- **Targets:** JVM, Android, iOS
- **Dependencies:** kotlinx-datetime
- **Depends on:** `:shared:domain`, `:shared:ids`

### `:shared:protocol-discord`

- **Purpose:** Discord DTOs, JSON serialization, mappers to `:shared:domain`. Gateway + REST low-level client.
- **Targets:** JVM, Android, iOS
- **Dependencies:** Ktor Client (CIO / Darwin engine per platform), kotlinx-serialization, kotlinx-coroutines
- **Depends on:** `:shared:domain`, `:shared:ids`

### `:shared:persistence-api`

- **Purpose:** Repository interfaces, SQLDelight schema (`.sq` files) declarations
- **Targets:** JVM, Android, iOS
- **Dependencies:** SQLDelight runtime, kotlinx-coroutines
- **Depends on:** `:shared:domain`, `:shared:ids`

### `:shared:persistence-sqldelight`

- **Purpose:** SQLDelight generated code + per-platform driver wiring
- **Targets:** JVM (SQLite JDBC), Android (Android SQLite), iOS (Native SQLite)
- **Depends on:** `:shared:persistence-api`, `:shared:platform-api`

### `:shared:repositories`

- **Purpose:** Concrete `MessageRepository`, `GuildRepository`, ... — wiring Discord protocol + persistence + RAM cache
- **Targets:** JVM, Android, iOS
- **Depends on:** `:shared:protocol-discord`, `:shared:persistence-api`, `:shared:chat-parser`

### `:shared:session`

- **Purpose:** `DiscordSession` — top-level session lifecycle, gateway connect/resume, state machine
- **Targets:** JVM, Android, iOS
- **Depends on:** `:shared:protocol-discord`, `:shared:repositories`, `:shared:platform-api`

### `:shared:media-api` (Phase 3)

- **Purpose:** `expect` audio capture/playback, video capture interfaces
- **Depends on:** `:shared:platform-api`

### `:shared:compose-ui`

- **Purpose:** Compose Composables shared across platforms (chat list, message bubble, RichText renderer, settings dialogs)
- **Targets:** JVM, Android, iOS
- **Dependencies:** Compose Multiplatform, Coil, Decompose (routing)
- **Depends on:** `:shared:domain`, `:shared:repositories`, `:shared:platform-api`

### `:desktop:app`

- **Purpose:** Desktop entry point — `main()`, top-level window, DI wiring, runtime configuration
- **Targets:** JVM
- **Dependencies:** Compose Desktop, Koin (or manual DI)
- **Depends on:** `:shared:compose-ui`, `:shared:session`, one of `:desktop:platform-*` (per OS detection)

### `:desktop:platform-linux`

- **Purpose:** Linux `actual` implementations of `:shared:platform-api` — libsecret, D-Bus notifications, libayatana tray, xdg-open
- **Targets:** JVM
- **Dependencies:** JNA, dbus-java
- **Depends on:** `:shared:platform-api`

### `:desktop:platform-macos` / `:desktop:platform-windows`

- Stub in Phase 1 (base paths / clipboard only). Full implementation Phase 2+.

### `:desktop:media-pipewire` (Phase 3)

- **Purpose:** Linux audio capture/playback via PipeWire
- **Depends on:** `:shared:media-api`, `:desktop:platform-linux`

### `:desktop:media-portal` (Phase 4)

- **Purpose:** Wayland screenshare via xdg-desktop-portal + PipeWire video stream
- **Depends on:** `:shared:media-api`, `:desktop:platform-linux`, `:desktop:media-pipewire`

### `:android:app`

- **Purpose:** Android entry point — Application, MainActivity, Compose host
- **Targets:** Android
- **Depends on:** `:shared:compose-ui`, `:shared:session`, `:android:platform`

### `:android:platform`

- **Purpose:** Android `actual` implementations of `:shared:platform-api`
- **Depends on:** `:shared:platform-api`

### `:ios:app`

- **Purpose:** iOS entry point — `UIApplicationMain`, Compose iOS host
- **Targets:** iOS
- **Depends on:** `:shared:compose-ui`, `:shared:session`, `:ios:platform`

### `:ios:platform`

- **Purpose:** iOS `actual` implementations of `:shared:platform-api`
- **Depends on:** `:shared:platform-api`

### `:tools:parser-fixtures-gen`

- **Purpose:** CLI tool for generating parser test fixtures from real Discord messages (sanitized)
- **Targets:** JVM
- **Depends on:** `:shared:chat-parser`

## Dependency graph (ASCII)

```
ids ◄──── domain ◄──── chat-parser ◄────┐
              ▲              ▲           │
              │              │           │
              ├─ protocol-discord ◄──────┤
              │              │           │
              ├─ persistence-api ◄───────┤
              │              ▲           │
              │              │           │
              │     persistence-sqldelight
              │              ▲           │
              │              │           │
              └─── repositories ◄────────┤
                             ▲           │
                             │           │
                       session ◄─────────┤
                             ▲           │
                             │           │
                       compose-ui ◄──────┤
                             ▲           │
        ┌────────────────────┼────────┐  │
        │                    │        │  │
   :desktop:app        :android:app  :ios:app
        │                    │        │
   platform-linux      platform-and  platform-ios
        │                    │        │
        └──── platform-api ──┴────────┘
```

## Versioning & toolchain

- **Kotlin:** 2.x (latest stable at project start)
- **Compose Multiplatform:** latest stable
- **JVM target:** 17 (Compose Desktop requirement)
- **Android minSdk:** 26 (Oreo, ~98 % coverage)
- **Android targetSdk:** latest
- **iOS deployment target:** 14.0+
- **Gradle:** 8.x with Version Catalog (`gradle/libs.versions.toml`)
- **JDK for build:** 21 (toolchain)

## Build conventions

- Gradle convention plugins in `buildSrc/` or `build-logic/` (TBD — ADR later)
- One `KotlinMultiplatformExtension` setup per module type (shared multiplatform vs desktop-only)
- ktlint + detekt as pre-commit and CI gate
- `ktfmt` or `ktlint --format` as formatter
- KMP source sets: `commonMain`, `commonTest`, `jvmMain`, `androidMain`, `iosMain` (with shared `iosMain` via hierarchy template)

## Open questions (to discuss with architect subagent)

1. **Compose for `:shared:compose-ui`?** Compose iOS is beta. Option: keep Compose code in `:desktop:compose-ui` in Phase 1, share from Phase 2 when mobile starts. Reduces risk.
2. **Navigation:** Decompose vs Voyager vs custom. Decompose has better KMP support, Voyager a nicer DSL.
3. **DI:** Koin (multiplatform, runtime) vs manual constructor injection. Manual is sufficient for a small app.
4. **Image loading:** Coil vs Compose Multiplatform image loader. Coil 3.x has MPP support.
5. **WebSocket on iOS:** Ktor Client iOS engine uses NSURLSession — WebSocket support is newer, verify stability.
