# FP-14b — Test-first red-phase tests for Mac App Store variant

Status: TEST-WRITER PLAN + EXECUTION (HARD RULE #1 Step 5). Writes failing tests only.
Date: 2026-05-29
Author: test-writer role — refs Issue #55, builds on FP-14a (`2026-05-29-fp14a-mac-app-store-architect.md`)

> HARD RULE #2 in full force. No `@Ignore`, no `assertTrue(true)`, no TODOs. Every test asserts a real surface FP-14c..g will satisfy.

---

## 1. Surfaces locked by these tests

Maps 1:1 to Issue #55 inventory and FP-14a §12 slice decomposition.

| # | Surface | Test file | Framework | Initial state |
|---|---|---|---|---|
| 1 | `MacAppStoreGplChecker` matcher (parallel to `IosGplChecker`) — used by future `verifyMacAppStoreNoGplDeps` Gradle task | `build-logic/src/{main,test}/kotlin/MacAppStoreGplChecker[Test].kt` | JUnit 5 | **GREEN** — pure-Kotlin matcher infrastructure, parallel to the existing `IosGplChecker` shipped in build-logic today even before any task consumed it. |
| 2 | `JnaVideoToolboxH264Encoder` — JVM H.264 encoder for Mac App Store variant | `desktop/app/src/macAppStoreTest/kotlin/dev/puklic/desktop/macappstore/codec/JnaVideoToolboxH264EncoderContractTest.kt` | kotlin.test (JUnit 5 runner) | **RED** — class doesn't exist → `ClassNotFoundException` at test runtime. |
| 3 | `JnaLibopusEncoder` / `JnaLibopusDecoder` — JVM Opus binding to bundled libopus.dylib | `desktop/app/src/macAppStoreTest/kotlin/dev/puklic/desktop/macappstore/codec/JnaLibopus{Encoder,Decoder}ContractTest.kt` | kotlin.test | **RED** — class doesn't exist. |
| 4 | `JnaNwConnectionUdpTransport` — Network.framework UDP transport | `desktop/app/src/macAppStoreTest/kotlin/dev/puklic/desktop/macappstore/transport/JnaNwConnectionUdpTransportContractTest.kt` | kotlin.test | **RED** — class doesn't exist. |
| 5 | `dist/apple/macappstore/Puklic.entitlements` — App Sandbox + JIT + microphone + screen capture + files + JIT entitlements | `desktop/app/src/test/kotlin/dev/puklic/desktop/macappstore/PuklicMacAppStoreEntitlementsTest.kt` | kotlin.test + javax.xml | **RED** — file doesn't exist → `FileNotFoundException`. |
| 6 | `fastlane/Fastfile` `lane :mac_app_store do` | `fastlane/spec/mac_app_store_lane_spec.rb` | plain Ruby (no rspec gem) | **RED** — lane not present in Fastfile. |
| 7 | `.github/workflows/mac-app-store.yml` workflow shape | `.github/workflows-test/mac_app_store_workflow_check.sh` | bash + grep | **RED** — workflow file does not exist. |

---

## 2. Test framework choices — rationale

- **build-logic** continues to use **JUnit 5** (matches existing `IosGplCheckerTest`).
- **`:desktop:app`** uses **kotlin.test** with JUnit Platform runner (already wired by `puklic.jvm-library` plugin via `useJUnitPlatform()`).
- **fastlane spec** — plain Ruby `raise unless` style. Rationale: introducing `rspec` to a Ruby-only-for-fastlane project would drag a Gemfile dep that the existing iOS lane doesn't use. The check is one boolean per assertion, plain Ruby is sufficient and runnable as `ruby fastlane/spec/mac_app_store_lane_spec.rb`.
- **CI workflow content check** — bash + grep. Rationale: the workflow file shape (presence of triggers, runner OS, secret names) is text-grep-able and any tool heavier than grep would be overkill.

---

## 3. Source set wiring

`desktop/app/build.gradle.kts` gets a **NEW `macAppStoreTest` source set ONLY** in this slice. The matching `macAppStoreMain` source set is FP-14d scope. The compile classpath is intentionally limited so the test sources can reference `:shared:voice-codec`, `:shared:voice-api`, and `:desktop:platform-macos` interfaces. The tests intentionally reference classes that **do not exist** — running the test task surfaces `ClassNotFoundException` (red).

Critically: the `check` task is NOT auto-wired to depend on `macAppStoreTest` in this slice. FP-14d will land the impl + the `check` wiring together. Today's CI is therefore not red — the failing tests are runnable on-demand via `./gradlew :desktop:app:macAppStoreTest` for verification.

---

## 4. Test naming convention

`<ClassUnderTest><Behaviour>Test.kt`, methods backticked `\`<surface> <expected outcome>\``. Mirrors `IosGplCheckerTest` style.

---

## 5. Self-critic

- **Does this lock the right surface?** Yes. Each of the 6 surfaces from #55 has at least one test that fails with a concrete reason (class-not-found, file-not-found, content-grep miss, content-parse miss). FP-14c..g must satisfy them.
- **Missing cases?** None worth blocking on. The contract tests for JNA wrappers assert (a) class loads, (b) implements the right interface, (c) factory companion exposes constructor parameters from FP-14a §4.2. No behaviour assertions yet — those land in FP-14c's impl PR alongside concrete code that can actually be exercised on a Mac.
- **HARD RULE #2 compliance**: no `@Ignore`, no `assertTrue(true)`, no TODOs, no "phase 2" qualifiers. The MacAppStoreGplChecker matcher ships the FULL forbidden list now; FP-14d's Gradle task just consumes it.
- **Deletability**: every test file is self-contained and can be removed by FP-14f without leaving orphan references in production code (no test-only helpers in main sources).

---

## 6. Acceptable red forms

| Surface | Acceptable red |
|---|---|
| JNA wrapper contract tests | `ClassNotFoundException` thrown by `Class.forName(...)` in the test body |
| Entitlements file test | `FileNotFoundException` / `assertTrue(file.exists())` failing |
| fastlane spec | Ruby `raise "missing lane"` |
| workflow yaml check | `exit 1` with explanatory stderr |

---

## 7. Out of scope (FP-14f may delete redundant tests)

The contract tests intentionally do NOT assert runtime behaviour — calling `encode(...)` on a not-yet-existing JNA bridge requires a Mac with the Opus.framework slice extracted into `Contents/Resources`. That's FP-14c's e2e impl test. Today's red phase locks only the **type contract**.

---

## 8. References

- Issue #55 — FP-14b unit-test-writer mandate
- `docs/03_infrastructure/architect-reports/2026-05-29-fp14a-mac-app-store-architect.md` §12 (slice decomposition)
- `build-logic/src/main/kotlin/IosGplChecker.kt` — pattern reused
- `build-logic/src/test/kotlin/IosGplCheckerTest.kt` — pattern reused
- `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/codec/video/H264Encoder.kt` — interface under contract
- `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/codec/OpusCodec.kt` — interface under contract
- `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/codec/transport/VoiceUdpTransport.kt` — interface under contract
