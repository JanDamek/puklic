# ADR-0008 — Chat parser: keep custom Discord-markdown parser, do not adopt `org.jetbrains:markdown`

- Status: Accepted
- Date: 2026-05-27
- Deciders: project owner
- Tracking issue: [#9](https://github.com/JanDamek/puklic/issues/9)
- Related memory rule: [library-first-before-custom](../../../../.claude/projects/-Users-damekjan-git-puklic/memory/library-first-before-custom.md)

## Context

`:shared:chat-parser` contains a hand-rolled parser (`Parser.kt`, 527 LOC; 80 tests in `ParserTest.kt`) that turns a Discord message string into a `RichTextDocument` AST. It was implemented incrementally across Phase 2 (commits `e527c03`, `0b120ee`, `f90b7c6`, `ba9a986`) before the project formalized the rule that any custom parser/codec must be preceded by a library survey.

Issue #9 demands a **retroactive library-first audit** comparing the custom parser against `org.jetbrains:markdown:0.7.3` (KMP, CommonMark + GFM, Apache-2.0), with a binding decision (no temporary state — HARD RULE #2).

## Library survey

| Library | KMP support | Spec | License | Discord-token support | Tolerant on unmatched | Underline `__x__` | Spoiler `||x||` | `>>>` block quote |
|---|---|---|---|---|---|---|---|---|
| **`org.jetbrains:markdown:0.7.3`** | yes | CommonMark + GFM | Apache-2.0 | no — would require pre/post-processing + custom flavor | partial (CommonMark is strict in places Discord is lenient) | parsed as **bold** (CommonMark) — conflicts with Discord | no | no |
| `com.vladsch.flexmark` | JVM only | CommonMark + many extensions | BSD-2 | no | partial | bold | no | no |
| `commonmark-java` | JVM only | CommonMark | BSD-2 | no | strict | bold | no | no |
| **Current hand-rolled** | yes (commonMain) | Discord-flavored subset | (project) | yes (native) | yes (literal fallback) | underline (correct) | spoiler (correct) | yes |

JVM-only libraries are eliminated immediately — they cannot live in `:shared:chat-parser/commonMain` (rule "shared modules must not know about the platform"). Only `org.jetbrains:markdown` is a viable candidate.

## Discord markdown is not CommonMark

The Discord dialect diverges from CommonMark in load-bearing ways:

1. **`__x__` means underline, not bold.** CommonMark / jetbrains-markdown parse this as strong emphasis.
2. **`||x||` is spoiler.** Not in CommonMark, not in GFM.
3. **`>>> ` block quote consumes the rest of the message.** Discord-specific.
4. **Headings only 1–3 levels, requiring a space after the hashes.** CommonMark allows 1–6.
5. **No setext headings, no reference links, no images via `![alt](url)`, no HTML.** These would all be syntactically *recognized* by jetbrains-markdown and produce surprising AST nodes that Compose UI must then ignore.
6. **Discord-specific inline tokens** (`<@id>`, `<#id>`, `<@&id>`, `<:name:id>`, `<a:name:id>`, `<t:unix:style>`, `@everyone`, `@here`) are not markdown at all. Any general markdown parser is blind to them; they would need a pre-pass to placeholder them or a post-pass to extract them from text nodes — either way, the Discord-token logic stays in our code.
7. **Tolerant on unmatched delimiters.** Discord renders `**foo` as literal `**foo`. CommonMark engines vary; strict ones may differ.

## Options considered

### Option A — Full replacement with `org.jetbrains:markdown`

Rip out custom parser, depend on `org.jetbrains:markdown`, write:
- A custom `MarkdownFlavourDescriptor` disabling images, reference links, setext, HTML, autolinks (we keep our own), strong-emphasis-from-double-underscore.
- A pre-pass to extract Discord tokens into placeholders before invoking the library.
- An AST translator from jetbrains-markdown's `ASTNode` tree to our `RichTextDocument` / `RichTextBlock` / `RichTextInline`.
- A post-pass to inject Discord tokens back into the AST in the right inline positions.
- Re-implementations of spoiler and Discord-style `>>> ` block quote (the library does not know them).

Estimated size: ~250 LOC of translator + ~150 LOC of pre/post-pass + ~80 LOC custom flavor = **~480 LOC**, vs the 527 LOC the custom parser uses today, **plus** a transitive dependency. We would still own every Discord-specific edge case, just with a more complicated seam to debug.

Rejected. The library does not deliver the value that justifies the cost.

### Option B — Hybrid (library for CommonMark portion, custom for Discord tokens)

Hardest to reason about: the seam between "CommonMark portion" and "Discord-token portion" is not clean because Discord tokens appear *inside* inline-emphasis runs (e.g. `**hello <@123>**`). Drawing the seam means either parsing twice or splicing ASTs, both of which create more bugs than the current parser has.

Rejected — would violate HARD RULE #2 by introducing a more complex middle ground.

### Option C — Keep custom parser, document this decision

The custom parser is:
- 527 LOC, single file, no inheritance, no global state, pure function
- 80 unit tests, all green
- Native Discord dialect — no impedance mismatch
- Zero runtime dependencies beyond `kotlinx.datetime`
- KMP-clean (`commonMain` only)

Bugs found historically (animated emoji `<a:...>`, asterisk bullets, link-click handler, persistence-mapper dropping parsedContent) were not parser-correctness bugs — they were missing-features added incrementally. A library would not have prevented them.

**Selected.** This ADR is the final say. The custom parser stays.

## Decision

We keep `:shared:chat-parser` as a custom hand-rolled Discord-markdown parser. We do not adopt `org.jetbrains:markdown` or any other markdown library.

## Consequences

### Positive

- One single-responsibility file (`Parser.kt`) is the entire markdown surface — easy to audit, easy to extend for future Discord syntax (e.g. small text `-# `, masked links policy).
- No transitive dependency, no version drift, no library-bug exposure.
- AST shape (`RichTextDocument` / `RichTextBlock` / `RichTextInline`) is owned by us and tuned for Compose rendering — no translation layer.
- KMP-clean.

### Negative

- We carry maintenance ownership of all parser edge cases.
- New contributors must learn the Discord dialect (mitigated by `docs/02_domain/richtext-ast.md` and the 80 tests in `ParserTest.kt` acting as executable spec).

### Neutral / future

- If Discord ever publishes a formal, stable grammar and an open-source reference implementation, revisit this ADR.
- If the custom parser grows past ~1000 LOC across multiple files, revisit the cost/benefit. Until then, the 527-LOC single-file form is well within the SOLID size budget (`Parser.kt` is a module of small private functions, no class exceeds the 500-LOC class budget; the file-level LOC is incidental, not a class).

## References

- Issue [#9](https://github.com/JanDamek/puklic/issues/9) — Retroactive library-first audit
- Memory rule: [library-first-before-custom](../../../../.claude/projects/-Users-damekjan-git-puklic/memory/library-first-before-custom.md)
- Code: `shared/chat-parser/src/commonMain/kotlin/dev/puklic/chatparser/Parser.kt`
- Tests: `shared/chat-parser/src/commonTest/kotlin/dev/puklic/chatparser/ParserTest.kt`
- Domain AST spec: `docs/02_domain/richtext-ast.md`
- Library evaluated: <https://github.com/JetBrains/markdown>
