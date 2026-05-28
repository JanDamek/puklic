# `dist/apple/` — Apple distribution templates

This directory holds **templates** for the Apple distribution pipeline (TestFlight,
App Store). No credentials, no live config, no `.p8` keys here.

See the full design:
[`docs/03_infrastructure/architect-reports/2026-05-28-apple-distribution.md`](../../docs/03_infrastructure/architect-reports/2026-05-28-apple-distribution.md).

## Files

| File | Purpose |
|---|---|
| `ExportOptions-AppStore.plist` | Template for `xcodebuild -exportArchive` when uploading to App Store / TestFlight. Contains placeholders. |
| `Fastfile.template` | Template fastlane configuration with the `beta` lane (build + archive + upload to TestFlight internal). Reads credentials from environment, never inline. |

Neither file is consumed directly. You **must** fill the placeholders into
`.filled.plist` / `fastlane/Fastfile` copies that are **gitignored**.

## Fill-in procedure (one-time)

1. **Confirm Apple Team ID**: currently `GR74KSG8M9` (from `~/.appstoreconnect/asc_api.sh`).

2. **Decide bundle ID**: suggested `dev.puklic.ios`. Whatever value chosen MUST
   match the App ID registered in Apple Developer portal.

3. **Register App ID** (manual, in Apple Developer portal):
   - Certificates, Identifiers & Profiles → Identifiers → ➕ → App IDs → App
   - Bundle ID = explicit, e.g. `dev.puklic.ios`
   - Capabilities: ✅ Push Notifications. Nothing else.
   - Register.

4. **Create App Store Connect record** (manual):
   - App Store Connect → My Apps → ➕ → New App
   - Platform = iOS
   - Bundle ID = the one registered in #3
   - SKU = `puklic-ios`
   - User Access = Limited Access initially
   - **Check "Make this app available on Apple Silicon Macs"** (= Designed for iPad on Mac)

5. **Create distribution certificate + provisioning profile** (manual or via
   fastlane `match`):
   - For first manual run: Xcode → Settings → Accounts → Team → Manage
     Certificates → ➕ → Apple Distribution.
   - Provisioning profile: Apple Developer portal → Profiles → ➕ → App Store →
     select the App ID from #3 → certificate from above → name e.g. "Puklic iOS
     App Store" → download and double-click to install.

6. **Fill `ExportOptions-AppStore.plist`**:
   ```
   cp dist/apple/ExportOptions-AppStore.plist dist/apple/ExportOptions-AppStore.filled.plist
   # edit the .filled.plist — replace TEAM_ID_PLACEHOLDER, BUNDLE_ID_PLACEHOLDER,
   # PROVISIONING_PROFILE_NAME_PLACEHOLDER
   # add dist/apple/ExportOptions-AppStore.filled.plist to .gitignore (already covered
   # by *.filled.plist pattern — verify)
   ```

7. **Set up fastlane**:
   ```
   gem install fastlane
   mkdir -p fastlane
   cp dist/apple/Fastfile.template fastlane/Fastfile
   # fastlane/ added to .gitignore (Fastfile contains no secrets but pattern keeps
   # it consistent with build artifacts)
   ```

8. **Export environment variables** (or put in CI secrets):
   ```sh
   export ASC_KEY_ID=6C6D4D726S
   export ASC_ISSUER_ID=69a6de7f-7dab-47e3-e053-5b8c7c11a4d1
   export ASC_KEY_PATH="$HOME/.appstoreconnect/private_keys/AuthKey_6C6D4D726S.p8"
   export TEAM_ID=GR74KSG8M9
   export BUNDLE_ID=dev.puklic.ios
   ```

9. **Smoke-test ASC connectivity**:
   ```
   bundle exec fastlane asc_ping
   ```

10. **Upload first build to TestFlight**:
    ```
    bundle exec fastlane beta
    ```

## Manual Apple steps that this tooling does NOT do

- App ID registration (#3 above)
- App Store Connect record creation (#4)
- Distribution certificate creation (#5)
- Beta App Review submission (one-time when uploading the first build for any new
  app — Apple does this automatically when you assign an internal-tester group)
- Internal-tester group creation + Apple ID invitations (App Store Connect →
  TestFlight → Internal Testing)

## Push (APN) infra

Push key provisioning is documented in [`../push/README.md`](../push/README.md).
Push prep is **independent** from TestFlight upload — TestFlight works without
a configured APN key, push delivery comes later.
