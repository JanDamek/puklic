# `dist/push/` — Push notification infrastructure (APN + FCM)

Push consumer wiring (client SDK integration, background handlers, badge counts)
is **not implemented today** and **not addressed in this directory**.

This directory documents only the **infrastructure prep** so that when the client
side is designed, credentials and capabilities are already in place. Per user
2026-05-28: "to že zatím tam nic nepůjde nevadí" — infra ready, consumer when
designed.

See the full design:
[`docs/03_infrastructure/architect-reports/2026-05-28-apple-distribution.md`](../../docs/03_infrastructure/architect-reports/2026-05-28-apple-distribution.md) §6 (APN) and §7 (FCM).

## Routing decision

| Platform | Channel | Auth |
|---|---|---|
| iOS / Apple-Silicon-Mac-as-iPad | **APNs direct (HTTP/2)** | `.p8` auth key (modern; ES256 JWT) |
| Android (future) | **FCM HTTP v1** | Firebase service-account JSON (Google OAuth2) |
| Desktop (Linux/macOS native) | No push — foreground WebSocket only |

No relay layer. Each platform consumes its native push channel directly when
the server side is built.

---

## APN setup — checklist for user

Apple Developer portal access required.

- [ ] Open Apple Developer → Certificates, Identifiers & Profiles → **Keys**.
- [ ] Click ➕ to create a new key.
- [ ] Name: `Puklic APNs`.
- [ ] Check ✅ "Apple Push Notifications service (APNs)".
- [ ] (Optional, recommended) restrict the key to specific app IDs by selecting
      "Configure" → restrict to `dev.puklic.ios` (or your chosen bundle ID).
- [ ] Continue → Register → **Download `AuthKey_<KID>.p8`** (one-shot — Apple
      will never let you re-download).
- [ ] Note the Key ID shown on the Keys list (10-character alphanumeric).
- [ ] Move the file to disambiguate from the existing ASC API key:
      ```
      mv ~/Downloads/AuthKey_<KID>.p8 ~/.appstoreconnect/private_keys/AuthKey_<KID>_APNS.p8
      chmod 600 ~/.appstoreconnect/private_keys/AuthKey_<KID>_APNS.p8
      ```

### Why `_APNS` suffix?

Apple uses `AuthKey_<KID>.p8` as the filename convention for **both** App Store
Connect API keys **and** APNs auth keys. The current on-disk
`~/.appstoreconnect/private_keys/AuthKey_6C6D4D726S.p8` is an **ASC API Team
Key**, NOT an APNs key (confirmed in `~/.appstoreconnect/asc_api.sh`). Add the
`_APNS` suffix on download to avoid future confusion.

### APN server-side parameters (for when push is wired)

| Parameter | Value |
|---|---|
| Endpoint (production) | `https://api.push.apple.com/3/device/<deviceToken>` |
| Endpoint (development) | `https://api.sandbox.push.apple.com/3/device/<deviceToken>` |
| HTTP method | `POST` |
| Auth | `Authorization: bearer <JWT>` — JWT signed with the `.p8` key |
| JWT header | `{"alg":"ES256","kid":"<KEY_ID>","typ":"JWT"}` |
| JWT payload | `{"iss":"<TEAM_ID>","iat":<now>}` (TTL ≤ 1 hour) |
| `apns-topic` header | Bundle ID exactly (e.g. `dev.puklic.ios`) |
| `apns-push-type` header | `alert` for visible notifications, `background` for silent |
| `apns-priority` | `10` (immediate) or `5` (conserve battery) |

The Team ID is `GR74KSG8M9` (same as the ASC API setup).

### Why `.p8` over legacy `.p12`

- `.p12` certs expire annually + are per-environment (dev/prod separate).
- `.p8` keys are environment-agnostic, do not expire (rotate manually), use
  modern HTTP/2 + JWT. Apple's current recommendation.

---

## FCM setup — checklist for user

Google account access required.

- [ ] Open https://console.firebase.google.com.
- [ ] Click **"Add project"**.
- [ ] Project name: `Puklic` (or your choice).
- [ ] Disable Google Analytics for the project (not needed for push delivery).
- [ ] Click "Create project" → wait for provisioning → Continue.
- [ ] In the project: ⚙️ **Project settings** → **Service accounts** tab.
- [ ] Click **"Generate new private key"** → confirm → file downloads as
      `puklic-firebase-adminsdk-<hash>.json`.
- [ ] Move and lock down:
      ```
      mkdir -p ~/.firebase
      mv ~/Downloads/puklic-firebase-adminsdk-*.json \
         ~/.firebase/puklic-fcm-service-account.json
      chmod 600 ~/.firebase/puklic-fcm-service-account.json
      ```

### Android app registration (later, when Android push consumer lands)

DO NOT do this step today. Adding `google-services.json` to the repo without an
Android push consumer wired up violates HARD RULE #2 (no half-states).

When Android push is implemented:

- Firebase Console → Project settings → Your apps → ➕ Add app → Android.
- Android package name: `dev.puklic.android`.
- Download `google-services.json` → place at `android/app/google-services.json`.
- Add to `.gitignore`.

### FCM server-side parameters (for when push is wired)

| Parameter | Value |
|---|---|
| Endpoint | `https://fcm.googleapis.com/v1/projects/<PROJECT_ID>/messages:send` |
| Auth | OAuth2 bearer token from service-account JSON (scope `https://www.googleapis.com/auth/firebase.messaging`) |
| HTTP method | `POST` |
| Content | `{ "message": { "token": "<device-token>", "notification": { "title": "...", "body": "..." }, "data": { ... } } }` |

The legacy "Server Key" auth is **deprecated as of 2024-06-20** — do not use it.

---

## What is NOT in this directory

- ❌ No `.p8` files (secrets — stored in `~/.appstoreconnect/private_keys/`).
- ❌ No service-account JSON (secrets — stored in `~/.firebase/`).
- ❌ No client SDK code, no server-side push code (out of scope per user).
- ❌ No `google-services.json` (no Android push consumer yet).

## Next step

When the client push consumer is designed, a separate architect report covers:
- iOS: `UNUserNotificationCenter` registration, APNs token capture, background fetch.
- Android: Firebase SDK init, `FirebaseMessagingService` subclass, token refresh.
- Server: deciding where push originates (jervis `service-orchestrator` vs. dedicated relay).
