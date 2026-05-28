# iosApp — Xcode wrapper for the Compose iOS framework

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
in Xcode and pressing ▶ rebuilds the Kotlin framework automatically.

## Local archive + TestFlight upload

```bash
# One-time: install gem dependencies
bundle install

# One-time: drop your ASC API key into ~/.appstoreconnect/private_keys/
# and source the helper for env vars
source ~/.appstoreconnect/asc_api.sh

# Fill the export options template (gitignored — never commit)
sed \
  -e 's/TEAM_ID_PLACEHOLDER/GR74KSG8M9/g' \
  -e 's/BUNDLE_ID_PLACEHOLDER/cz.damek.puklic.app/g' \
  -e 's/PROVISIONING_PROFILE_NAME_PLACEHOLDER/<your profile name>/g' \
  dist/apple/ExportOptions-AppStore.plist \
  > dist/apple/ExportOptions-AppStore.filled.plist

export ASC_KEY_ID=6C6D4D726S
export ASC_ISSUER_ID=69a6de7f-7dab-47e3-e053-5b8c7c11a4d1
export ASC_KEY_PATH=~/.appstoreconnect/private_keys/AuthKey_6C6D4D726S.p8
export TEAM_ID=GR74KSG8M9
export BUNDLE_ID=cz.damek.puklic.app

bundle exec fastlane ios beta
```

## CI

`.github/workflows/apple-testflight.yml` runs the same `fastlane ios beta`
lane on a `macos-15` runner. Trigger via Actions → Apple TestFlight → Run
workflow. Required GitHub Secrets:

| Secret | Contents |
|---|---|
| `ASC_KEY_ID` | App Store Connect API Key ID |
| `ASC_ISSUER_ID` | ASC Issuer UUID |
| `ASC_KEY_P8` | Full text of the `.p8` file |
| `APPLE_DIST_P12_BASE64` | Base64-encoded `.p12` containing the Apple Distribution cert + private key |
| `APPLE_DIST_P12_PASSWORD` | Password for the `.p12` |
| `MAC_KEYCHAIN_PASSWORD` | Throwaway password for the temporary CI keychain |
| `APPLE_PROVISIONING_PROFILE_BASE64` | Base64-encoded App Store provisioning profile |
| `PROVISIONING_PROFILE_NAME` | Human-readable name of the profile (used in `ExportOptions`) |

## References

- `docs/03_infrastructure/architect-reports/2026-05-28-apple-distribution.md`
- `docs/03_infrastructure/architect-reports/2026-05-28-ios-app-framework.md`
- `docs/03_infrastructure/architect-reports/2026-05-28-ios-dependency-graph.md`
