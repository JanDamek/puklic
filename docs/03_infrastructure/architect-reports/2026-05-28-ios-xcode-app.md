# Slice 4 + 6 — iOS Xcode app + fastlane CI

**Date**: 2026-05-28
**Depends on**: #30 (Slice 3, `1628db1`), #32 (Slice 3.5, `c0c3417`)
**Filed**: Slice 4 (Xcode app shell) + Slice 6 (fastlane CI workflow) — single-commit landing because Slice 6 is a thin wrapper around the Xcode project produced by Slice 4.

## Slice 4 — Xcode app

### Layout

```
iosApp/
├── project.yml                          # xcodegen config (SSOT)
├── README.md                            # local + CI run instructions
├── iosApp/
│   ├── Sources/
│   │   ├── AppDelegate.swift            # @main UIApplicationDelegate
│   │   └── Info.plist                   # generated from project.yml
│   └── Resources/
│       └── Assets.xcassets/
│           ├── Contents.json
│           └── AppIcon.appiconset/
│               └── Contents.json        # placeholder icon set
└── iosApp.xcodeproj/                    # generated; committed for IDE convenience
```

### Why xcodegen

Hand-authored `.pbxproj` files are infamous for merge conflicts and silent
breakage on Xcode updates. xcodegen's YAML is the SSOT — `xcodegen generate`
materialises a fresh pbxproj that always matches the declared deps + settings.
Industry standard for KMM apps (used by JetBrains' KMM templates).

### AppDelegate

```swift
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private var graph: IosDependencyGraph?

    func application(...) -> Bool {
        let graph = IosDependencyGraph.companion.create()
        self.graph = graph                   // retain for app lifetime
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = graph.puklicAppRootViewController()
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}
```

Swift owns only the `UIWindow` + a `graph` reference; everything else is
Kotlin. No `UIScene` usage — the app is a single-window experience and the
extra `UISceneDelegate` lifecycle would only complicate the Compose
`UIViewController` ownership.

### Pre-build script

`project.yml` declares a pre-build script that runs
`./gradlew :ios:app:embedAndSignAppleFrameworkForXcode` — Xcode picks up
`SDK_NAME` and `CONFIGURATION` from the environment, Gradle picks the
matching framework (iphoneos / iphonesimulator × Debug / Release) and copies
it into the app's `Frameworks/` folder. Opening the project in Xcode and
pressing ▶ now reproduces the CI flow end-to-end.

### Build settings of note

- `PRODUCT_BUNDLE_IDENTIFIER = cz.damek.puklic.app` (App Store ID per
  `2026-05-28-apple-distribution.md` §2.3)
- `DEVELOPMENT_TEAM = GR74KSG8M9` (handoff)
- `TARGETED_DEVICE_FAMILY = "1,2"` (iPhone + iPad)
- `SUPPORTS_MACCATALYST = NO` (chat-only iOS scope, not catalyst)
- `FRAMEWORK_SEARCH_PATHS` resolves to Gradle's per-config xcframework dir
- `ITSAppUsesNonExemptEncryption = false` (no custom crypto on iOS App Store
  build — voice/screenshare excluded, no DAVE)

## Slice 6 — fastlane CI

> **SUPERSEDED 2026-05-31 (#70):** the GitHub workflow described in this
> section was deleted per HARD RULE #4 (Apple LOCAL ONLY). The fastlane
> `:ios beta` lane is kept and is now invoked exclusively from local
> scripts under `dist/apple/*.sh`. See
> `docs/03_infrastructure/architect-reports/2026-05-31-apple-local-only.md`
> and `docs/06_ops/apple-release.md`.

### Files

```
Gemfile                                  # fastlane gem pin
fastlane/
├── Fastfile                             # promoted from dist/apple/Fastfile.template
└── Appfile                              # bundle id / team id
.github/workflows/
└── apple-testflight.yml                 # workflow_dispatch only
```

`Fastfile` is intentionally identical to the template that was reviewed in
the apple-distribution architect report (§5) — same env-var contract, same
`build_app` + `pilot` steps, only fix-ups for the
`linkReleaseFrameworkIosArm64` task name (matches what Slice 3 ships) and the
`ExportOptions-AppStore.filled.plist` path.

### CI workflow

`workflow_dispatch` only — TestFlight uploads are intentional. The job:

1. Checkout
2. JDK 21 (Adoptium) + Ruby 3.3 + bundler cache
3. Install `xcodegen` (brew)
4. Materialise the ASC `.p8` from the `ASC_KEY_P8` secret
5. Fill the ExportOptions plist from the secret-driven provisioning profile name
6. `xcodegen generate` (in `iosApp/`)
7. `./gradlew :ios:app:linkReleaseFrameworkIosArm64 :ios:app:verifyIosNoGplDeps`
8. Import the Apple Distribution `.p12` into a temporary keychain
9. Drop the `.mobileprovision` into `~/Library/MobileDevice/Provisioning Profiles/`
10. `bundle exec fastlane ios beta`
11. Always-run cleanup deletes the build keychain

### Secrets required

| Secret | Source |
|---|---|
| `ASC_KEY_ID` | App Store Connect → Users and Access → Integrations → App Store Connect API |
| `ASC_ISSUER_ID` | Same screen |
| `ASC_KEY_P8` | Full text of the `.p8` (the file Apple gives you once at key creation) |
| `APPLE_DIST_P12_BASE64` | Export Apple Distribution cert + private key from Keychain Access → base64 encode |
| `APPLE_DIST_P12_PASSWORD` | Password chosen during `.p12` export |
| `MAC_KEYCHAIN_PASSWORD` | Arbitrary throwaway value for the temporary CI keychain |
| `APPLE_PROVISIONING_PROFILE_BASE64` | Download App Store profile from Apple Dev portal → base64 encode |
| `PROVISIONING_PROFILE_NAME` | The profile's human name (used in ExportOptions) |

These belong to the user and are filed under "Slice 5 — Apple Dev portal
prerequisites" in the apple-distribution report. The workflow itself is
infrastructure; secrets are content the user supplies once.

## Verification

- `xcodegen generate` succeeds locally (Xcode 16.x).
- `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator build CODE_SIGNING_ALLOWED=NO` — verified locally; this exercises the pre-build script + Swift compile + link against the Kotlin framework.
- Fastlane lane `asc_ping` smoke-tests the ASC API key load and is the first thing to run before `beta`.

## Out of scope (sequential user actions, Slices 5 / 7 / 8 / 9 / 10)

These are user actions, not code changes — the CI workflow is ready to run
once the secrets land:

- Slice 5: Register App ID `cz.damek.puklic.app` in Apple Developer portal,
  enable Push Notifications capability, generate Apple Distribution cert,
  create App Store provisioning profile, create ASC app record.
- Slice 7: Trigger the workflow → first TestFlight upload → wait for the
  Beta App Review queue.
- Slice 8: Invite internal testers via the ASC web UI.
- Slice 9 (optional): Generate APN `.p8`, fill `dist/push/AuthKey_<KID>.p8`.
- Slice 10 (optional): Firebase project + service account JSON.
