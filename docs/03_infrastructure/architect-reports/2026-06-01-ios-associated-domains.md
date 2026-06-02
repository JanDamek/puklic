# iOS Associated Domains — `webcredentials:discord.com`

Date: 2026-06-01
Issue: [#75](https://github.com/JanDamek/puklic/issues/75)
Status: queued — entitlement block is present but commented out in
`iosApp/iosApp/iosApp.entitlements`. Activation requires three Apple Developer
Portal steps (see below) before the next TestFlight build.

## What the entitlement does

`com.apple.developer.associated-domains` with value `webcredentials:discord.com`
tells iOS that the Puklic app is allowed to participate in the iCloud Passwords
/ AutoFill flow scoped to discord.com credentials. End-state behaviour is
governed by two independent pieces:

1. **Our side** — the entitlement embedded in the signed app bundle. This is
   the only side we control.
2. **Discord's side** — a publicly reachable
   `https://discord.com/.well-known/apple-app-site-association` file declaring
   `GR74KSG8M9.cz.damek.puklic.app` as an authorised consumer of the
   `webcredentials` service.

Discord will not publish the AASA file for a third-party client. That is the
limit of what we can realistically achieve.

## Expected end-state

| Side | Behaviour |
|------|-----------|
| Discord publishes AASA (won't happen) | Full-screen Face ID sheet pre-filled with the stored discord.com credentials when the user focuses the email/password field. |
| Discord does **not** publish AASA (reality) | iOS QuickType bar above the keyboard surfaces a "discord.com" Passwords pill. Tapping it opens the system Passwords picker scoped to the discord.com category. The user manually picks the right credential. |

The realistic outcome is the QuickType pill — still a meaningful UX improvement
over the current state where iOS suggests Czech dictionary words for the email
field instead of stored credentials.

## Activation — Apple Developer Portal runbook

These three steps are interactive (Apple ID 2FA) and cannot be automated. They
must precede the next TestFlight build that ships the uncommented entitlement,
otherwise `codesign` will reject the bundle with
`Provisioning profile doesn't include the com.apple.developer.associated-domains entitlement`.

1. **Identifiers → cz.damek.puklic.app → Capabilities**
   - Tick ✅ **Associated Domains**
   - Save.

2. **Profiles → Puklic App Store → Edit → Save**
   - Re-generates the profile so it embeds the new entitlement.
   - Download the new `.mobileprovision`.

3. **Replace the local provisioning profile**
   ```
   cp ~/Downloads/Puklic_App_Store.mobileprovision \
      ~/Library/MobileDevice/Provisioning\ Profiles/Puklic_App_Store.mobileprovision
   ```

After all three are done, uncomment the
`com.apple.developer.associated-domains` block in
`iosApp/iosApp/iosApp.entitlements` and ship 1.2.6.

## Verification on TestFlight

1. Install the 1.2.6 TestFlight build.
2. Open the Discord login screen in Puklic.
3. Focus the email field.
4. Expect the **QuickType bar** above the keyboard to surface a
   `discord.com` Passwords pill. Tap it → system Passwords picker filtered to
   discord.com entries.
5. Pick the right credential → password field populates.

If the QuickType pill does not appear after fresh install + reboot, the
entitlement is missing from the embedded provisioning profile. Re-check step 3
of the runbook above (`security cms -D -i <profile>` and look for the key in
the `Entitlements` dict).

## Why this lives commented out rather than as a follow-up issue

HARD RULE #2 forbids deprecated / temporary scaffolding. The reason this
specific entry is *not* a temporary shim:

- The entitlement is the **complete conceptual answer** — there is no
  alternative path. Compose `KeyboardType.Password` alone does not unlock the
  discord.com category in QuickType.
- The portal step is an external prerequisite outside the codebase that blocks
  activation; the comment is a single `git revert` away from being live.
- The comment block names the exact prerequisite, so a future maintainer cannot
  uncomment it without understanding the dependency.
