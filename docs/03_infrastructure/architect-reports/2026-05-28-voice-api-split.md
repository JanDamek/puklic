# Voice API split — extract `:shared:voice-api` from `:shared:voice`

Status: ARCHITECT REPORT (Step 2). User pre-approved Step 4 in the Slice 2a
handoff (`.claude-handoff-2026-05-28.md`, see "BLOCKER awaiting user — voice-api
split"). Issue: GitHub #27.

Date: 2026-05-28
Author: pipeline orchestrator (this session)

## 1. Goal

Allow Apache-2.0 KMP modules (`:shared:session`, `:shared:compose-ui`) to depend
on the voice public types *without* pulling JVM-only GPL-3.0 implementation
(FFmpeg-GPL, libdave, JNA, javacpp, BouncyCastle). This unblocks Slice 2b
(iOS actuals in `:ios:platform`) and Slice 3 (`:ios:app` Compose iOS
framework), both of which would otherwise force GPL into the iOS classpath
and fail `verifyIosNoGplDeps`.

## 2. Conceptual shape

Two modules where there is currently one:

| Module | New role | License | Targets | Contains |
|---|---|---|---|---|
| `:shared:voice-api` (NEW) | Public types + policy only | Apache-2.0 | jvm + iosArm64 + iosX64 + iosSimulatorArm64 (KMP-wide via `puklic.kmp-library`) | The interfaces, sealed states, data classes, exceptions, no-op fallbacks, pure policy (`DaveDowngradeDetector`). No native deps. Depends only on `:shared:ids`, `:shared:domain`, kotlinx-coroutines-core. |
| `:shared:voice` (existing, slimmed) | JVM-only implementation | GPL-3.0 (FFmpeg-GPL + libdave + x264) | jvm only | `DefaultVoiceClient`, gateway, transport, crypto, codec (Opus via FFmpeg), audio (JavaSound), screenshare encoders/sources/linux portal, `MainGatewayBridge` interface (JVM-only because consumed by `DefaultVoiceClient`). Now `api(projects.shared.voiceApi)` so existing JVM consumers continue to see the public types transitively. |

## 3. Exact file moves (`git mv`, history-preserving)

From `shared/voice/src/commonMain/kotlin/dev/puklic/voice/` → `shared/voice-api/src/commonMain/kotlin/dev/puklic/voice/`:

- `PublicApi.kt` (contains `AudioConstants`, `AudioDevice`, `VoiceState`, `VoiceBusyException`, `IncomingVideoFrame`, `DaveUiState`, `IncomingCall`, `VoiceClient`)
- `NoOpVoiceClient.kt`
- `DaveDowngradeDetector.kt` (pure policy — only depends on `DaveUiState`; referenced doc-link from `:shared:compose-ui:DaveDowngradeBanner`)
- `screenshare/ScreenShareState.kt`
- `screenshare/ScreenShareClient.kt`
- `screenshare/ScreenSource.kt`
- `screenshare/NoOpScreenShareClient.kt`

From `shared/voice/src/commonTest/kotlin/dev/puklic/voice/` → `shared/voice-api/src/commonTest/kotlin/dev/puklic/voice/`:

- `DaveDowngradeDetectorTest.kt`

Files KEPT in `shared/voice/src/commonMain/` (because they have JVM-only siblings via package-private use):
- `pipeline/CapturePipeline.kt`, `crypto/AeadCipher.kt`, `transport/*` (Vp8Packetiser, H264Depacketizer, etc.), `codec/OpusCodec.kt`, `audio/{AudioPlayback,AudioCapture}.kt`, `gateway/*`.
  These are commonMain only because the module never enabled iOS targets; they are internal contracts for the JVM impl and not part of the UI-facing API.

No file moves needed in `jvmMain` — everything there stays.

## 4. Dependency-graph diff

`:shared:session` (`build.gradle.kts`):
```
- implementation(projects.shared.voice)
+ implementation(projects.shared.voiceApi)
```

`:shared:compose-ui` (`build.gradle.kts`):
```
- implementation(projects.shared.voice)
+ implementation(projects.shared.voiceApi)
```

`:desktop:app` (`build.gradle.kts`): unchanged — still depends on `:shared:voice` (the impl). Through `api(projects.shared.voiceApi)` re-export it will resolve the public types from the api module too, avoiding duplicate-type-on-classpath issues.

`:shared:voice` (`build.gradle.kts`):
```
+ commonMain.dependencies { api(projects.shared.voiceApi) }
```
(Note: `api` not `implementation` because `DefaultVoiceClient` must expose `VoiceClient` etc. in its public signatures.)

`settings.gradle.kts`:
```
+ include(":shared:voice-api")
```

## 5. New module skeleton

`shared/voice-api/build.gradle.kts`:
```kotlin
// :shared:voice-api — Apache-2.0 public voice/screenshare types.
//
// Apache-2.0 + KMP-wide (jvm + iOS) so that :shared:session and :shared:compose-ui
// can depend on voice types without pulling :shared:voice JVM-only GPL deps
// (FFmpeg-GPL, libdave, x264, JNA, javacpp). This module contains ONLY:
//   - public interfaces / sealed states / data classes
//   - no-op fallback implementations
//   - pure policy (DaveDowngradeDetector)
// It depends only on :shared:ids, :shared:domain, and kotlinx-coroutines-core.
// No native code, no JVM-only artifacts.
//
// See docs/03_infrastructure/architect-reports/2026-05-28-voice-api-split.md
// See docs/03_infrastructure/dep-policy.md

plugins {
    id("puklic.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.ids)
            implementation(projects.shared.domain)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotest.assertions.core)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
        }
    }
}

android {
    namespace = "dev.puklic.shared.voiceapi"
}
```

(Note: `puklic.kmp-library` already declares jvm + android + iosArm64 + iosX64 + iosSimulatorArm64. The android target is benign — `:shared:voice-api` won't be linked into any Android app yet, but the convention plugin makes the source-set hierarchy uniform.)

## 6. Critic pass (Step 3)

| Concern | Resolution |
|---|---|
| `AudioConstants`, `AudioDevice`, `IncomingVideoFrame` are not listed in handoff but live in `PublicApi.kt`. | They are referenced by the `VoiceClient` interface (`devices: StateFlow<List<AudioDevice>>`, `incomingVideo: StateFlow<Map<Int, IncomingVideoFrame>>`). Splitting them apart would break the interface. They move with `PublicApi.kt`. Conceptually-correct (no half-state). |
| `DaveDowngradeDetector` not listed in handoff. | Pure policy depending only on `DaveUiState`. Referenced from `:shared:compose-ui:DaveDowngradeBanner` (doc link only). Moving it keeps the "policy belongs with the type" principle. Tests move with it (commonTest). |
| `MainGatewayBridge` interface stays in `:shared:voice/jvmMain`. | It is consumed by `DefaultVoiceClient` (JVM-only) and produced by `MainGatewayBridgeAdapter` in `:desktop:app` (JVM-only). No KMP module needs it. Keep co-located with `DefaultVoiceClient`. |
| `:shared:voice-api` declares android target via convention plugin — does that pull anything GPL? | No. The module's commonMain deps are `:shared:ids`, `:shared:domain`, `kotlinx-coroutines-core` — all Apache-2.0. The convention plugin only adds Kotlin stdlib + AGP scaffolding. `verifyIosNoGplDeps` will continue to pass. |
| Re-export via `api(projects.shared.voiceApi)` from `:shared:voice` — does any caller bypass `:shared:voice-api` and break? | No: existing imports like `import dev.puklic.voice.VoiceClient` resolve identically because the fully-qualified Kotlin package is unchanged (`dev.puklic.voice`). The Gradle module changes but the FQN doesn't. |
| Could JNA / FFmpeg leak transitively through `:shared:voice` → `:shared:voice-api`? | No. The dep arrow is the other way: `:shared:voice` depends on `:shared:voice-api`, not vice versa. `:shared:voice-api` has only Apache-2.0 deps. |
| `commonTest` in `:shared:voice-api` — does the android target force an Android instrumented test? | No, the convention plugin uses `jvm()` for unit tests; android tests only run if `androidTest` source set has files (we add none). |

No findings requiring redesign. Proceed to Step 5 (tests-first) and Step 6 (impl).

## 7. Test plan (Step 5 — tests-first)

The existing `DaveDowngradeDetectorTest.kt` already covers `DaveDowngradeDetector` and `DaveUiState` from `commonMain` — it moves with the source file and is the contract test that the new `:shared:voice-api` `commonMain` types compile and behave correctly. No new tests are needed *for the extraction itself* — every test that previously passed in `:shared:voice:jvmTest` continues to pass against the JVM impl (which now transitively re-exports `:shared:voice-api`). The compile-contract for `:shared:compose-ui` commonTest (`MainViewModelDmCallTest.kt`, already imports `dev.puklic.voice.*`) verifies that the API surface is unchanged.

Step 9 build set:
```
./gradlew :shared:voice-api:build :shared:voice:build :shared:session:build \
          :shared:compose-ui:build :desktop:app:assemble :ios:app:verifyIosNoGplDeps
```

ALL must pass.

## 8. dep-policy.md update

Add a row to the build-target matrix:

| `:shared:voice-api` | Linked into desktop + iOS shared graph | Apache-2.0 only | KMP-wide public voice types. No native deps. |
| `:shared:voice` | Desktop only (impl) | Apache-2.0, MIT, BSD, GPL-3.0 | JVM-only voice impl. Forbidden in `:ios:app` graph (already covered by `verifyIosNoGplDeps`). |

## 9. Risk + rollback

- Risk: untouched test wiring elsewhere imports a type via the wrong gradle module. Mitigation: package name is unchanged (`dev.puklic.voice`), so existing source `import` statements continue to resolve. Only `build.gradle.kts` dependency edges change.
- Risk: someone adds a JVM-only dep to `:shared:voice-api` later, silently breaking iOS. Mitigation: `verifyIosNoGplDeps` runs on every `:ios:app:check` and would fail. Plus the architect report + dep-policy.md row codify the rule.
- Rollback: revert the commit — `git mv` history is preserved, the inverse is mechanical.

## 10. Done criteria (Step 11)

- All files moved with `git mv` (preserves blame).
- `:shared:voice-api:build`, `:shared:voice:build`, `:shared:session:build`, `:shared:compose-ui:build`, `:desktop:app:assemble`, `:ios:app:verifyIosNoGplDeps` all green.
- `docs/03_infrastructure/dep-policy.md` updated with the new module row.
- This report referenced from GitHub issue #27 closing comment.
- No TODO, no temporary state, no commented-out code (HARD RULE #2).
