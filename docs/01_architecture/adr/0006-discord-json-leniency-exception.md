# ADR-0006: `ignoreUnknownKeys = true` výjimka pro Discord DTO parser

- **Status:** accepted
- **Date:** 2026-05-21
- **Deciders:** Jan Damek

## Context

`CLAUDE.md` (repo-level) zakazuje `ignoreUnknownKeys = true` jako obecný shortcut, protože tiše schovává schema bugs. Pravidlo platí pro veškerou interní serializaci.

Discord API ale **není pod naší kontrolou**. Discord přidává nová pole do svých payloadů (`MESSAGE_CREATE`, `READY`, embeds, components, atd.) průběžně a bez oznámení. Striktní deserializace by každý takový rollout proměnila v immediate crash klienta — což je horší než tichá tolerance neznámých polí.

Potřebujeme proto **scoped výjimku** z obecného pravidla.

## Decision

**Tato architektonická výjimka platí výhradně pro `Json` instanci používanou v `:shared:protocol-discord`** pro deserializaci Discord REST responses + Gateway dispatched events.

Implementace:

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
    ignoreUnknownKeys = false                // CI gate proti schema drift
    encodeDefaults = false
    explicitNulls = false
}
```

Mapper unit testy + fixture-based deserialization **vždy** používají `DiscordJsonStrict`. Pokud Discord přidá pole a v test fixture je, CI failuje až do okamžiku, kdy je DTO updatovaný — to je žádaný behavior.

## Rationale

- **Production resilience:** Discord nezveřejňuje breaking change schedule. Neznámé pole nesmí shazovat klient real users.
- **CI as schema-drift gate:** strict deserializace v testech zachytí každé nové pole *při běžném vývojovém cyklu*, kdy fixture-based mapper test už existuje — engineer pak vědomě rozhodne, jak DTO rozšířit.
- **Žádný leak výjimky:** `DiscordJson` je `internal val`, žije jen v `:shared:protocol-discord`. Mappery (`DiscordMessageDto.toDomain()`) vrací doménové typy (`ChatMessage`, `Guild`, ...), které **strict serializací nedotčené**. Žádný consumer mimo `protocol-discord` neuvidí `DiscordJson`.

## Consequences

- ✅ Discord schema additions nezpůsobí production crash.
- ✅ Schema drift se odhalí v CI v okamžiku, kdy někdo edituje fixture nebo přidává nový test.
- ✅ Doménový model zůstává strict — žádné `Optional<Map<String, Any>>` placeholdery na neznámé fieldy.
- ⚠️ `DiscordJson` instance **nesmí být reused** mimo `:shared:protocol-discord`. Kdokoli odjinud volá `Json { ... }` musí explicitně nakonfigurovat svou vlastní instanci (default je strict).
- ⚠️ Mapper test fixtures **musí být drženy aktuální** s Discord schema. Stale fixture = false-positive schema drift alert.
- 🔒 Při code review hledat `Json {` použití mimo `protocol-discord` — pokud `ignoreUnknownKeys = true` leaknul jinam, je to bug.

## Related

- ADR-0002: Token paste login (využívá REST API)
- `CLAUDE.md` (repo-level) — obecný zákaz `ignoreUnknownKeys` a zákaz quick-fixů
- `docs/02_domain/discord-protocol.md` — Discord API contract notes
- Spec: `docs/03_infrastructure/architect-reports/2026-05-21-gradle-setup.md` §Q8 (původní rationale + Json instance design)
