# ADR-0002: Token paste jako primární login flow pro MVP

- **Status:** accepted
- **Date:** 2026-05-21
- **Deciders:** Jan Damek

## Context

Discord nemá veřejné OAuth API pro user account — OAuth pokrývá jen boty/integrace. Pro third-party user klient existují dvě reálné cesty získání session tokenu:

1. **Token paste** — uživatel získá token z prohlížeče (DevTools / localStorage) a vloží ho do klienta.
2. **Email + password + MFA flow** — klient volá `/auth/login`, řeší captcha, MFA, fingerprinting.

## Options considered

### Option A — Token paste (MVP)
**Pros:**
- Implementace minimální (jen text input + validace + secure storage)
- Nízká pravděpodobnost Discord detection (klient se chová jako už-přihlášený browser session)
- Žádná manipulace s captcha / fingerprint headers
- Uživatel má plnou kontrolu nad tokenem (může revoke v browseru)

**Cons:**
- UX hnusný — uživatel musí umět F12 nebo následovat návod
- Token expiruje při změně hesla / odhlášení z browseru
- Vyžaduje, aby uživatel pochopil bezpečnostní implikace tokenu

### Option B — Email+password flow (později, fáze 2+)
**Pros:** Hladký UX, automatický refresh.
**Cons:**
- Discord má fingerprinting (`super-properties`, `x-fingerprint`, browser cookies) — bez správné simulace tě `/login` odmítne nebo zařadí na captcha review
- Vyšší riziko banu — Discord vidí, že login nepřišel z oficiálního klientu
- Captcha (hCaptcha) musí být zobrazena v WebView → komplikuje multiplatform
- MFA TOTP/SMS/backup codes flow

### Option C — Oba zároveň od MVP
Odmítnuto — zvětšuje scope MVP bez úměrné hodnoty. Token paste stačí pro autora a early adopters.

## Decision

**Option A pro MVP (fáze 1). Option B se přidá ve fázi 2 jako sekundární cesta. Token paste zůstane jako fallback / power-user opce permanentně.**

## Consequences

- ✅ MVP login je týdenní task, ne měsíční
- ✅ Token uložen v platform secure storage:
  - Linux: Secret Service API (libsecret) přes JNA
  - macOS: Keychain
  - Windows: DPAPI / Credential Manager
  - Android: EncryptedSharedPreferences / Keystore
  - iOS: Keychain
- ✅ Při startu klient validuje token přes `GET /users/@me`; při 401 vyhodí uživatele na login screen
- ⚠️ Onboarding musí mít jasný návod jak token získat — screenshoty, krok-za-krokem
- ⚠️ README a UI musí varovat: token = plný přístup k účtu, nikdy nesdílet
- 🔒 Token nikdy nelogovat, nikdy neposílat mimo Discord API
- 🔒 Secure storage interface v `:shared:platform-api`, implementace per platforma

## Related

- ADR-0003: Cache & RAM strategy
- `docs/02_domain/discord-protocol.md` (TBD) — heartbeat, super-properties, gateway URL
- `docs/03_infrastructure/platform-abstractions.md` (TBD)
