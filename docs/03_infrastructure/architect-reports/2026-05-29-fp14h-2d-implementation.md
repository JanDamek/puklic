# FP-14h-2d — kotlinx-atomicfu plugin migration (implementation slice)

Implementation of FP-14h-1-v2 §8 + §13.2d for `:shared:voice-codec`.

Date: 2026-05-31. Branch: `main`. Issue: JanDamek/puklic#68.

References:
- Architect SSOT: `2026-05-29-fp14h-1-v2-voice-gateway-redesign.md` §8 + §13.2d
- Predecessor slices: FP-14h-2a (`618c1f7`), FP-14h-2b (`2510920`), FP-14h-2c (`9ab447d`)

---

## §1 Scope clarification vs. v2 architect plan

The v2 plan §8.2 lists `VideoRtpSender`, `PlaybackPipeline`, and
`IncomingVideoPipeline` as the concurrency-rewrite targets. Per the
slice ordering decision recorded in §13.2c, those concurrency
rewrites would land **together with their move** from `:shared:voice`
to `:shared:voice-codec`. As of HEAD `756fb69`, those three files
are still in `:shared:voice/jvmMain/` — their move was not part of
FP-14h-2b or FP-14h-2c. They remain out of scope for FP-14h-2d per
the task prompt's "DO NOT touch :shared:voice GPL impl" + the
explicit module-scoping ":shared:voice-codec".

FP-14h-2d therefore covers only the j.u.c.* usages that currently
live inside `:shared:voice-codec`.

### 1.1 Inventory inside `:shared:voice-codec/src/`

`grep -rln "java.util.concurrent" shared/voice-codec/src/`:

| File | Use | Migration |
|---|---|---|
| `jvmMain/kotlin/dev/puklic/voice/codec/OpusCodec.jvm.kt` line 36 | `import java.util.concurrent.atomic.AtomicBoolean` | replace import with `kotlinx.atomicfu.atomic` |
| same file line 69 | `private val closed = AtomicBoolean(false)` in `LibavOpusEncoder` | `private val closed = atomic(false)` |
| same file line 136 | `check(!closed.get())` | `check(!closed.value)` |
| same file line 170 | `if (closed.compareAndSet(false, true))` | identical API on `kotlinx.atomicfu.AtomicBoolean` |
| same file line 192 | `private val closed = AtomicBoolean(false)` in `LibavOpusDecoder` | `private val closed = atomic(false)` |
| same file line 240 | `check(!closed.get())` | `check(!closed.value)` |
| same file line 300 | `if (closed.compareAndSet(false, true))` | identical API |

`grep -rln "ConcurrentHashMap\|ConcurrentLinkedQueue" shared/voice-codec/src/` → no results. **Nothing to migrate for concurrent collections.**

Total: 1 file, 7 line-touches, byte-identical semantics.

---

## §2 Build-script delta

### 2.1 `gradle/libs.versions.toml`

`[versions]`:
```
kotlinx-atomicfu = "0.27.0"
```

`[libraries]`:
```
kotlinx-atomicfu = { group = "org.jetbrains.kotlinx", name = "atomicfu", version.ref = "kotlinx-atomicfu" }
```

`[plugins]`:
```
kotlinx-atomicfu = { id = "org.jetbrains.kotlinx.atomicfu", version.ref = "kotlinx-atomicfu" }
```

Why 0.27.0: it is the version bundled internally by `kotlinx-coroutines-core 1.10.1` (the project's pinned coroutines version) and is officially compatible with Kotlin 2.1.x including 2.1.21 (per kotlinx.atomicfu release notes + verified by coroutines 1.10.1's dependency declaration). Using the same version as coroutines avoids duplicate classpath versions.

### 2.2 `settings.gradle.kts`

The plugin is published to Maven Central. `pluginManagement.repositories` already includes `mavenCentral()` — no entry change required. Adding the `kotlinx-atomicfu` plugin alias in `libs.versions.toml` is sufficient.

### 2.3 `shared/voice-codec/build.gradle.kts`

`plugins` block adds `alias(libs.plugins.kotlinx.atomicfu)`. `jvmMain.dependencies` adds `implementation(libs.kotlinx.atomicfu)` — required for the JVM-side `atomic(...)` factory used by `OpusCodec.jvm.kt`. The atomicfu compiler plugin transforms `AtomicBoolean` field references into `j.u.c.atomic.AtomicReferenceFieldUpdater` on JVM at compile time, but the source-level dependency must still resolve.

---

## §3 Source migration

File: `shared/voice-codec/src/jvmMain/kotlin/dev/puklic/voice/codec/OpusCodec.jvm.kt`.

Changes:
- Replace `import java.util.concurrent.atomic.AtomicBoolean` with `import kotlinx.atomicfu.atomic`.
- Replace both `private val closed = AtomicBoolean(false)` with `private val closed = atomic(false)`.
- Replace both `closed.get()` reads with `closed.value`.
- `closed.compareAndSet(false, true)` API matches atomicfu's `AtomicBoolean.compareAndSet` directly — no rename.

Semantics preserved: atomicfu's `atomic(false)` returns a `kotlinx.atomicfu.AtomicBoolean` whose memory model is identical to `j.u.c.atomic.AtomicBoolean` on JVM (the compiler plugin lowers it to volatile + `AtomicReferenceFieldUpdater`). Behaviour is byte-identical.

---

## §4 Self-critic

### 4.1 atomicfu plugin × Kotlin 2.1.21 compatibility

`atomicfu 0.27.0` is the version embedded in `kotlinx-coroutines-core
1.10.1`. Since the coroutines build itself uses `compileOnly(libs.kotlinx.atomicfu)`
for its compile-time AtomicFU transforms, and coroutines 1.10.1
publishes against Kotlin 2.1.x, the plugin is verified compatible
with the project's Kotlin pin (2.1.21). ✅

### 4.2 Interaction with `@PuklicVoiceCodec` opt-in

`OpusCodec.jvm.kt` does NOT use `@PuklicVoiceCodec`. The atomicfu
migration touches only private fields inside two private classes —
no API change, no opt-in propagation needed. ✅

### 4.3 Test impact

No existing test in `:shared:voice-codec/jvmTest/` reads `closed` directly
(`AtomicBoolean` is a private field). The change is opaque to all
callers. FP-14b test-ownership boundary not crossed. ✅

### 4.4 KMP target impact

The migration is jvmMain-only. iosMain/commonMain are not touched.
The atomicfu Gradle plugin applies the compile-time transform to all
KMP source sets — declaring it on the module is harmless for iOS
targets where no atomicfu use is present (the transform is a no-op
when no atomicfu types are referenced). ✅

### 4.5 verifyMacAppStoreNoGplDeps gate

atomicfu is Apache-2.0. Mac App Store gate stays GREEN. ✅

### 4.6 verifyIosNoGplDeps gate

atomicfu is Apache-2.0, no Native cinterop added. iOS gate stays GREEN. ✅

---

## §5 Acceptance criteria

- `./gradlew :shared:voice-codec:compileKotlinJvm` GREEN
- `./gradlew :shared:voice-codec:compileKotlinIosArm64 :shared:voice-codec:compileKotlinIosX64 :shared:voice-codec:compileKotlinIosSimulatorArm64` GREEN
- `./gradlew :shared:voice:build :shared:voice-codec:build` GREEN
- `./gradlew :ios:app:verifyIosNoGplDeps :desktop:app:verifyMacAppStoreNoGplDeps` GREEN
- `./gradlew :desktop:app:macAppStoreTest --no-configuration-cache` GREEN
- `./gradlew :shared:voice:test :shared:voice-codec:test` GREEN
