# Contributing to Puklic

Thanks for your interest! Puklic is in early architecture phase — no code yet — but contributions to docs, architecture review, and (later) implementation are welcome.

## Code of Conduct

This project adheres to the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold it.

## How to contribute

### Reporting bugs

- Search [existing issues](https://github.com/JanDamek/puklic/issues) first
- Use the **Bug report** issue template
- Include: OS + version, Puklic version, steps to reproduce, expected vs actual behavior
- **Never include your Discord token** — it grants full account access

### Suggesting features

- Use the **Feature request** issue template
- Check [product vision](docs/00_overview/product-vision.md) and [roadmap](docs/07_roadmap/phases.md) first — some things are intentionally out of scope (no bots, no automation, no AI)

### Code contributions

Before writing code:

1. **Open an issue first** for non-trivial changes. Discuss approach before implementation.
2. **Read the docs** — especially [`CLAUDE.md`](CLAUDE.md), [ADRs](docs/01_architecture/adr/), and the [module map](docs/01_architecture/module-map.md).
3. **Follow the pipeline** — design (ADR if architectural) → tests → implementation → docs update. Each step in the same PR or sequential PRs.

#### Pull request workflow

1. Fork the repo
2. Branch from `main` with descriptive name: `feature/gateway-resume`, `fix/message-edit-race`
3. Make commits with [Conventional Commits](https://www.conventionalcommits.org/) style:
   - `feat(gateway): implement resume on disconnect`
   - `fix(parser): handle unclosed bold markdown`
   - `docs(adr): add ADR-0005 for navigation choice`
4. Write/update tests
5. Update relevant docs in `docs/` (required for architectural changes — see [`CLAUDE.md`](CLAUDE.md))
6. Push and open a PR using the PR template
7. CI must pass (lint, tests). Reviewer approval needed for merge.

## Coding standards

Once code lands:

- **Language:** Kotlin 2.x, idiomatic (see global rules in [`~/.claude/CLAUDE.md`](https://github.com/JanDamek/puklic#) or analog conventions)
- **Style:** ktlint default + project [`.editorconfig`](.editorconfig). Run `./gradlew ktlintFormat` before committing.
- **Static analysis:** detekt passes (`./gradlew detekt`)
- **Architecture rules:** UI ≠ logic, no global scopes, no Discord DTO in UI — see [`CLAUDE.md`](CLAUDE.md)
- **Test coverage:** new code with tests; parser/protocol modules target ≥90% coverage
- **No emoji in code/comments** unless explicitly part of test fixtures

## Testing

- Unit tests in `commonTest` for shared modules — must run on all KMP targets
- Integration tests for protocol layer against test doubles (mock gateway server)
- No live Discord testing in CI (rate limits + ToS)

## Documentation

- Architectural changes → update or add ADR in `docs/01_architecture/adr/`
- Domain model changes → update `docs/02_domain/*`
- Public API changes → KDoc on every public declaration
- **Docs and code in the same commit** — PRs that touch architecture without doc updates will be requested to add them

## Licensing of contributions

By submitting a contribution, you agree to license it under the [Apache License 2.0](LICENSE), the same license as the project.

## Security

For security vulnerabilities, **do not open a public issue**. See [`SECURITY.md`](SECURITY.md).

## Questions

- General discussion: GitHub Discussions
- Real-time chat: TBD (not on Discord, for obvious reasons)
