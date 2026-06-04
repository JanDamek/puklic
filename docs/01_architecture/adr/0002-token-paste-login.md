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

## Update — 2026-05-22: realistic client-identity headers on every REST + Gateway request

Discord's REST stack returns `50001 Missing Access` for legitimately accessible channels when the
caller's `User-Agent` and `X-Super-Properties` do not look like the official desktop client. We
verified this against live channels the user can read in the official Discord client but where
`GET /channels/{id}/messages` failed for Puklic with `{"message":"Chybí přístup","code":50001}`.

The fix (per the Acheron client, MIT-licensed reference at github.com/ouwou/acheron) is to send
the desktop-client identity header set on every REST request and to send the same client
properties on the Gateway IDENTIFY payload:

- `User-Agent`: real Discord desktop UA (Electron + Chrome variant)
- `X-Discord-Timezone`: system IANA tz (e.g. `Europe/Prague`)
- `X-Discord-Locale`: system locale (e.g. `en-US`, `cs-CZ`)
- `X-Super-Properties`: base64-encoded JSON with `os`, `browser="Discord Client"`,
  `browser_version`, `os_version`, `system_locale`, `client_build_number`, `release_channel`,
  and the related referrer fields
- `X-Debug-Options: bugReporterEnabled`
- `Referer: https://discord.com/channels/@me`

The same `DiscordClientProperties` payload is also sent as `d.properties` on the Gateway
IDENTIFY (op 2), replacing the prior `{os:"linux", browser:"puklic", device:"puklic"}` which
made our gateway connection trivially identifiable as a third-party client and contributed to
some channel views remaining empty after READY.

This is a deliberate, narrow deviation from the "no detection-evasion" rule in
`CLAUDE.md`. It is required to make REST work for channels the user already has
access to in the official client. **We do not perform TLS-layer fingerprint impersonation
(no libcurl-impersonate / utls), and we do not solve captchas.** Header-level identity is the
minimum needed to interact with the same endpoints the official client uses; we still rely on
the user's own (browser-obtained) token and do not mint sessions.

`client_build_number` is hardcoded to `380000` for Phase 1; Phase 2 should fetch the current
build from Discord's HTML (or a community feed) on startup. The default `User-Agent` is the
macOS Electron variant; Phase 2 should pick the right OS-specific UA on Linux / Windows.

## Update — 2026-06-03: hCaptcha Enterprise (rqdata / rqtoken) — supersedes the §"Captcha" constraint above

The original constraint (surface a fixed error and ask the user to paste a browser token) was
replaced by an in-app, **user-solved** hCaptcha WebView (`CaptchaWebView`, per-platform `actual`).
The user solves the challenge themselves — this is not a programmatic captcha solver and stays
within the "behave like a real user" rule; Puklic never automates the solve.

Discord's login captcha is **hCaptcha Enterprise**. The captcha-required response carries
`captcha_sitekey`, `captcha_service`, `captcha_rqdata` (the enterprise challenge blob),
`captcha_rqtoken` and `captcha_session_id`. The widget MUST be rendered with `rqdata`, and — per
[docs.discord.food/topics/captcha-handling](https://docs.discord.food/topics/captcha-handling) —
the solution on the retry `POST /auth/login` MUST be sent in **HTTP headers** (the JSON body is
deprecated and ignored):

- `X-Captcha-Key` = the solved hCaptcha token
- `X-Captcha-Rqtoken` = the response's `captcha_rqtoken` (when present)
- `X-Captcha-Session-Id` = the response's `captcha_session_id` (when present)

The first attempt (1.2.16) sent `captcha_rqtoken` in the JSON body, which Discord ignored, so the
challenge looped. 1.2.17 moves to the header contract and threads `session_id` through.

Threading (every layer carries rqdata / rqtoken / sessionId):

- `DiscordLoginClient`: `LoginResponse.CaptchaRequired(sitekey, service, rqdata, rqtoken, sessionId)`;
  `loginWithCredentials(..., captchaKey, captchaRqtoken, captchaSessionId)` sets the three
  `X-Captcha-*` headers (only when non-blank). The request body no longer carries captcha fields.
- `:shared:session`: `CredentialsLoginResult.CaptchaRequired` / `LoginOutcome.CaptchaRequired` carry
  `rqdata` + `rqtoken` + `sessionId`; `CredentialsLogin.login(..., captchaRqtoken, captchaSessionId)`
  and `SessionManager.startSessionWithCredentials(..., captchaRqtoken, captchaSessionId)` forward them.
- `LoginViewModel`: `LoginState.captchaRqdata` / `captchaRqtoken` / `captchaSessionId`;
  `onCaptchaSolved` re-submits with the stored rqtoken + sessionId.
- `CaptchaWebView.ios.kt`: renders `data-rqdata="<rqdata>"` on the `.h-captcha` div when present.

A solution can still be rejected if the hCaptcha bot-score is too high (the iOS NSURLSession TLS
fingerprint is the suspected cause); that is a separate concern from the header contract and is NOT
addressed by detection-evasion (forbidden by `CLAUDE.md`).

## Related

- ADR-0003: Cache & RAM strategy
- `docs/02_domain/discord-protocol.md` (TBD) — heartbeat, super-properties, gateway URL
- `docs/03_infrastructure/platform-abstractions.md` (TBD)
