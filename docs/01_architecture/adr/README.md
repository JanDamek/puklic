# Architecture Decision Records

Every non-trivial architectural decision has its own ADR. For the format see [`0000-template.md`](0000-template.md).

## Index

| ID | Title | Status |
|---|---|---|
| [0001](0001-compose-mpp-everywhere.md) | Compose Multiplatform as the unified UI layer | accepted |
| [0002](0002-token-paste-login.md) | Token paste as the primary login flow for MVP | accepted |
| [0003](0003-cache-strategy.md) | Cache & RAM strategy | accepted |
| [0004](0004-coroutine-first.md) | Coroutine-first architecture | accepted |
| [0005](0005-decompose-navigation.md) | Decompose as the navigation library | accepted |
| [0006](0006-discord-json-leniency-exception.md) | `ignoreUnknownKeys = true` exception for the Discord DTO parser | accepted |

## Rules

- New ADR = new number, never edit an existing accepted ADR (create a new ADR with `supersedes` instead)
- Status: `proposed` → `accepted` after user approval, or `superseded by ADR-XXXX` / `deprecated`
- Date = day of creation, not updated afterwards
