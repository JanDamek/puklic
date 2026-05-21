# ADR-0006: `ignoreUnknownKeys = true` exception for the Discord DTO parser

- **Status:** accepted
- **Date:** 2026-05-21
- **Deciders:** Jan Damek

## Context

`CLAUDE.md` (repo-level) prohibits `ignoreUnknownKeys = true` as a general shortcut, because it silently hides schema bugs. The rule applies to all internal serialization.

However, the Discord API is **not under our control**. Discord continuously adds new fields to its payloads (`MESSAGE_CREATE`, `READY`, embeds, components, etc.) without notice. Strict deserialization would turn every such rollout into an immediate client crash — which is worse than silently tolerating unknown fields.

We therefore need a **scoped exception** from the general rule.

## Decision

**This architectural exception applies exclusively to the `Json` instance used in `:shared:protocol-discord`** for deserializing Discord REST responses + gateway dispatched events.

Implementation:

```kotlin
// :shared:protocol-discord/src/commonMain/kotlin/.../DiscordJson.kt
internal val DiscordJson = Json {
    ignoreUnknownKeys = true                 // Discord external API exception
    encodeDefaults = false
    explicitNulls = false
    isLenient = false
}
```

**Test fixtures used to verify mappers MUST use a separate strict instance:**

```kotlin
// :shared:protocol-discord/src/commonTest/kotlin/.../DiscordJsonStrict.kt
internal val DiscordJsonStrict = Json {
    ignoreUnknownKeys = false                // CI gate against schema drift
    encodeDefaults = false
    explicitNulls = false
}
```

Mapper unit tests + fixture-based deserialization **always** use `DiscordJsonStrict`. If Discord adds a field that is present in a test fixture, CI fails until the DTO is updated — this is the desired behavior.

## Rationale

- **Production resilience:** Discord does not publish a breaking-change schedule. An unknown field must not crash the client for real users.
- **CI as schema-drift gate:** Strict deserialization in tests catches every new field *during the normal development cycle*, when a fixture-based mapper test already exists — the engineer then consciously decides how to extend the DTO.
- **No exception leak:** `DiscordJson` is an `internal val`, lives only in `:shared:protocol-discord`. Mappers (`DiscordMessageDto.toDomain()`) return domain types (`ChatMessage`, `Guild`, ...) which are **unaffected by lenient serialization**. No consumer outside `protocol-discord` will ever see `DiscordJson`.

## Consequences

- ✅ Discord schema additions will not cause a production crash.
- ✅ Schema drift is detected in CI the moment someone edits a fixture or adds a new test.
- ✅ The domain model remains strict — no `Optional<Map<String, Any>>` placeholders for unknown fields.
- ⚠️ The `DiscordJson` instance **must not be reused** outside `:shared:protocol-discord`. Any other `Json { ... }` call in the project must explicitly configure its own instance (default is strict).
- ⚠️ Mapper test fixtures **must be kept current** with the Discord schema. A stale fixture = a false-positive schema drift alert.
- 🔒 During code review, look for `Json {` usages outside `protocol-discord` — if `ignoreUnknownKeys = true` has leaked elsewhere, that is a bug.

## Related

- ADR-0002: Token paste login (uses REST API)
- `CLAUDE.md` (repo-level) — general prohibition of `ignoreUnknownKeys` and quick-fixes
- `docs/02_domain/discord-protocol.md` — Discord API contract notes
- Spec: `docs/03_infrastructure/architect-reports/2026-05-21-gradle-setup.md` §Q8 (original rationale + Json instance design)
