# FP-14g — Documentation update closing the FP-14 cycle

Doc-updater role per HARD RULE #1 step 11. Closes issue #61.

## 1. Scope

Update documentation to reflect the FP-1..FP-14f-fix work landed on `main`
(commits 8978e6e..73a8922). Define FP-14h as the documented follow-up for
the deferred FP-14f critic findings (F-2, F-7..F-13, F-16..F-21).

This report does NOT change code, build configs, test files, or other
architect reports beyond the §3.6 SUPERSEDED note in
`2026-05-29-full-feature-parity.md`.

## 2. Files updated

| File | Change kind | Approx LOC |
|---|---|---|
| `docs/07_roadmap/phases.md` | Append Phase 6 Slices 13/14 status + FP-14h follow-up | +~35 |
| `docs/03_infrastructure/dep-policy.md` | Add `:desktop:platform-macos-appstore` row + `:desktop:app` macAppStore source-set row + verifyMacAppStoreNoGplDeps note | +~15 |
| `docs/03_infrastructure/architect-reports/2026-05-29-full-feature-parity.md` | §3.6 SUPERSEDED header; §7 slice plan annotated with commit refs + redirect note + FP-14h row | +~20 |
| `CLAUDE.md` (project root) | Voice/Screencast columns annotated "NoOpVoiceClient until FP-14h" on iOS + Mac App Store rows; reference to FP-14h | +~5 |
| `docs/03_infrastructure/architect-reports/2026-05-29-fp14g-docs-update.md` | This report (NEW) | +~90 |

KB store: one `mcp jervis-mcp__kb_store` finding under
`agent://claude-mcp/puklic/2026-05-29-fp14-cycle-complete`.

## 3. Slice-by-slice landed status (FP cycle)

| Slice | Commit | Issue | Status |
|---|---|---|---|
| FP-1 | `8978e6e` | #41 | [x] XSalsa20Poly1305Cipher + VoicePacketCodec extracted to `:shared:voice-codec` |
| FP-2 | `603f57d` | #42 | [x] H264Encoder/Decoder KMP interfaces + JVM bridge |
| FP-3 | `4706637` | #43 | [x] VoiceUdpTransport KMP interface + JVM bridge |
| FP-4 | `49f9854` | #44 | [x] iOS Opus via libopus 1.5.2 XCFramework |
| FP-5 | `7076c9c` | #45 | [x] iOS H264 via VideoToolbox |
| FP-6 | `e84ce88` | #46 | [x] iOS VoiceUdpTransport via Network.framework |
| FP-7 | `e9b0724` | #47 | [x] `:shared:screencast` extracted from `:shared:voice` |
| FP-8 | `918ed33` | #48 | [x] macOS ScreenCaptureKit via JNA |
| FP-9 | `042e76c` | #49 | [x] Windows IDXGIOutputDuplication + WASAPI loopback |
| FP-10 | `9ef0545` | #50 | [x] Windows platform actuals + Compose Desktop packaging + CI |
| FP-11 | `7a259c5` | #51 | [x] iOS ReplayKit Broadcast Extension + App Group IPC |
| FP-12 | `cbd80f7` | #52 | [x] iOS ReplayKit screencast impl |
| FP-13 | — | — | [-] BLOCKED → redirected. CMP 1.8 has no native macOS Compose UI runtime. JVM Compose Desktop path adopted in FP-14. |
| FP-14a | `9d183f1` | #54 | [x] Architect verification + library survey + jpackage probe |
| FP-14b | `f1651a0` | #55 | [x] Red-phase failing tests |
| FP-14c | `4d3eb38` | #56 | [x] VideoToolbox + libopus + Network.framework JNA wrappers |
| FP-14d | `1d5a53b` | #57 | [x] Gradle macAppStore source set + packageMacAppStore + entitlements |
| FP-14e | `01a0e30` | #58 | [x] fastlane mac_app_store lane + `.github/workflows/mac-app-store.yml` |
| FP-14f-critic | `334d26a` | #59 | [x] Critic findings filed (24 findings) |
| FP-14f-fix | `73a8922` | #60 | [x] F-1, F-3..F-6, F-14, F-15, F-22, F-23, F-24 fixed |
| FP-14g | (this) | #61 | [x] Doc closure |
| FP-14h | — | (new) | [ ] Follow-up: voice wiring + architectural concerns |

## 4. FP-14h follow-up scope

FP-14h is the documented follow-up slice that bundles all FP-14f critic
findings deferred out of FP-14f-fix:

- **F-2** — Wire a real `AppleNativeVoiceClient` into
  `MacAppStoreDependencyGraph` so the FP-14c codec wrappers (VideoToolbox,
  libopus, Network.framework UDP) actually receive data. Today both
  `:ios:app` and the `macAppStore` source set ship a `NoOpVoiceClient`;
  the FP-1..FP-12 native codec primitives are present on the classpath
  but dead.
- **F-7, F-8, F-9** — `JnaNwConnectionUdpTransport` lifetime: missing
  `nw_release` + dispatch queue release; `Block.Keepalive` not released
  across `close()`; `STATE_FAILED` closed-flag race.
- **F-10, F-11** — VideoToolbox encoder double-release on `close()`;
  decoder `lastFrame` cross-thread access without memory barrier.
- **F-12** — `JnaLibopusEncoder/Decoder` close-during-encode race.
- **F-13** — `verifyMacAppStoreNoGplDeps` covers
  `macAppStoreRuntimeClasspath`, but `:shared:voice-codec`'s own JVM
  artifact still references `org.bytedeco` (`LibavOpusEncoder`). Split
  `:shared:voice-codec` into `voice-codec-api` (no native) + `voice-codec-libav`
  (JVM/GPL).
- **F-16** — HARD RULE #2 escape: the "voice is not wired in v1" note in
  `MacAppStoreMain.kt` and FP-14d §3.1 is a temporary, which is forbidden.
  Resolution is to land F-2 (full wiring) before any Mac App Store
  TestFlight submission, or formally amend FP-14a §0 with a documented
  scope reduction and close FP-14c as kept-but-not-shipped.
- **F-17..F-21** — Minor JNA / Dispatch / Info.plist / dock-icon resource
  fixes.

FP-14h MUST land before any Mac App Store binary is submitted to App
Store Connect for review — submitting a chat-only build without voice
contradicts the FP-cycle objective and the platform table in CLAUDE.md.

## 5. Known operational constraint (recorded for future runners)

`productbuild`'s `SecKeyCreateSignature` call against the Mac Installer
Distribution private key prompts on a local developer machine the first
time it is used non-interactively. The CI keychain in
`.github/workflows/mac-app-store.yml` works around this by importing the
`.p12` with `-T /usr/bin/codesign -T /usr/bin/productbuild` ACL grants
and unlocking with `security unlock-keychain`. On a developer machine
the user must "Always Allow" once via Keychain Access. This is captured
in `2026-05-29-fp14a-mac-app-store-architect.md` §3.4 + §9 and is NOT a
bug; it is the documented prerequisite.

## 6. Self-critic

- All FP-14 commits in scope referenced with SHA + issue? **Yes** (§3).
- FP-14h scope clearly defined? **Yes** (§4) — bundles F-2, F-7..F-13,
  F-16..F-21 with one-line summaries each.
- Deferred findings called out by ID? **Yes**.
- No TODO in committed docs? **Yes** — FP-14h is tracked as a
  forthcoming issue, not as a TODO comment.
- No false "done" — FP-14h marked `[ ]`, FP-13 marked `[-]` with
  rationale. The voice wiring on App Store ships is NOT claimed "done".
- English only? **Yes**.

## 7. Outputs

- `docs/07_roadmap/phases.md` updated.
- `docs/03_infrastructure/dep-policy.md` updated.
- `docs/03_infrastructure/architect-reports/2026-05-29-full-feature-parity.md`
  §3.6 SUPERSEDED note + §7 annotated.
- `CLAUDE.md` Platforms table annotated.
- KB store: one finding under
  `agent://claude-mcp/puklic/2026-05-29-fp14-cycle-complete`.
- Issue #61 closed by the FP-14g commit.
