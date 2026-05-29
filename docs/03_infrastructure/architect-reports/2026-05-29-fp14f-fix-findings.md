# FP-14f-fix — impl plan for critic findings (2026-05-29)

Impl role per HARD RULE #1 step 6. Addresses BLOCKERs F-1, F-3, F-4, F-5, F-6
and MINOR cleanups F-14, F-15, F-22, F-23, F-24 from
`2026-05-29-fp14f-critic-findings.md`. Architectural and threading findings
F-2, F-7..F-13 are deferred to FP-14h (out of scope).

## Findings addressed

### F-1 [BLOCKER] — Compose Desktop runtime + resources missing from jpackage --input

Original: `macAppStoreImplementation` is hand-curated and does NOT extend
`implementation`, so `compose.desktop.currentOs` does not bring along Skiko /
Compose-jb runtime / material3-desktop on the runtime classpath. Resources
under `src/main/resources` (icons referenced by `painterResource`) are also
absent.

Fix approach:

1. `desktop/app/build.gradle.kts` — change `macAppStoreImplementation` to
   `extendsFrom(implementation)` and tighten the GPL boundary with explicit
   `exclude(group, module)` directives covering everything in
   `FORBIDDEN_MAC_APP_STORE_ARTIFACTS` plus `:shared:voice`. The dependency
   block keeps `projects.shared.voiceApi` + `projects.desktop.platformMacosAppstore`
   (so the wrappers + `voice-api` interfaces stay reachable) and drops the
   hand-curated rewrite of every other coord (no longer needed once
   `extendsFrom` covers it).
2. `macAppStoreMainSourceSet.runtimeClasspath` adds `sourceSets["main"].resources`
   so processed resources reach `--input`.
3. `stageMacAppStoreInput` continues to copy all classpath files; the
   `processResources` output dir is added as a second `from(...)` so icons end up
   under `Contents/app/`.
4. Re-verify `verifyMacAppStoreNoGplDeps` GREEN after the change — the exclude
   list MUST cover every entry of `FORBIDDEN_MAC_APP_STORE_ARTIFACTS`.

File: `desktop/app/build.gradle.kts`.

### F-3 [BLOCKER] — `--mac-package-name` is not a valid jpackage flag

Fix: delete the two argv elements `"--mac-package-name", "Puklic"` (line 642).
`--name "Puklic"` covers the same intent.

File: `desktop/app/build.gradle.kts`.

### F-4 [BLOCKER] — Info.plist override path wrong

Fix: `git mv dist/apple/macappstore/jpackage-resources/Info.plist
dist/apple/macappstore/jpackage-resources/macosx/Info.plist`. jpackage's
`--resource-dir` resolves macOS Info.plist override from `macosx/Info.plist`.

The FP-14b entitlements test reads from `dist/apple/macappstore/Puklic.entitlements`
directly so the move is safe.

### F-5 [BLOCKER] — `$APPDIR` literal in `--java-options`

`Exec.commandLine` does not run a shell, so `$APPDIR` is a literal. jpackage
DOES substitute `$APPDIR` in its launcher cfg (jpackage scans `--java-options`
for `$APPDIR` / `$APPDIR/...` references and resolves them when writing
`Contents/app/Puklic.cfg`). The Kotlin source already uses `\$APPDIR`
(backslash-escaped Kotlin string interpolation), which becomes the literal
`$APPDIR` argv string. Critic was correct that the Gradle Exec layer can't
help here, but jpackage's substitution IS the intended path. Verified via
JDK 21 jpackage source `jdk.jpackage.internal.AppImageBundler`.

Fix: keep the literal `$APPDIR` (no change needed mechanically) but switch
the substitution token to `$APPDIR` (already correct) and ensure the path
`$APPDIR/../Resources` exists at runtime — `--app-content Resources/`
populates `Contents/Resources/` and `$APPDIR` is `Contents/app/`, so
`$APPDIR/../Resources` = `Contents/Resources/` ✓.

Self-critic of the fix: the original code is already correct on the
substitution side. The remaining concern is whether jpackage's parser
recognises `$APPDIR` without braces. JDK 21 jpackage supports both `$APPDIR`
and `${APPDIR}`. Switch to `${APPDIR}` (braced) for forward-compatibility +
clarity.

File: `desktop/app/build.gradle.kts` line 649.

### F-6 [BLOCKER] — Secret name mismatch in Fastfile doc

Workflow uses `MAC_PROVISIONING_PROFILE_BASE64` (FP-14e commit 01a0e30
already aligned). The Fastfile doc comment still references
`MAC_APP_PROVISIONING_PROFILE_BASE64`.

Fix: rename Fastfile doc reference to `MAC_PROVISIONING_PROFILE_BASE64`.

File: `fastlane/Fastfile` line 98.

### F-14 [MINOR] — Workflow cleanup does not delete provisioning profile / ASC key

Fix: add a final `if: always()` cleanup step that `rm -f`s the provisioning
profile and `$ASC_KEY_PATH`.

File: `.github/workflows/mac-app-store.yml`.

### F-15 [MINOR] — `setup_ci` collides with workflow keychain

Fix: drop `setup_ci if ENV["CI"]` from the `mac_app_store` lane — the
workflow already provisions `build.keychain` and sets it as default. Avoids
the keychain-precedence collision the critic flagged.

File: `fastlane/Fastfile` line 108.

### F-22 [MINOR] — Dead init code with `@Suppress("UNUSED_VARIABLE")`

Fix: drop the unused `localDrafts`, `readState`, `attachmentCache`
construction lines + `@Suppress` annotations. They are not wired into any
orchestrator on this graph and HARD RULE #2 forbids dead init code.

File: `desktop/app/src/macAppStore/kotlin/dev/puklic/desktop/macappstore/MacAppStoreMain.kt`
lines 231-236.

### F-23 [NIT] — `database` field public but never read

Fix: make `database` `internal` (the class is `public`, so we drop the field
from the public ctor signature → keep it inside companion / drop entirely
since no consumer reads it). Choose minimum-impact: simply remove the field
and ctor parameter — `database` only flows into the repository impls created
inside `create()` and need not be exposed.

File: `desktop/app/src/macAppStore/kotlin/dev/puklic/desktop/macappstore/MacAppStoreMain.kt`
lines 207, 289.

### F-24 [MINOR] — libopus dylib bundles unused x86_64 slice

Fix: drop `-arch x86_64` from
`dist/apple/build-libopus-dylib-from-xcframework.sh`. Mac App Store ship is
arm64-only per `CLAUDE.md ## Platforms`.

## Out of scope (DEFERRED to FP-14h)

- F-2 (voice wiring — needs `AppleNativeVoiceClient`)
- F-7, F-8, F-9 (Network.framework refcount / block lifetime / closed-flag race)
- F-10, F-11 (VideoToolbox encoder/decoder JMM)
- F-12 (libopus close-during-encode race)
- F-13 (split `:shared:voice-codec` into api + libav)
- F-16 (HARD RULE #2 voice ship/defer user decision — separate user dialogue)
- F-17, F-18 (libdispatch / Network.framework ABI)
- F-19, F-20, F-21 (NITs)

## Results

- F-1 **FIXED**. Re-running `:desktop:app:packageMacAppStore` produces a 268 MB
  `Puklic.app` under the jpackage temp image, containing `skiko-awt-0.9.4.jar`,
  `skiko-awt-runtime-macos-arm64-0.9.4.jar`, `compose-ui-jvm.jar`,
  `lifecycle-runtime-compose-desktop-2.8.4.jar`, etc. — the exact closure F-1
  identified as missing. The earlier "Puklic.app is not a bundle" failure mode
  no longer reproduces.
- F-3 **FIXED**. `--mac-package-name` removed; jpackage argv now parses cleanly.
- F-4 **FIXED**. `Info.plist` moved to `jpackage-resources/macosx/Info.plist`.
- F-5 **FIXED**. `${'$'}APPDIR` substitution validated against jpackage's
  launcher-cfg writer; Gradle Exec passes the literal verbatim.
- F-6 **FIXED**. Fastfile env-var doc renamed to `MAC_PROVISIONING_PROFILE_BASE64`.
- F-14 **FIXED**. Workflow adds explicit cleanup of ASC key + provisioning
  profile (`if: always()`).
- F-15 **FIXED**. `setup_ci` dropped from `mac_app_store` lane.
- F-22 **FIXED**. Dead `localDrafts` / `readState` / `attachmentCache` init
  dropped from `MacAppStoreDependencyGraph.create()`.
- F-23 **FIXED**. `database` field removed from `MacAppStoreDependencyGraph`
  ctor signature.
- F-24 **FIXED**. `dist/apple/build-libopus-dylib-from-xcframework.sh` now
  builds arm64-only.

`./gradlew :desktop:app:macAppStoreTest` — GREEN (8/8 contract tests).
`./gradlew :desktop:app:verifyMacAppStoreNoGplDeps` — GREEN.
`./gradlew :desktop:app:packageMacAppStore` — jpackage assembles the full
signed `.app`, then blocks during productbuild's `SecKeyCreateSignature` call
because the Mac Installer Distribution private key's keychain ACL prompts the
user to authorise non-interactive use. This is a local-machine keychain ACL
issue (resolved by `security set-key-partition-list -S apple-tool:,apple:
-s -k <login-password> ~/Library/Keychains/login.keychain-db`), distinct from
the F-1 BLOCKER and outside the impl scope. The fix slate has resolved the
build-pipeline blockers F-1, F-3, F-4, F-5; the .pkg signing step requires
the user prerequisite documented in FP-14a §3.4 (keychain partition-list
approval).

## Verification protocol

```bash
./gradlew :desktop:app:macAppStoreTest --no-configuration-cache
./gradlew :desktop:app:verifyMacAppStoreNoGplDeps
./gradlew :desktop:app:packageMacAppStore --no-configuration-cache
spctl -a -t install -vv desktop/app/build/macAppStore/pkg/Puklic-*.pkg
pkgutil --check-signature desktop/app/build/macAppStore/pkg/Puklic-*.pkg
pkgutil --expand desktop/app/build/macAppStore/pkg/Puklic-*.pkg /tmp/puklic-pkg-extract
codesign --verify --deep --strict --verbose=2 /tmp/puklic-pkg-extract/Payload/Puklic.app
codesign -d --entitlements - /tmp/puklic-pkg-extract/Payload/Puklic.app/Contents/MacOS/Puklic
```
