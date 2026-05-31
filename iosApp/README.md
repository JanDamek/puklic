# iosApp — Xcode wrapper for the Compose iOS framework

> **HARD RULE #4 (2026-05-31):** Apple distribution is LOCAL ONLY. No GitHub
> Actions workflow may build or upload to App Store Connect. All TestFlight
> uploads run from a developer Mac via `dist/apple/*.sh`. See
> `docs/06_ops/apple-release.md` for the runbook.

This directory hosts the thin Swift Xcode app that embeds the `PuklicShared`
Kotlin/Native framework produced by `:ios:app`. All app logic lives in Kotlin
(see `../ios/app/src/commonMain/kotlin/dev/puklic/ios/IosDependencyGraph.kt`);
Swift owns only the `UIWindow` and the `UIApplicationDelegate`.

## Files

| Path | Purpose |
|---|---|
| `project.yml` | xcodegen configuration — SSOT for the Xcode project. Regenerate with `xcodegen generate`. |
| `iosApp/Sources/AppDelegate.swift` | App entry. Calls `IosDependencyGraph.companion.create()` + `puklicAppRootViewController()`. |
| `iosApp/Sources/Info.plist` | Generated from `project.yml` by xcodegen. |
| `iosApp/Resources/Assets.xcassets/` | App icon catalog (placeholder; ship real icons before App Store submission). |
| `iosApp.xcodeproj/` | Generated. Committed for IDE convenience. |

## Regenerate the project

```bash
brew install xcodegen        # one-time
cd iosApp
xcodegen generate
```

The pre-build script in `project.yml` runs
`./gradlew :ios:app:embedAndSignAppleFrameworkForXcode` so opening the project
in Xcode and pressing run rebuilds the Kotlin framework automatically.

## Local archive + TestFlight upload

One-time setup:

```bash
bundle install
# Drop your ASC API key at ~/.appstoreconnect/private_keys/AuthKey_6C6D4D726S.p8
# Install the Apple Distribution cert + Puklic_App_Store.mobileprovision via Xcode.
# Fill the export options template:
sed \
  -e 's/TEAM_ID_PLACEHOLDER/GR74KSG8M9/g' \
  -e 's/BUNDLE_ID_PLACEHOLDER/cz.damek.puklic.app/g' \
  -e 's/PROVISIONING_PROFILE_NAME_PLACEHOLDER/<your profile name>/g' \
  dist/apple/ExportOptions-AppStore.plist \
  > dist/apple/ExportOptions-AppStore.filled.plist
```

Per-release:

```bash
dist/apple/release-ios.sh            # build + upload to TestFlight internal
dist/apple/release-ios.sh --dry-run  # validate prerequisites without shipping
```

For finer control:

```bash
dist/apple/build-ipa.sh     # archive only → build/ios-archive/Puklic.ipa
dist/apple/deploy-ipa.sh    # upload existing .ipa to TestFlight
```

## References

- `docs/06_ops/apple-release.md` — runbook
- `docs/03_infrastructure/architect-reports/2026-05-31-apple-local-only.md`
- `docs/03_infrastructure/architect-reports/2026-05-28-apple-distribution.md`
- `docs/03_infrastructure/architect-reports/2026-05-28-ios-app-framework.md`
- `docs/03_infrastructure/architect-reports/2026-05-28-ios-dependency-graph.md`
