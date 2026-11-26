# Linux desktop performance baseline (PUKLIC-128)

Measured 2026-11-26 against the Phase 5 targets in `docs/07_roadmap/phases.md`, without a real Discord account.
Harness: `tools/perf` (see its README). Reproduce: `tools/perf/run.sh` (results in `build/perf/<label>.json`).

## Method

- Discord is simulated by `tools/perf/mock_discord.py` (REST v10 + JSON gateway, READY with N full guilds, generated messages).
  The app is pointed at it with `-Dpuklic.discord.apiBase` / `-Dpuklic.discord.gatewayUrl`
  (`DiscordEndpointsConfig.kt`); the token is pre-seeded through a file-backed `secret-tool` stand-in.
- Each sample is a true cold start: fresh `HOME`/XDG tree (empty cache and database), fresh mock server, the packaged
  `createReleaseDistributable` app image (ProGuard-shrunk, see PUKLIC-137 below) launched through `bin/Puklic`.
- Idle = 1 guild, RSS (`VmRSS` of `/proc/<pid>/status`) read 60 s after first frame and READY.
- Active = 10 guilds; `-Dpuklic.dev.autotest=true` loads the first 5 text channels of each guild (50 messages each),
  RSS read 30 s after the auto-test completed.
- Cold start = harness spawn timestamp to the `first-frame` milestone (`StartupTiming`), taken from the idle scenario.
- Median of 3 runs per scenario.

## Environment

- Host: 24 vCPU pod (kernel 6.8), Java 21 (Temurin 21.0.13.1), Compose Multiplatform 1.12.2, Kotlin 2.4.21.
- Rootless Xvfb 1280x800x24 (`tools/perf/setup-xvfb.sh`), Skiko `SOFTWARE_FAST` (CPU renderer, no GPU).
- The host was heavily shared during the runs (load average about 46 on 24 cores), so wall-clock timings are inflated
  and noisy (first run of each series is the slowest, e.g. 13.7 s). Memory numbers are stable (spread under 1 MB).

## Results after PUKLIC-137 (median of 3, 2026-11-26)

| Metric | Target | PUKLIC-128 baseline | Now | Result |
|---|---|---|---|---|
| RSS idle (1 guild) | < 150 MB | 183.6 MB | 149.2 MB (samples 148.8 / 149.2 / 150.1) | PASS, thin margin |
| RSS active (10 guilds, 5 channels each) | < 300 MB | 198.9 MB | 155.4 MB | PASS |

What changed, with the single-run idle RSS measured for each step (same host, noise about 1 to 3 MB):

| Step | Idle RSS |
|---|---|
| Baseline (PUKLIC-128 tuned flags, unshrunk app image) | 182.4 MB |
| ProGuard release shrink + obfuscation (`createReleaseDistributable`, `desktop/app/proguard-rules.pro`) | 162.5 to 166 MB |
| Plus Logback replaced by `java.util.logging` through `slf4j-jdk14` (`JulLogging`) | about -3.5 MB |
| Plus initial heap `-Xms8m -Xmn4m` (was 32 m / 16 m): committed heap 33 MB -> 18 MB | about -8 MB |
| Plus `-XX:TrimNativeHeapInterval=5000` (Linux only, glibc) | about -3.7 MB |

Notes:

- ProGuard keeps `dev.puklic.**`, ktor, kotlinx.coroutines, Skiko, JNA, dbus-java, jnr, sqlite, javacpp whole (reflection, JNI,
  ServiceLoader). Obfuscated `kotlinx.coroutines` fails bytecode verification (`VerifyError: Bad invokespecial` in an obfuscated
  `cancel()`), and shrunk/obfuscated ktor silently loses its engine (session never connects), so both stay unshrunk.
  ServiceLoader implementations (ktor engine, coil fetchers, decompose main-thread checker, dbus transport, slf4j provider) have explicit
  keep rules. ProGuard 7.10.0 is pinned because the default 7.8.0 cannot read Kotlin 2.4 metadata.
- Shipping packages must come from the `...Release...` tasks (`packageReleaseDeb`, `packageReleaseDmg`, `createReleaseDistributable`);
  the plain `package*` tasks produce the unshrunk image (about 20 MB more idle RSS).
- Logging: file rotation is now size based (10 MB x 14 files, no gzip, no daily roll); console and file layouts and the credential
  redaction are unchanged.
- Verified afterwards (PUKLIC-139, 2026-11-26): `packageReleaseDeb` builds a working `.deb` from the shrunk image (122 MB, Depends and
  `/usr/bin/puklic` symlink injected by `patchDebPostBuild`; `fakeroot` is needed on the host). `jdeps` over the shrunk classpath reports
  no unresolved class reference (only javacpp's build-time Maven mojos). A headless probe on the shrunk jars encodes and decodes Opus
  through the bundled FFmpeg natives, instantiates the libdave MLS client and loads dbus-java (it stops only at the missing session bus).
- Not verified: `.AppImage` / `.dmg` / `.msi` packaging, a live D-Bus tray against a session bus, a live voice call and screen sharing,
  real GPU rendering (no session bus, audio server, macOS or Windows on this host).
- The idle margin is about 1 MB; any new eagerly loaded dependency can push it back over the target.
- UI localization (PUKLIC-135): the first `Res.string.*` access runs the static initializer of the generated `String0_commonMainKt` /
  `String1_commonMainKt`, which loads about 200 lambda classes (`-Xlambdas=class`). A/B on the same host and flags (median of 3, shrunk
  image): idle 149.1 -> 151.2 MB, active 154.5 -> 156.1 MB. Idle is therefore about 1 MB over the 150 MB target again; JVM flag
  experiments (`ReservedCodeCacheSize`, `-Xss`, string deduplication, trim interval) moved it by at most 1 MB and were not adopted.

## Results before PUKLIC-137 (median of 3)

| Metric | Target | Default JVM flags | Tuned (shipped) | Result |
|---|---|---|---|---|
| RSS idle (1 guild) | < 150 MB | 249.4 MB | 183.6 MB | FAIL |
| RSS active (10 guilds, 5 channels each) | < 300 MB | 289.4 MB | 198.9 MB | PASS |
| Cold start to first frame | < 2 s | 8.5 s | 7.4 s | FAIL in this environment (not representative) |

"Default JVM flags" is the same build with the tuning flags removed from `Puklic.cfg` (G1, no heap limits).
"Tuned" is what `desktop/app/build.gradle.kts` now ships: `-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xms32m -Xmx512m
-Xmn16m -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:-UsePerfData`, plus `-Xlambdas=class -Xsam-conversions=class`
for first-party JVM modules. Tuned idle split: RssAnon 149.5 MB, RssFile 34.1 MB. Time to READY processed was 5.8 s (tuned idle).

## Where the idle memory goes (Native Memory Tracking, tuned, idle)

Committed: Java heap 35 MB, Metaspace 54 MB, Symbols 27 MB, Code cache 10 MB, class space 8 MB, threads 3 MB.
About 11,000 classes are loaded (Compose UI/foundation/material3 about 3,700, Kotlin stdlib/coroutines/serialization,
Ktor about 700, Logback about 570, Skiko/Skia about 330). JDK classes are not served from CDS because the jlink runtime
that Compose builds has no CDS archive.

## Experiments that did not help (idle RSS, single runs)

PUKLIC-137: Logback -> `java.util.logging` alone (+3 MB only, kept because it adds up); base CDS archive of the JDK classes
(`-Xshare:dump` into the runtime image, about -0.7 MB, not kept); trimmed heap-free ratios (5/15), `-XX:ReservedCodeCacheSize=24m`,
`-XX:CICompilerCount=1` (together about -1 MB, not kept); ProGuard with ktor and coroutines shrunk (broken, see above).

PUKLIC-128:

| Experiment | Idle RSS |
|---|---|
| Tuned baseline | about 193 MB (NMT run) |
| jlink runtime with `--generate-cds-archive` (base CDS) | 189 MB |
| Plus dynamic AppCDS archive (`-XX:+AutoCreateSharedArchive`) | 205 MB |
| `MALLOC_ARENA_MAX=1` | 192 MB |
| Smaller code cache, heap and thread stacks, one compiler thread | 198 MB |

## Animated GIF impact (PUKLIC-134)

Same `active` scenario (10 guilds) with `--gif-every 5`: every fifth mock message carries a 240x180, 10-frame GIF (`tools/perf/assets/anim.gif`),
median of 3 runs.

| Build | RSS active |
|---|---|
| Animated decoder registered | 189 MB |
| Static first frame only | 198 MB |

No measurable increase (the difference is run-to-run noise). CPU use of playback was not measured.

## Conclusion and caveats

- JVM tuning cut idle RSS by 66 MB and active RSS by 90 MB. The active target is met with margin.
- (Historical, before PUKLIC-137) Idle (184 MB vs 150 MB) was not met. The remainder is class metadata and symbols of the Compose, Ktor and Logback
  stacks; further reduction needs structural work (fewer eager-loaded libraries, trimming Logback, ProGuard/R8 shrinking of the app
  image), not flags. Tracked as follow-up work in PUKLIC-128.
- Cold start was measured only under load, software rendering and Xvfb; 5.8 to 8.5 s is an upper bound and is not a valid
  verdict against the 2 s target. A re-measurement on a quiet machine with a GPU is needed.
- The mock has no TLS, no CDN (avatars and icons are `null`) and a warm OS page cache; a real account pulls more data.
- Not verified: macOS, Windows, real GPU rendering, real Discord traffic.
