# Architecture Decision Records

Každé netriviální architektonické rozhodnutí má vlastní ADR. Formát viz [`0000-template.md`](0000-template.md).

## Index

| ID | Title | Status |
|---|---|---|
| [0001](0001-compose-mpp-everywhere.md) | Compose Multiplatform jako jednotná UI vrstva | accepted |
| [0002](0002-token-paste-login.md) | Token paste jako primární login flow pro MVP | accepted |
| [0003](0003-cache-strategy.md) | Cache & RAM strategie | accepted |
| [0004](0004-coroutine-first.md) | Coroutine-first architektura | accepted |

## Pravidla

- Nové ADR = nové číslo, nikdy needitovat existující accepted ADR (místo toho nové ADR s `supersedes`)
- Status: `proposed` → `accepted` po user approval, případně `superseded by ADR-XXXX` nebo `deprecated`
- Datum = den vytvoření, neaktualizuje se
