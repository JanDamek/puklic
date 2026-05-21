# Module map

Gradle multimodule layout. **Draft** — finální struktura projde architect subagent review před `gradle init`.

## Kotlin Multiplatform topology

Puklic je Gradle multi-project build s Kotlin Multiplatform (KMP) projekty. Většina modulů má targets `[jvm, android, iosArm64, iosX64, iosSimulatorArm64]`; některé jen JVM (desktop).

## Modulový strom

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
│   ├── media-api/                    # :shared:media-api  (fáze 3)
│   └── compose-ui/                   # :shared:compose-ui
│
├── desktop/
│   ├── app/                          # :desktop:app
│   ├── platform-linux/               # :desktop:platform-linux
│   ├── platform-macos/               # :desktop:platform-macos     (stub fáze 1)
│   ├── platform-windows/             # :desktop:platform-windows   (stub fáze 1)
│   ├── media-pipewire/               # :desktop:media-pipewire     (fáze 3)
│   └── media-portal/                 # :desktop:media-portal       (fáze 4, xdg-desktop-portal)
│
├── android/
│   ├── app/                          # :android:app                (fáze 2)
│   └── platform/                     # :android:platform           (fáze 2)
│
├── ios/
│   ├── app/                          # :ios:app                    (fáze 2/3)
│   └── platform/                     # :ios:platform               (fáze 2/3)
│
└── tools/
    └── parser-fixtures-gen/          # :tools:parser-fixtures-gen  (CLI tool)
```

## Per-modul popis

### `:shared:ids`

- **Účel:** Type-safe ID value classes (UserId, GuildId, ChannelId, ...)
- **Targets:** JVM, Android, iOS
- **Závislosti:** kotlinx-datetime (jen pro Snowflake → Instant extension)
- **Závisí na:** nic

### `:shared:domain`

- **Účel:** Doménové typy (ChatMessage, Guild, Channel, UserSummary, ...). Pure data classes.
- **Targets:** JVM, Android, iOS
- **Závislosti:** kotlinx-datetime, kotlinx-serialization (pro Json serialization annotations)
- **Závisí na:** `:shared:ids`

### `:shared:platform-api`

- **Účel:** `expect` interfaces pro SecureStorage, NotificationService, atd.
- **Targets:** JVM, Android, iOS
- **Závislosti:** kotlinx-coroutines
- **Závisí na:** nic

### `:shared:chat-parser`

- **Účel:** RichText AST parser (raw String → RichTextDocument). Pure functions.
- **Targets:** JVM, Android, iOS
- **Závislosti:** kotlinx-datetime
- **Závisí na:** `:shared:domain`, `:shared:ids`

### `:shared:protocol-discord`

- **Účel:** Discord DTO, JSON serialization, mappers do `:shared:domain`. Gateway + REST low-level client.
- **Targets:** JVM, Android, iOS
- **Závislosti:** Ktor Client (CIO / Darwin engine per platform), kotlinx-serialization, kotlinx-coroutines
- **Závisí na:** `:shared:domain`, `:shared:ids`

### `:shared:persistence-api`

- **Účel:** Repository interfaces, SQLDelight schema (`.sq` files) deklarace
- **Targets:** JVM, Android, iOS
- **Závislosti:** SQLDelight runtime, kotlinx-coroutines
- **Závisí na:** `:shared:domain`, `:shared:ids`

### `:shared:persistence-sqldelight`

- **Účel:** SQLDelight generated code + per-platform driver wiring
- **Targets:** JVM (SQLite JDBC), Android (Android SQLite), iOS (Native SQLite)
- **Závisí na:** `:shared:persistence-api`, `:shared:platform-api`

### `:shared:repositories`

- **Účel:** Concrete `MessageRepository`, `GuildRepository`, ... — wiring Discord protokol + persistence + RAM cache
- **Targets:** JVM, Android, iOS
- **Závisí na:** `:shared:protocol-discord`, `:shared:persistence-api`, `:shared:chat-parser`

### `:shared:session`

- **Účel:** `DiscordSession` — top-level session lifecycle, gateway connect/resume, state machine
- **Targets:** JVM, Android, iOS
- **Závisí na:** `:shared:protocol-discord`, `:shared:repositories`, `:shared:platform-api`

### `:shared:media-api` (fáze 3)

- **Účel:** `expect` audio capture/playback, video capture interfaces
- **Závisí na:** `:shared:platform-api`

### `:shared:compose-ui`

- **Účel:** Compose Composables sdílené přes platformy (chat list, message bubble, RichText renderer, settings dialogy)
- **Targets:** JVM, Android, iOS
- **Závislosti:** Compose Multiplatform, Coil, Decompose (routing) / Voyager?
- **Závisí na:** `:shared:domain`, `:shared:repositories`, `:shared:platform-api`

### `:desktop:app`

- **Účel:** Desktop entry point — `main()`, top-level window, DI wiring, runtime configuration
- **Targets:** JVM
- **Závislosti:** Compose Desktop, Koin (nebo manual DI)
- **Závisí na:** `:shared:compose-ui`, `:shared:session`, jeden z `:desktop:platform-*` (per OS detection)

### `:desktop:platform-linux`

- **Účel:** Linux `actual` implementace `:shared:platform-api` — libsecret, D-Bus notifications, libayatana tray, xdg-open
- **Targets:** JVM
- **Závislosti:** JNA, dbus-java
- **Závisí na:** `:shared:platform-api`

### `:desktop:platform-macos` / `:desktop:platform-windows`

- Stub ve fázi 1 (jen base path / clipboard). Plná implementace fáze 2+.

### `:desktop:media-pipewire` (fáze 3)

- **Účel:** Linux audio capture/playback přes PipeWire
- **Závisí na:** `:shared:media-api`, `:desktop:platform-linux`

### `:desktop:media-portal` (fáze 4)

- **Účel:** Wayland screenshare přes xdg-desktop-portal + PipeWire video stream
- **Závisí na:** `:shared:media-api`, `:desktop:platform-linux`, `:desktop:media-pipewire`

### `:android:app`

- **Účel:** Android entry point — Application, MainActivity, Compose host
- **Targets:** Android
- **Závisí na:** `:shared:compose-ui`, `:shared:session`, `:android:platform`

### `:android:platform`

- **Účel:** Android `actual` implementace `:shared:platform-api`
- **Závisí na:** `:shared:platform-api`

### `:ios:app`

- **Účel:** iOS entry point — `UIApplicationMain`, Compose iOS host
- **Targets:** iOS
- **Závisí na:** `:shared:compose-ui`, `:shared:session`, `:ios:platform`

### `:ios:platform`

- **Účel:** iOS `actual` implementace `:shared:platform-api`
- **Závisí na:** `:shared:platform-api`

### `:tools:parser-fixtures-gen`

- **Účel:** CLI tool pro generování parser test fixtures z reálných Discord zpráv (sanitized)
- **Targets:** JVM
- **Závisí na:** `:shared:chat-parser`

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

- **Kotlin:** 2.x (latest stable při startu)
- **Compose Multiplatform:** latest stable
- **JVM target:** 17 (Compose Desktop requirement)
- **Android minSdk:** 26 (Oreo, ~98 % coverage)
- **Android targetSdk:** latest
- **iOS deployment target:** 14.0+
- **Gradle:** 8.x s Version Catalog (`gradle/libs.versions.toml`)
- **JDK pro build:** 21 (toolchain)

## Build conventions

- Gradle convention plugins v `buildSrc/` nebo `build-logic/` (TBD — ADR později)
- Jeden `KotlinMultiplatformExtension` setup per typový modul (shared multiplatform vs desktop-only)
- ktlint + detekt jako pre-commit a CI gate
- `ktfmt` nebo `ktlint --format` jako formatter
- KMP source sets: `commonMain`, `commonTest`, `jvmMain`, `androidMain`, `iosMain` (s shared `iosMain` přes hierarchy template)

## Open questions (k diskuzi s architect subagentem)

1. **Compose for `:shared:compose-ui`?** Compose iOS je beta. Možnost: ponechat Compose code v `:desktop:compose-ui` ve fázi 1, sdílet až ve fázi 2 při startu mobile. Snižuje risk.
2. **Navigation:** Decompose vs Voyager vs vlastní. Decompose má lepší KMP support, Voyager hezčí DSL.
3. **DI:** Koin (multiplatform, runtime) vs manual constructor injection. Pro malou app stačí manual.
4. **Image loading:** Coil vs Compose Multiplatform image loader. Coil 3.x má MPP support.
5. **WebSocket on iOS:** Ktor Client iOS engine používá NSURLSession — websocket podpora je novější, ověřit stabilitu.
