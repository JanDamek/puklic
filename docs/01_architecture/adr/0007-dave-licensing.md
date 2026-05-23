# ADR-0007: DAVE E2EE — accept GPL-3.0 binary distribution via Wire core-crypto

- **Status:** accepted
- **Date:** 2026-05-23
- **Deciders:** Jan Damek

## Context

Phase 3.1 of the roadmap (`docs/07_roadmap/phases.md`) calls for DAVE (Discord
Audio/Video End-to-End encryption) on top of the already-shipped voice stack.
DAVE is built on MLS (RFC 9420) for group key agreement plus per-frame
ChaCha20-Poly1305 over Opus/video payload. See architect report
`docs/03_infrastructure/architect-reports/2026-05-23-dave-e2ee.md` for the
full protocol analysis.

The implementation blocker is the MLS library:

- The only mature, audited MLS library available on Maven Central with a
  Kotlin/JVM surface is **`com.wire:core-crypto-jvm`** (Wire). It wraps a Rust
  implementation via JNI and ships native `.so` / `.dylib` / `.dll` per
  platform classifier. License: **GPL-3.0-or-later**.
- Discord's own `libdave` reference (C++, MIT) would require a multi-week
  cross-compile + JNI wrapper pipeline.
- Hand-rolling MLS is multi-month and would ship unaudited crypto.

Puklic source code is Apache-2.0. The distributed binary installers were
already bumped to **GPL-2.0-or-later** in Phase 6 (commit `1ff8c58`,
`docs/06_ops/licensing.md`) because they bundle the GPL build of FFmpeg
(libx264 / libx265). Adding `com.wire:core-crypto-jvm` makes the effective
binary license **GPL-3.0-or-later** — a small delta over the existing GPL-2.0
baseline.

## Options considered

### Option A — Adopt `com.wire:core-crypto-jvm` (GPL-3.0)

Pros:
- Working, audited MLS today; 3-4 engineer-weeks to ship DAVE end-to-end.
- Ciphersuite 1 (`MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519`) supported —
  matches Discord.
- Per-epoch exporter mechanism present (`deriveAvsSecret`).
- Aligns with global CLAUDE.md rule "use a working solution first".

Cons:
- Binary license becomes GPL-3.0-or-later (delta from GPL-2.0).
- Wire 4.2.0's public API only exposes an MLS exporter with the fixed label
  `"AVS"` — DAVE needs label `"Discord Secure Frames v0"`. The 3.1b spike
  works around this for the smoke test by accepting both sides derive
  identical bytes via the AVS exporter; producing DAVE-correct exporter bytes
  requires upgrading Wire or patching core-crypto.
- iOS artifact (`core-crypto-iosarm64`) stuck at 0.6.0-rc → iOS DAVE deferred.

### Option B — Wrap Discord's `libdave` (MIT) via JNI

Pros:
- Bit-exact match with Discord's official client.
- Permissive (MIT) — binary stays GPL-2.0-or-later.
- Solves the exporter-label problem out of the box.

Cons:
- 6-8 weeks of native cross-compile + JNI pipeline before any DAVE code lands.
- Blocks the lock-icon UX for months.

### Option C — Hand-roll MLS in Kotlin

Rejected: 4-6 months + would ship unaudited crypto. Not viable for a
community project.

## Decision

Adopt **`com.wire:core-crypto-jvm:4.2.0`** for MLS. The distributed Puklic
binary installer license becomes **GPL-3.0-or-later**. Source code remains
**Apache-2.0**.

The exporter-label gap is acknowledged and documented in the architect report
§3 + in `MlsClient.exportSecret` KDoc; the gap is acceptable for the 3.1b
spike (parity demonstrated) and will be closed in 3.1c-e via one of:
- bumping Wire to a version that exposes the raw MLS exporter, or
- the libdave-JNI route (Phase 3.2), or
- a downstream patch to core-crypto.

## Consequences

- `LICENSE-third-party.txt` enumerates `com.wire:core-crypto-jvm` and notes the
  GPL-3.0-or-later bump.
- `docs/06_ops/licensing.md` updated: distributed binary is GPL-3.0-or-later
  (was GPL-2.0-or-later); source remains Apache-2.0.
- Anyone redistributing the **binary installer** must comply with
  GPL-3.0-or-later: provide source, license, and offer corresponding source.
- Anyone forking the **source** under Apache-2.0 is unaffected — they simply
  must not link against `com.wire:core-crypto-jvm` if they want a non-GPL
  combined binary (matches the existing libx264 caveat).
- `:shared:voice-dave` is JVM-only. Android DAVE is feasible (`.aar` exists)
  and deferred to Phase 3.2; iOS DAVE is blocked on a Wire iOS artifact
  update.
- Phase 3.1b deliverable: `:shared:voice-dave` module + `MlsClient` interface
  + Wire-backed JVM actual + 3 passing smoke tests (init, KeyPackage,
  two-client Welcome exporter parity).

## Related

- Architect report: `docs/03_infrastructure/architect-reports/2026-05-23-dave-e2ee.md`
- Licensing SSOT: `docs/06_ops/licensing.md`
- Module: `shared/voice-dave/`
- Roadmap: `docs/07_roadmap/phases.md` Phase 3
- Prior license bump (libx264, GPL-2.0): commit `1ff8c58`
