# ADR-0002: Token paste as the primary login flow for MVP

- **Status:** accepted
- **Date:** 2026-05-21
- **Deciders:** Jan Damek

## Context

Discord has no public OAuth API for user accounts — OAuth only covers bots/integrations. For a third-party user client there are two realistic paths to obtaining a session token:

1. **Token paste** — the user retrieves their token from the browser (DevTools / localStorage) and pastes it into the client.
2. **Email + password + MFA flow** — the client calls `/auth/login`, handles captcha, MFA, and fingerprinting.

## Options considered

### Option A — Token paste (MVP)
**Pros:**
- Minimal implementation (just a text input + validation + secure storage)
- Low probability of Discord detection (client behaves like an already-logged-in browser session)
- No captcha / fingerprint header manipulation
- User has full control over the token (can revoke it in the browser)

**Cons:**
- Poor UX — the user must know how to press F12 or follow instructions
- Token expires on password change / browser logout
- Requires the user to understand the security implications of the token

### Option B — Email+password flow (later, Phase 2+)
**Pros:** Smooth UX, automatic refresh.
**Cons:**
- Discord uses fingerprinting (`super-properties`, `x-fingerprint`, browser cookies) — without proper simulation `/login` will reject requests or route them to captcha review
- Higher ban risk — Discord can see the login didn't come from the official client
- Captcha (hCaptcha) must be displayed in a WebView → complicates multiplatform
- MFA TOTP/SMS/backup codes flow

### Option C — Both at the same time from MVP
Rejected — increases MVP scope without proportional value. Token paste is sufficient for the author and early adopters.

## Decision

**Option A for MVP (Phase 1). Option B will be added in Phase 2 as a secondary path. Token paste remains as a fallback / power-user option permanently.**

## Consequences

- ✅ MVP login is a week-long task, not a month-long one
- ✅ Token stored in platform secure storage:
  - Linux: Secret Service API (libsecret) via JNA
  - macOS: Keychain
  - Windows: DPAPI / Credential Manager
  - Android: EncryptedSharedPreferences / Keystore
  - iOS: Keychain
- ✅ On startup the client validates the token via `GET /users/@me`; on 401 it sends the user to the login screen
- ⚠️ Onboarding must have a clear guide on how to retrieve the token — screenshots, step-by-step
- ⚠️ README and UI must warn: token = full account access, never share it
- 🔒 Token must never be logged, never sent anywhere other than the Discord API
- 🔒 Secure storage interface in `:shared:platform-api`, implementation per platform

## Update — 2026-05-22: email/password added alongside token paste

Email-or-username + password login was originally deferred to Phase 2 because Discord routinely
challenges new sign-ins with hCaptcha (unsolvable from a third-party client without bundling a
captcha solver, which would push Puklic into self-bot territory). User has chosen to ship it now
with the following constraints:

- The `LoginScreen` exposes **two tabs**: `Token` (paste) and `Email / Password`.
- The credentials flow calls `POST /api/v10/auth/login` via the new
  `DiscordLoginClient` (module `:shared:protocol-discord`).
- Successful login persists the resulting token via the same `SecureStorage` path as token-paste;
  no separate credential storage exists.
- **MFA**: when Discord returns `{"mfa":true,"ticket":...}` the UI prompts for the 6-digit TOTP
  code and calls `POST /api/v10/auth/mfa/totp`. SMS / WebAuthn factors are not yet implemented.
- **Captcha**: when Discord returns `captcha_key`/`captcha_sitekey` we surface a fixed error
  message asking the user to switch to the Token tab and paste a browser-obtained token. We do
  NOT bundle a captcha solver — solving a captcha programmatically would constitute self-bot
  behavior and is forbidden by `CLAUDE.md`.
- Passwords are never persisted, never logged, never echoed back outside the in-memory UI state;
  the password field uses `PasswordVisualTransformation`.

## Related

- ADR-0003: Cache & RAM strategy
- `docs/02_domain/discord-protocol.md` (TBD) — heartbeat, super-properties, gateway URL
- `docs/03_infrastructure/platform-abstractions.md` (TBD)
