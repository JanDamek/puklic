# FP-14f critic findings (2026-05-29)

Read-only critic pass per HARD RULE #1 Step 7. Covers commits:

- `4d3eb38` — FP-14c VideoToolbox + libopus + Network.framework JNA wrappers
- `1d5a53b` — FP-14d Gradle macAppStore source set + packageMacAppStore + entitlements
- `01a0e30` — FP-14e fastlane `mac` platform + Mac App Store workflow

Reviewer: code-critic role. No production / test / build / workflow files were
modified by this pass — only this report file is added.

## Severity legend

- **BLOCKER** — must be fixed before any Mac App Store upload attempt
- **MAJOR** — significant correctness / safety / sandbox impact, fix within FP-14
- **MINOR** — code quality / consistency / latent bug, can roll into FP-14g
- **NIT** — style or doc preference

## Summary

| Severity | Count |
|---|---|
| BLOCKER | 6 |
| MAJOR | 8 |
| MINOR | 7 |
| NIT | 3 |
| **Total** | **24** |

The two mission-brief known gaps (productbuild failure, NoOpVoiceClient) are
confirmed and root-caused in F-1 and F-2 respectively. The largest cluster of
new findings is in `JnaNwConnectionUdpTransport` (Apple-block lifetime + nw
object refcount imbalance + closed-flag race) and in the `packageMacAppStore`
Gradle task (no Compose Desktop deps actually reach jpackage, Info.plist
override path is wrong for jpackage, `--mac-package-name` collides with
`--name`).

---

## Findings

### F-1 [BLOCKER] `packageMacAppStore` cannot produce a runnable `.app` — Compose Desktop deps are missing from the staged classpath; productbuild rejects with "Puklic.app is not a bundle"

File: `desktop/app/build.gradle.kts` lines 538-654 (especially 539-545 stage,
598-625 ant jar, 629-651 argv).

The `stageMacAppStoreInput` task copies every file resolved on
`macAppStoreMainSourceSet.runtimeClasspath` into `build/macAppStore/input/`.
That classpath is `output + configurations["macAppStoreRuntimeClasspath"]` (line
433), which is built from the hand-curated `macAppStoreImplementation`
configuration only — it does NOT extend `implementation`. The curated list at
lines 441-483 declares Maven/coord deps (Coil, Decompose, Compose
material/materialIconsExtended, Ktor, Kotlinx, Koin, Kermit, Logback) and the
project deps — but `compose.desktop.currentOs` (line 474) is the only Compose
Desktop entry. **It is not enough**: Compose Desktop's actual runtime classpath
on macOS resolves to `skiko-awt-runtime-macos-arm64`, `compose-jb-runtime-desktop`,
`material3-desktop` etc., which only reach the project via the
`compose.desktop` DSL block applied to the `main` source set. The curated
`macAppStoreImplementation` does NOT inherit those resolution rules, so the
staged input directory will be missing the Compose Desktop runtime and the
Skiko native — the JVM in the packaged .pkg cannot start the Compose app at
all. (Side-effect: `MacAppStoreMain.kt` imports `compose.desktop.currentOs`
classes that are not on the classpath at runtime → `NoClassDefFoundError`
before the Window function is ever called.)

In addition, the `--main-jar` is hand-rolled by an inline Ant `jar` task
(lines 612-625) containing only `macAppStoreMainSourceSet.output.classesDirs`.
That excludes resource files like `icons/puklic-256.png` referenced via
`painterResource(WINDOW_ICON_RESOURCE)` (MacAppStoreMain.kt line 141, 176),
which also are not on any other jar — they live under the `main` source set's
processed-resources directory (`desktop/app/src/main/resources`). The main
source set is referenced only at compile time (line 432); its
`processResources` output is NOT included in `--input`, so resource loads at
runtime will throw or silently miss.

These two omissions together are the most plausible root cause for the
mission-brief "productbuild Puklic.app is not a bundle" failure: jpackage
performs a sanity check on the staged `.app` it just assembled before handing
off to productbuild; if the launcher cannot find the `--main-class`'s referenced
classes the .app structure may be left incomplete, leading to the rejection.
(jpackage itself emits a clearer error in modern JDKs, but the wording
"Puklic.app is not a bundle" is consistent with the .app missing its
`Contents/MacOS/<launcher>` because the launcher target failed to materialise.)

Suggested fix (NOT applied — read-only pass):

1. The macAppStore source set should include `sourceSets["main"].output` AND
   the processed resources directory in `runtimeClasspath`, AND should
   delegate the curated dep list to a configuration that does extend a
   carefully-pruned subset of `implementation`. Specifically:
   - Build `macAppStoreRuntimeClasspath` from a `macAppStoreImplementation`
     that extends `implementation` MINUS `:shared:voice` AND MINUS the GPL
     transitives. Use `exclude(...)` on those instead of re-listing every dep
     by hand. The hand-list approach has already drifted (Compose Desktop +
     resources missed).
   - Include `sourceSets["main"].resources.srcDirs` and run
     `processResources` so resources end up under `--input` either as a jar
     or via an extra resource-input directory.
2. Run jpackage with `--verbose` in CI so the actual error surface is logged.
3. Once a real `.app` is produced, productbuild's complaint will either
   disappear or change shape; the cert-not-found error path is already known
   to be a separate cert-keychain issue (FP-14a §3.4).

Disposition: BLOCKER. New issue (FP-14d-followup, link from #57). Must be
fixed before any further `packageMacAppStore` invocation.

---

### F-2 [BLOCKER] FP-14c codec wrappers are dead code — Mac App Store ship has no real `VoiceClient`

File: `desktop/app/src/macAppStore/kotlin/dev/puklic/desktop/macappstore/MacAppStoreMain.kt`
line 401: `val voiceClient: VoiceClient = NoOpVoiceClient()`.

The whole FP-14c module (`:desktop:platform-macos-appstore`,
JnaLibopusEncoder/Decoder, JnaVideoToolboxH264Encoder/Decoder,
JnaNwConnectionUdpTransport) is on `macAppStoreRuntimeClasspath` per line
453-461 of `desktop/app/build.gradle.kts`, but `MacAppStoreMain.kt` never
references any of those classes. The packaged `.pkg` therefore contains code
that is reachable only via reflection (it isn't reflected on, so it's truly
dead) — every chat / file / settings feature works, but voice + screencast
fall back to no-op. This contradicts the FP-14d architect report §3.2 which
explicitly states "voice IS wired via FP-14c factories" — the impl chose the
iOS-App-Store posture instead and the architect report's claim is now stale.

Additionally, `:shared:voice-codec` JVM artifact is pulled in via the FP-14c
module as `api(projects.shared.voiceCodec)` (build.gradle.kts of
`platform-macos-appstore`, line 40) — and the FP-14d consumer excludes
`org.bytedeco` from THAT specific project dep (line 460), but the shared
voice-codec JVM source set still ships its OWN `LibavOpusEncoder` /
`LibavOpusDecoder` classes that reference `org.bytedeco.ffmpeg.*` symbols. If
javacpp/ffmpeg-platform-gpl is excluded but `LibavOpusEncoder.class` is on
the macAppStore classpath, any reflective load of `:shared:voice-codec`'s JVM
flavor would crash with `NoClassDefFoundError`. Since
`AppleNativeVoiceClient` is supposed to land later (per FP-14a §4.2) and would
use FP-14c primitives instead of the Libav* path, this dead-class risk only
materialises if something else references the Libav classes — none does
today, so it's latent but real once any future code touches that module's JVM
source set.

Suggested fix (NOT applied):

1. File FP-14h (`AppleNativeVoiceClient`) tracking issue. Until that lands
   either (a) close FP-14c as "delivered but unused — kept as a building
   block for FP-14h" and document the gap in `docs/05_platforms/macos.md`,
   or (b) merge FP-14h into FP-14 cycle as a blocker for the .pkg first
   submission.
2. Per HARD RULE #2 ("never temporary, always conceptual") this should NOT
   ship as "v1 no voice, fix later" — either the architect report (FP-14a)
   needs to be amended to officially adopt the iOS-style no-voice posture
   AND the FP-14c module should be marked `tasks { … onlyIf { false } }`
   (i.e. not compiled into the .pkg) so the audit story stays clean, OR
   FP-14h must land before .pkg upload. The current state (modules present
   but unused) is exactly the "for now" middle ground HARD RULE #2 forbids.
3. Drop `api(projects.shared.voiceCodec)` from
   `:desktop:platform-macos-appstore/build.gradle.kts` and instead depend on
   `voice-api` only, since the codec interfaces actually consumed
   (`H264Encoder`, `OpusEncoder`, `VoiceUdpTransport`) all live in
   `voice-api` per the FP-14a §2.3 contract. This eliminates the
   Libav* dead-class risk mechanically.

Disposition: BLOCKER for the architectural narrative (FP-14a §0 claimed voice
would be wired). Either downgrade the FP-14a §0 voice claim to "deferred to
FP-14h" with explicit user reconfirmation, or block .pkg submission until
FP-14h.

---

### F-3 [BLOCKER] `--mac-package-name` is invalid jpackage flag — packaging will fail at argv parse

File: `desktop/app/build.gradle.kts` line 642.

jpackage exposes `--name`, `--mac-package-identifier`,
`--mac-package-signing-prefix`, and (since JDK 14) `--mac-package-name` was
removed / not present. The current jpackage option list on JDK 21 is:
`--mac-package-identifier`, `--mac-package-signing-prefix`,
`--mac-app-store`, `--mac-bundle-signing-prefix` — there is no
`--mac-package-name`. Passing it will produce
`Invalid Option: [--mac-package-name]` and the task fails. The intent was
already covered by `--name "Puklic"` (line 633).

Suggested fix: delete lines 642 ("--mac-package-name", "Puklic"). Verify with
`$JAVA_HOME/bin/jpackage --help | grep mac-package` on the same JDK CI uses.

Disposition: BLOCKER. Trivial fix; include in FP-14d follow-up.

---

### F-4 [BLOCKER] Info.plist override is in the wrong file name for jpackage to pick up

File: `dist/apple/macappstore/jpackage-resources/Info.plist`, referenced via
`--resource-dir` at line 647 of `desktop/app/build.gradle.kts`.

jpackage's `--resource-dir` does not look for a file named `Info.plist`. The
documented override file name on macOS for the Info.plist template is
`Info-lite.plist.template` (legacy) or, for current JDK 21, the resource is
named `Info.plist` BUT must be inside a `package/macosx/` subdirectory of the
`--resource-dir` (see `man jpackage` in JDK 21 and openjdk-jpackage source
`jdk.jpackage.internal.MacAppBundler#getConfigRootDirOverridesPath`). The
file at `dist/apple/macappstore/jpackage-resources/Info.plist` will be
silently ignored. jpackage will then auto-inject its default category
(`public.app-category.utilities`) and the boilerplate
`NSMicrophoneUsageDescription` ("The application Puklic is requesting access
to the microphone.") — both explicitly called out in FP-14a §3.3 as MUST be
overridden.

Suggested fix: move the override file to
`dist/apple/macappstore/jpackage-resources/macosx/Info.plist` (the `macosx/`
subfolder is mandatory) AND verify with a probe run (`jpackage --type
app-image --resource-dir … --verbose` and `plutil -p Contents/Info.plist` on
the output). The architect probe in FP-14a §3 used a custom Info.plist but
did not actually exercise the override-merge path — re-probe.

Disposition: BLOCKER for App Review (the default NSMicrophoneUsageDescription
string is a guaranteed App Review rejection; the wrong
LSApplicationCategoryType is a less-severe but still bad signal). Trivial fix.

---

### F-5 [BLOCKER] `--java-options "-Djna.library.path=$APPDIR/../Resources"` is shell-expanded by `Exec` task, not by jpackage

File: `desktop/app/build.gradle.kts` line 649.

The string `"-Djna.library.path=\$APPDIR/../Resources"` is passed to Gradle's
`Exec.commandLine(...)`, which spawns jpackage directly without a shell, so
`$APPDIR` is a literal — there is no shell expansion AND jpackage does NOT
expand `$APPDIR` itself (jpackage just embeds whatever string is given into
the launcher cfg). At launch the JVM receives the literal `-Djna.library.path=$APPDIR/../Resources`,
and JNA tries to load `opus` from a directory literally named `$APPDIR`, which
doesn't exist → `UnsatisfiedLinkError`. Voice / codec calls will crash on first
use.

The conventional path is to resolve the dylib via an absolute path computed at
JVM startup, OR to use jpackage's documented launcher variable `${APPDIR}`
(braces required for the jpackage substitution to recognise the token). Even
then jpackage only expands `${APPDIR}` in certain contexts; safer is to compute
the path in Kotlin at startup (`System.getProperty("compose.application.resources.dir")`
or `URLDecoder`-on-codesource-jar).

Suggested fix:
- Replace with `-Djna.library.path=${APPDIR}/../Resources` AND verify against
  the JDK 21 jpackage variable substitution doc; OR
- Drop the `--java-options` entirely and resolve in `MacAppStoreMain.kt`
  before the first JNA call (e.g.
  `System.setProperty("jna.library.path", File(System.getProperty("compose.application.resources.dir")).absolutePath)`).

Disposition: BLOCKER once F-1/F-2 land (currently the wrappers aren't called
so the missing dylib path is latent — but any later FP-14h work hits it
immediately).

---

### F-6 [BLOCKER] `MAC_PROVISIONING_PROFILE_BASE64` secret name mismatch between Fastfile env-var doc and workflow

File: `.github/workflows/mac-app-store.yml` line 80 uses
`secrets.MAC_PROVISIONING_PROFILE_BASE64`. `fastlane/Fastfile` lines 88-103
documents the secret as `MAC_APP_PROVISIONING_PROFILE_BASE64`. The two will
not refer to the same GitHub secret. If the user follows the Fastfile comment
when registering secrets they will set `MAC_APP_PROVISIONING_PROFILE_BASE64`
and the workflow will write a 0-byte provisioning profile file at line 83,
leading to a codesign failure at jpackage time.

Suggested fix: pick one canonical name (`MAC_PROVISIONING_PROFILE_BASE64` is
shorter and matches FP-14a §7 doc) and align Fastfile doc + workflow + commit
message. Also align profile filename consistently — workflow installs as
`Puklic_Mac_App_Store.provisionprofile`, jpackage does not consume it via
filename so this is cosmetic but worth standardising for grep.

Disposition: BLOCKER; trivial doc/workflow fix in FP-14e follow-up.

---

### F-7 [MAJOR] `JnaNwConnectionUdpTransport` leaks the connection's `nw_release` AND the per-connection dispatch queue

File: `desktop/platform-macos-appstore/src/main/kotlin/.../transport/JnaNwConnectionUdpTransport.kt`,
`close()` line 141-149.

`close()` calls `nw_connection_cancel(connection)` but never
`nw.nw_release(connection)`. Per Apple's documentation
`nw_connection_create` returns a retained object; the caller must
`nw_release` it once cancelled. Without that the `nw_connection_t` and all
of its backing TCP/UDP state (sockets, dispatch sources, TLS context) leak —
significant memory cost per voice channel join/leave cycle.

Same issue with `params` (`nw_parameters_create_secure_udp` line 72) and the
two `nw_endpoint_create_host` results (lines 77, 80) — all four are retained
and must be `nw_release`d. Currently none of them are.

`queue` (`dispatch_queue_create` line 56) is also retained; `dispatch_release`
must be called in `close()` (libdispatch).

The `JnaNwConnectionUdpTransport` instance can be created many times during
normal Discord voice channel hopping; over a session this leaks a meaningful
amount of native memory.

Suggested fix: in `close()` add:
```kotlin
nw.nw_release(connection)
// params + endpoints must be tracked in fields too; today they're locals
nw.nw_release(params)
nw.nw_release(remoteEndpoint)
localBindEndpoint?.let { nw.nw_release(it) }
dispatch.dispatch_release(queue)
```
Convert local vals in `init` to fields so `close()` can reach them.

Disposition: MAJOR. Fix as part of FP-14h or a FP-14c follow-up issue. Note
that on macOS 10.15+ many `nw_*` objects are ARC-managed under-the-hood when
imported from Swift/ObjC, but the C ABI we're targeting via JNA still
requires explicit balance.

---

### F-8 [MAJOR] `AppleBlock.Keepalive` for the state-change handler is not released across `close()`; `receiveKeepalive` is replaced under no synchronisation

File: `JnaNwConnectionUdpTransport.kt` lines 68, 89, 184-185.

`stateBlock` Keepalive is held for the life of the JnaNwConnectionUdpTransport
instance (fine in itself); but after `close()` the Memory backing the block
is still strongly referenced and never released. JNA `Memory.dispose()` (or
just letting it go GC-eligible) is the path; today the field is `val` so it
lives until the transport instance is unreachable. That's acceptable; flagging
for awareness.

The real bug is `receiveKeepalive` (line 184-185): on every re-arm the field
is overwritten WITHOUT synchronisation. The previous keepalive may still be
in-flight on Network.framework's queue — Apple's documentation does not
guarantee the prior receive completion has fully returned by the time the next
`nw_connection_receive_message` callback fires (they're serial on the same
queue, so in practice yes — but the field WRITE happens before
`nw_connection_receive_message` is called, and the field READ on the previous
block is a Java reference that's no longer reachable, so GC of the prior
Memory + JNA Callback trampoline race could let the trampoline be unmapped
mid-invocation if the prior block is still being torn down by Apple).

Suggested fix: hold the prior keepalive in a local that survives until after
`nw_connection_receive_message` returns to JNA; or use a `LinkedBlockingDeque`
of keepalives and drop old entries via a counter. The minimum-complexity fix
is to mark `receiveKeepalive` `@Volatile` (already is) AND keep a
`LinkedList` of "in-flight" keepalives, GCing them only after a few cycles.

Disposition: MAJOR. Race is narrow but real. Track in FP-14h or a dedicated
issue.

---

### F-9 [MAJOR] `incomingChannel` failure path during `STATE_FAILED` sets `closed` to `true` without going through `compareAndSet` — `close()` is then a no-op and `nw_connection_cancel` is never called

File: `JnaNwConnectionUdpTransport.kt` lines 227 and 234 (`closed.set(true)`
inside `handleStateChange`).

If Network.framework transitions to STATE_FAILED, the code sets `closed` to
true directly (line 227). A subsequent caller-side `close()` then sees
`closed.compareAndSet(false, true)` returns false and skips
`nw_connection_cancel(connection)`. Since Apple's docs say a `failed`
connection still needs `nw_connection_cancel` to free OS resources, this
leaves the connection in a half-released state.

Suggested fix: replace `closed.set(true)` with a private helper
`markClosedAndCancel()` that runs the same teardown as `close()`.

Disposition: MAJOR. Same family as F-7.

---

### F-10 [MAJOR] `JnaVideoToolboxH264Encoder.close()` calls `CFRelease(session)` on a session already released by `VTCompressionSessionInvalidate`

File: `JnaVideoToolboxH264Encoder.kt` lines 126-132 (encoder),
`JnaVideoToolboxH264Decoder.kt` lines 143-154 (decoder).

`VTCompressionSessionCreate` returns a +1-retained CF object. The standard
release pattern documented by Apple is `VTCompressionSessionInvalidate(s);
CFRelease(s);`. Both lines look correct on first read.

HOWEVER: `compressionCallback` (line 52) is held strongly in the encoder
instance. The JNA Callback trampoline → C function pointer mapping is
process-lifetime once `CallbackReference.getFunctionPointer` is called (FP-14d
report glosses this). On `close()` the trampoline remains valid which is
correct, but if VT's internal queue has a final delivery in flight when we
release the session, the callback runs after `closed = true` (line 128) and
the `synchronized(this) { outputs.addLast(...) }` block (line 210-213) silently
grows the queue forever (no consumer after `close()`). Not a crash, but a tiny
memory growth on every close.

More important: the `lastTs90k` increment (line 211) happens in the callback
thread, and `frameIndex` (line 107) is written from the caller thread —
neither is volatile / synchronized. With AllowFrameReordering=false +
RealTime=true the callback DOES fire synchronously inside
`VTCompressionSessionEncodeFrame`, so in practice no race; but the architecture
report describes the JNA approach as "follows the iOS impl exactly" — the iOS
impl actually offloads via a Channel because Kotlin/Native's
`staticCFunction` can't capture state. The "synchronous = safe" assumption is
only true under the exact property bundle set at lines 89-94; if any
property fails to apply (the check at line 298 throws, but if VT silently
ignores a property), reordering returns and the assumption breaks.

Suggested fix: mark `frameIndex` `@Volatile`, wrap `lastTs90k` reads/writes in
`synchronized(this)`, and gate the callback's enqueue on `!closed`. Better
still: rewrite to mirror iOS's Channel-based receive — the synchronisation
becomes a non-issue.

Disposition: MAJOR. Latent under the current property set but fragile.

---

### F-11 [MAJOR] `JnaVideoToolboxH264Decoder.decode()` writes to `lastFrame` on the VT queue and reads on the caller thread without any memory barrier

File: `JnaVideoToolboxH264Decoder.kt` lines 43, 58, 130, 140.

The decoder's callback writes `lastFrame = readBgraAsArgb(imageBuffer)` (line
58). The caller thread reads `return lastFrame` (line 140). Neither is
volatile. JIT can hoist or stale-read. With
`VTDecompressionSessionDecodeFrame` documented as synchronous when no
`kVTDecodeFrame_EnableAsynchronousDecompression` flag is passed (line 134
passes `decodeFlags = 0`), the callback DOES fire before the call returns —
but happens-before across thread boundaries requires a release/acquire pair,
which neither field write nor field read provides.

Suggested fix: `@Volatile var lastFrame: IntArray? = null`. Trivial.

Disposition: MAJOR. JMM correctness bug.

---

### F-12 [MAJOR] `JnaLibopusEncoder.encode()` / `JnaLibopusDecoder.decode()` are not thread-safe but expose no failure mode if multiple threads enter; doc says "NOT thread-safe — one instance per stream" but `close()` race exists

File: `JnaLibopusEncoder.kt` lines 65-82 (encode/close),
`JnaLibopusDecoder.kt` similar.

`closed` is a plain `var Boolean`. Worst case: encoder is `close()`d from one
thread (`opus_encoder_destroy(state)` runs) while another thread is in
`encode()` → JNA hands `state` to libopus → libopus reads freed memory → SIGSEGV.

This is theoretically out-of-contract per the "one instance per stream" doc,
but real-world usage often does close-on-disconnect from a different
coroutine than the encode loop. Make `close()` idempotent and serialise with
encode using an `AtomicBoolean` + `synchronized` block, or a single
`ReentrantLock`.

Suggested fix: replace `var closed: Boolean` with `private val closed =
AtomicBoolean(false)`, wrap `encode` + `close` in `synchronized(state)` (or
similar). Doc comment "NOT thread-safe" stays but the close-during-encode
crash mode is removed.

Disposition: MAJOR.

---

### F-13 [MAJOR] `verifyMacAppStoreNoGplDeps` walks only `macAppStoreRuntimeClasspath`; the FP-14d-staged jpackage `--input` directory is built from the same path BUT extends `macAppStoreImplementation` which excludes `org.bytedeco` only from `:desktop:platform-macos-appstore` — `:shared:voice-codec`'s OWN JVM artifact still ships `LibavOpusEncoder` referencing org.bytedeco classes

File: `build-logic/src/main/kotlin/MacAppStoreGplChecker.kt`,
`desktop/app/build.gradle.kts` lines 453-461.

The exclude at line 460 (`exclude(group = "org.bytedeco")`) is scoped to the
`:desktop:platform-macos-appstore` consumer edge only. `:shared:voice-codec`'s
JVM artifact is pulled twice transitively in real-world resolution: once via
that excluded edge, and once via direct exposure as
`api(projects.shared.voiceCodec)` in the platform-macos-appstore
build.gradle.kts (line 40). Because that `api` propagates `voice-codec`'s own
runtimeClasspath to consumers, the second path may or may not honour the
exclusion depending on Gradle resolution ordering.

Run `./gradlew :desktop:app:dependencies --configuration
macAppStoreRuntimeClasspath` to verify; the FP-14d report claims
"BUILD SUCCESSFUL" for `verifyMacAppStoreNoGplDeps` so today's coords don't
trigger it, but the GPL Libav* classes are present in `voice-codec.jar` even
without `org.bytedeco` JARs. A class-level scanner is the only way to be
safe; coord-based filtering is insufficient when one .jar carries both
Apache-2.0 commonMain + GPL-tainted JVM source-set classes.

Suggested fix: split `:shared:voice-codec` into `voice-codec-api` (commonMain
only) + `voice-codec-libav` (JVM GPL impl). Mac App Store consumer depends
only on the API split. This is the only mechanically-honest fix and matches
the FP-14a §2.3 architectural intent.

Disposition: MAJOR. Architectural debt — schedule under FP-14h or a fresh
issue.

---

### F-14 [MAJOR] FP-14e workflow does not delete imported .p12 + provisioning profile on cleanup; only the keychain is deleted

File: `.github/workflows/mac-app-store.yml` lines 90-95.

The `Cleanup keychain` step runs `security delete-keychain build.keychain ||
true` but does NOT delete:
- `$HOME/Library/MobileDevice/Provisioning Profiles/Puklic_Mac_App_Store.provisionprofile`
- `$ASC_KEY_PATH` (line 26: `${{ github.workspace }}/.appstoreconnect/AuthKey.p8`)

On `actions/checkout` re-use across jobs in a self-hosted scenario this could
leak credentials. On `macos-15` hosted runners the VM is destroyed after the
job — but security-hygiene-wise the cleanup should be explicit. The .p12 is
already deleted inline (lines 62, 76), good.

Suggested fix: add a final cleanup step that `rm -f` the provisioning profile
+ `AuthKey.p8`, also `if: always()`.

Disposition: MAJOR for self-hosted; MINOR for managed runners. Trivial fix.

---

### F-15 [MAJOR] `setup_ci` in fastlane lane creates ANOTHER temporary keychain — collides with the workflow's `build.keychain`

File: `fastlane/Fastfile` line 108.

`setup_ci` (from fastlane-plugin or built-in) creates `fastlane_tmp_keychain`
and adds it to the search list. The workflow has already created
`build.keychain` and run `security default-keychain -s build.keychain` (line
74). The two operations don't fail together but the order-of-precedence after
`setup_ci` is "fastlane_tmp_keychain first, build.keychain second", and
`productbuild` may not find the Mac Installer Distribution cert if fastlane's
keychain becomes the codesign target by default.

Suggested fix: drop `setup_ci if ENV["CI"]` — the workflow already handles
keychain provisioning explicitly. OR drop the workflow's keychain steps and
rely on setup_ci + match.

Disposition: MAJOR.

---

### F-16 [MAJOR] HARD RULE #2 violation — FP-14d report §3.1 says "the iOS-App-Store-style 'voice is not wired in v1 of the Mac App Store ship' decision applies", and `MacAppStoreMain.kt` line 113-117 documents this as "follow-up conceptual slice — NOT a temporary in this slice, but a parallel deliverable tracked separately"

File: `desktop/app/src/macAppStore/kotlin/dev/puklic/desktop/macappstore/MacAppStoreMain.kt`
lines 113-117. Architect: `2026-05-29-fp14d-gradle-packaging.md` line 31 ("voice is
not wired in v1 of the Mac App Store ship"), `2026-05-29-fp14a-mac-app-store-architect.md`
§0 ("voice IS wired via FP-14c factories" — contradicts).

This is precisely the "for now / phase 2" pattern HARD RULE #2 forbids:
shipping `NoOpVoiceClient` with documented intent to swap it out later. The
in-source comment trying to defuse this by calling it "a parallel deliverable
tracked separately" is the rhetorical pattern the rule was written against
(see CLAUDE.md project-level §"Forbidden patterns" — "`// disable for now,
re-enable when needed` — if it's needed eventually, leave it; if not, delete
it").

The conceptually-correct paths are (pick one):
1. Block FP-14d/e completion on FP-14h; do not commit `NoOpVoiceClient` to
   the Mac App Store source set. Update FP-14a §0 to reflect.
2. Officially adopt the iOS-style no-voice posture for the Mac App Store ship
   too. In that case: update FP-14a §0 (it currently asserts the opposite),
   FP-14c is DEAD CODE (close issue #56 as "deliverable kept for reference,
   not shipped"), update voice scope in `docs/00_overview/product-vision.md`
   + `docs/07_roadmap/phases.md`.

The middle ground in the current code IS the forbidden temporary.

Suggested fix: re-engage user for explicit decision per HARD RULE #2 escape
hatch ("Block — file an issue documenting the prerequisite + stop" or "Do it
fully").

Disposition: MAJOR. Process violation. Needs user input. Track via Issue
update on #59.

---

### F-17 [MINOR] Dispatch.kt is loaded via `Native.load("System", …)` but exposes `dispatch_data_create` whose 3rd `queue` parameter is documented as the queue *the destructor runs on*, not where data is created — the wrapper passes the main queue unconditionally

File: `Dispatch.kt`, `JnaNwConnectionUdpTransport.kt` lines 245, 255.

`dispatch_data_create(buffer, size, queue, destructor)` — `queue` is the
queue on which `destructor` is invoked. We pass `null` destructor + main
queue. With null destructor libdispatch uses
DISPATCH_DATA_DESTRUCTOR_DEFAULT, which is `free()`. But the buffer comes
from a JNA `Memory` (managed by the JVM GC), so calling `free()` on it is
undefined behaviour — JNA's `Memory` owns native memory it allocated via
`malloc` so technically `free()` on the same pointer succeeds, BUT JNA also
maintains its own ref tracking and will later try to free again on GC →
double-free.

Per Apple docs the canonical "I'll keep the buffer alive for you" pattern is
`DISPATCH_DATA_DESTRUCTOR_DEFAULT` (which is documented to copy the buffer at
creation time when destructor is `_dispatch_data_destructor_default`). Recent
libdispatch DOES copy in that case, so the double-free risk is theoretical
but the contract is murky.

Suggested fix: explicitly pass `DISPATCH_DATA_DESTRUCTOR_FREE` (a symbol we'd
expose) so the destructor IS `free()` — and allocate via `Native.malloc` so
JNA does NOT track it. OR copy the bytes into a malloc'd Memory and dispose
ourselves once the send completes. The current code is most likely safe
because libdispatch copies under default destructor, but the JNA `Memory`
lifetime + dispatch_data's internal copy is not air-tight.

Disposition: MINOR but worth a deliberate decision.

---

### F-18 [MINOR] `Network.DISABLE_PROTOCOL` global pointer is read via `getGlobalVariableAddress` but the symbol name `_nw_parameters_configure_protocol_disable` is the *function name*, not a global variable — JNA will resolve the function pointer, not a sentinel

File: `Network.kt` lines 75-85.

`NW_PARAMETERS_DISABLE_PROTOCOL` in Apple's headers is a macro:
`#define NW_PARAMETERS_DISABLE_PROTOCOL ((nw_parameters_configure_protocol_block_t)_nw_parameters_configure_protocol_disable)`.
So passing the function pointer cast to a block IS the correct sentinel.
However `nw_parameters_create_secure_udp` takes `nw_parameters_configure_protocol_block_t`
parameters which Apple defines as Objective-C blocks, not function pointers.
At runtime the call may work because libnetwork checks for the specific
sentinel pointer value (== `&_nw_parameters_configure_protocol_disable`) and
short-circuits before invoking it as a block — but this is private ABI and
not guaranteed across macOS releases.

Suggested fix: forge a real empty block via `AppleBlock.forge { ... no-op
... }` for the DISABLE case (or use `NW_PARAMETERS_DISABLE_PROTOCOL`
equivalent semantics by passing `nw_parameters_create()` without UDP option
configuration). Pragmatic: keep current code but add a runtime check that the
sentinel pointer is non-null and document the ABI dependency.

Disposition: MINOR — fragile across macOS major versions.

---

### F-19 [MINOR] `CompressionOutputCallback` interface defines `outputCallbackRefCon` as `Pointer?` but the C signature passes a non-null context — JNA may auto-box differently

File: `bridge/VideoToolbox.kt` lines 17-25.

Cosmetic. JNA handles null/non-null `Pointer` the same way (it just wraps a
non-zero address). Not actionable.

Disposition: NIT.

---

### F-20 [MINOR] `JnaLibopusEncoder.MAX_OPUS_PACKET_BYTES = 4000` exceeds the documented Discord voice packet RTP MTU (1275 bytes for Opus 48kHz max)

File: `JnaLibopusEncoder.kt` line 97.

4000 is libopus's documented maximum buffer for a frame at any bitrate; that's
fine as a heap-side maxlen. The trim to `copyOf(written)` returns the actual
encoded size, which for VoIP-class bitrates is < 200 bytes per 20 ms frame.
No bug — but the constant is misleading given Discord's MTU. Comment to
clarify it's an upper bound, not a target.

Disposition: NIT.

---

### F-21 [MINOR] `MacAppStoreMain.kt` `configureDockIcon()` reads `DOCK_ICON_RESOURCE` via classloader — the resource will not be on the macAppStore source set's classpath (resources live in main `src/main/resources` which is not on `macAppStoreRuntimeClasspath`)

File: `MacAppStoreMain.kt` lines 172-180.

Same family as F-1's resource-omission point. The icon load is wrapped in
`runCatching { ... }` (line 174) so it fails silently; the user sees a
generic JVM icon in the Dock instead of Puklic's. Not a crash, just degraded
UX.

Suggested fix: roll into F-1 fix.

Disposition: MINOR.

---

### F-22 [MINOR] Several `@Suppress("UNUSED_VARIABLE")` in `MacAppStoreDependencyGraph.create()` (lines 231-236) point at dead code paths copied from the DMG `DependencyGraph`

File: `MacAppStoreMain.kt` lines 231, 233, 235.

`localDrafts`, `readState`, `attachmentCache` are constructed and discarded.
Either they're needed (then wire them into the orchestrators) or they're not
(then drop the construction). Suppressing unused-variable warnings to ship
dead init code is exactly the pattern HARD RULE #2 forbids.

Suggested fix: drop the lines or pass into the orchestrators.

Disposition: MINOR — also a smaller HARD RULE #2 violation than F-16.

---

### F-23 [MINOR] `MacAppStoreDependencyGraph` field `database` is exposed publicly but never read by `MacAppStoreMain` or any other consumer

File: `MacAppStoreMain.kt` line 207.

Public surface for no caller. Move to `internal` or drop.

Disposition: NIT.

---

### F-24 [MINOR] FP-14c report §6 build script uses `clang -arch arm64 -arch x86_64`, producing a universal dylib — but Mac App Store hardened-runtime + .pkg only ships arm64 (Intel Macs are out of scope per CLAUDE.md `## Platforms`)

File: `dist/apple/build-libopus-dylib-from-xcframework.sh` line 35-38.

Bundling x86_64 wastes ~500 KB in the .pkg and slightly slows codesign. Not a
correctness issue since arm64-only Macs ignore the x86_64 slice, but it
contradicts the documented arm64-only macOS scope.

Suggested fix: drop `-arch x86_64`. Verify with `lipo -info` post-build.

Disposition: MINOR. Roll into FP-14g.

---

## Top 3 BLOCKER recap

| # | What | Fix scope |
|---|---|---|
| F-1 | `packageMacAppStore` `--input` is missing Compose Desktop runtime + main `processResources` → unrunnable .app → productbuild rejects | FP-14d follow-up issue, NEW |
| F-2 | FP-14c codec wrappers are unused; Mac App Store ship has no real voice → architect promise broken | User decision: either FP-14h before submission, or amend FP-14a §0 + close FP-14c as kept-but-not-shipped |
| F-3 | `--mac-package-name` is not a jpackage flag → argv parse fails | FP-14d follow-up issue, NEW (trivial) |

F-4 (Info.plist subdir), F-5 (`$APPDIR` not expanded), F-6 (secret-name mismatch)
are the other BLOCKERs; each is a small surgical fix.

## Disposition recommendation

- **This FP-14 cycle**: F-3, F-4, F-5, F-6, F-14, F-15, F-22, F-23, F-24
  (small follow-up issue, mechanical fixes). Bundle as a single FP-14d/e
  patch PR.
- **Requires user input before any fix**: F-2 and F-16 (HARD RULE #2 escape
  hatch — voice shipped vs deferred decision).
- **New issues / FP-14h scope**: F-1, F-7, F-8, F-9, F-10, F-11, F-12, F-13
  (architectural and threading work, larger than a patch).
- **NITs only**: F-17 through F-24 not already listed above.

## What this report does NOT do

- Does not modify any source / build / test / fastlane / workflow file.
- Does not close #59 (closes only after findings are addressed).
- Does not file follow-up issues (caller's responsibility per the mission
  brief — "fixing issues is FP-14d/e revisits or FP-14h").

## References

- `docs/03_infrastructure/architect-reports/2026-05-29-fp14a-mac-app-store-architect.md`
- `docs/03_infrastructure/architect-reports/2026-05-29-fp14c-codec-wrappers.md`
- `docs/03_infrastructure/architect-reports/2026-05-29-fp14d-gradle-packaging.md`
- Issue #59
- HARD RULE #1 step 7 (code-critic READ-ONLY)
- Repo CLAUDE.md HARD RULE #2 (NEVER TEMPORARY)
