import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.nio.file.Files
import java.nio.file.Paths

plugins {
    id("puklic.jvm-library")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
}

// Single source of truth for the packaging version. Defined in root
// `gradle.properties` as `puklic.version`. Drives jpackage `packageVersion`
// on ALL platforms (Linux .deb / .AppImage AND macOS .dmg — no per-OS
// override) and the generated `dev.puklic.desktop.update.Version.CURRENT`
// constant consumed by `UpdateChecker`.
val puklicVersion: String = (project.findProperty("puklic.version") as? String)
    ?: error(
        "puklic.version missing in gradle.properties — it is the single source of " +
            "truth for the packaging version. Set it at the repo root and re-run.",
    )

dependencies {
    implementation(projects.shared.composeUi)
    implementation(projects.shared.session)
    implementation(projects.shared.voice)
    implementation(projects.shared.platformApi)
    implementation(projects.shared.domain)
    implementation(projects.shared.ids)
    implementation(projects.shared.repositories)
    implementation(projects.shared.persistenceApi)
    implementation(projects.shared.persistenceSqldelight)
    implementation(projects.shared.protocolDiscord)
    implementation(projects.desktop.platformLinux)
    implementation(projects.desktop.platformMacos)
    implementation(projects.desktop.platformWindows)
    implementation(libs.koin.core)
    implementation(libs.decompose)
    implementation(libs.essenty.lifecycle.coroutines)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.coil)
    implementation(libs.coil.core)
    implementation(libs.coil.network.ktor3)
    implementation(libs.kermit)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.logback.classic)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

compose.desktop {
    application {
        mainClass = "dev.puklic.desktop.MainKt"
        nativeDistributions {
            // Linux is the primary target (per CLAUDE.md). macOS and Windows are
            // produced from their respective host runners. jpackage cross-compile is
            // not supported; each runner emits formats native to its host. Compose
            // Desktop rejects formats that don't match the current OS, so we filter
            // by os.name here.
            // Scope per docs/07_roadmap/phases.md §Platforms (updated 2026-05-29
            // FP-10, issue #50):
            //   - Linux   = officially shipped (.deb + .AppImage)
            //   - macOS   = officially shipped (.dmg)
            //   - Windows = officially shipped (.exe + .msi)
            val osName = System.getProperty("os.name").lowercase()
            val formats = when {
                osName.contains("linux") -> arrayOf(TargetFormat.Deb, TargetFormat.AppImage)
                osName.contains("mac") -> arrayOf(TargetFormat.Dmg)
                osName.contains("windows") -> arrayOf(TargetFormat.Exe, TargetFormat.Msi)
                else -> arrayOf<TargetFormat>()
            }
            targetFormats(*formats)
            packageName = "Puklic"
            packageVersion = puklicVersion
            description = "Lightweight native Discord client (Compose Multiplatform)"
            copyright = "© 2026 Jan Damek. Apache-2.0."
            vendor = "Jan Damek"

            // Runtime modules required by FFmpeg/JavaCPP, ktor, kotlinx, dbus-java.
            // jlink-style modules list; jpackage uses this to assemble the bundled JRE.
            modules(
                "java.naming",
                "java.management",
                "java.sql",
                "java.net.http",
                "jdk.unsupported",
                "jdk.crypto.ec",
            )

            linux {
                iconFile.set(rootProject.file("icons/linux/512x512/puklic.png"))
                appCategory = "Network"
                debMaintainer = "damek@mazlusek.com"
                menuGroup = "Internet"
                appRelease = "1"
                rpmLicenseType = "Apache-2.0"
                // Note: Compose Desktop 1.9.x (jpackage under the hood) does NOT
                // expose a DSL property for Debian `Depends:` and jpackage itself
                // has no `--linux-package-deps` flag. The runtime dependency on
                // `libsecret-tools` (needed by LinuxSecureStorage to invoke the
                // `secret-tool` CLI) is injected post-build by the
                // `patchDebPostBuild` task below — see issues #15 and #20.
            }
            macOS {
                iconFile.set(rootProject.file("icons/macos/puklic.icns"))
                bundleID = "cz.damek.puklic"
                appStore = false
                dockName = "Puklic"
                // No packageVersion override: the unified `puklic.version` from
                // gradle.properties applies on macOS too. CFBundleShortVersionString
                // (which jpackage derives from packageVersion for the .dmg/.app bundle)
                // accepts 0.x.y — the historical "MAJOR > 0" rule is App Store
                // specific (`appStore = false` here) and does not apply to
                // standalone jpackage .dmg distribution.
            }
            windows {
                // upgradeUuid is the MSI UpgradeCode — Windows uses this stable GUID
                // to detect "this is an upgrade of an existing install of the same
                // product" rather than a side-by-side parallel install. It MUST stay
                // constant across every Puklic version released; bumping it would
                // make every release install side-by-side instead of upgrading.
                // Generated 2026-05-29 specifically for Puklic.
                upgradeUuid = "b3c4a1d0-e7f5-4d8a-9c3e-6f2a1b8d5e4f"
                menuGroup = "Puklic"
                // perUserInstall = true sidesteps UAC elevation for the install —
                // the .msi writes under %LOCALAPPDATA% rather than Program Files.
                perUserInstall = true
                shortcut = true
                dirChooser = true
                console = false
                // No iconFile: jpackage requires .ico (not .png); we deliberately
                // do not commit a binary .ico to the repo. jpackage falls back to
                // its bundled default icon, which is acceptable for v1. A real
                // branded .ico can be added later by checking in
                // `icons/windows/puklic.ico` and setting iconFile here — no other
                // packaging change needed.
            }
        }
    }
}

// Forward selected -D system properties from the Gradle JVM to the application JVM so
// `./gradlew :desktop:app:run -Dpuklic.dev.autotest=true` actually reaches Main. Compose
// Desktop's `run` task does not propagate -D values by default.
tasks.withType<JavaExec>().configureEach {
    listOf("puklic.dev.autotest").forEach { key ->
        val value = System.getProperty(key)
        if (value != null) systemProperty(key, value)
    }
}

// ---------------------------------------------------------------------------
// patchDebPostBuild — single conceptual "make this .deb FHS-correct" step
// applied as a post-process to the .deb produced by Compose Desktop / jpackage.
//
// Two concerns are merged into one dpkg-deb roundtrip (extract once, repack
// once) so we never leave the .deb in a half-patched intermediate state:
//
//   1. Inject Debian `Depends:` line (issue #15). LinuxSecureStorage shells
//      out to `secret-tool` (from libsecret-tools) to persist Discord tokens
//      via the Secret Service API. Without it the first login throws
//      PlatformUnavailable. jpackage has no `--linux-package-deps` flag and
//      Compose 1.9.x exposes no DSL property for it.
//
//   2. FHS integration (issue #20). jpackage ships everything under
//      /opt/puklic/ and does NOT expose the binary on PATH, the .desktop
//      entry in /usr/share/applications, or the icon in /usr/share/pixmaps.
//      Users had no `puklic` command and the app didn't appear in menus.
//      We add:
//        - /usr/bin/puklic               -> symlink to /opt/puklic/bin/Puklic
//        - /usr/share/applications/puklic.desktop  (Exec/Icon rewritten to
//                                                   absolute / themed paths)
//        - /usr/share/pixmaps/puklic.png            (icon copy)
//
// Runs only on Linux build agents (skipped on macOS dev builds — they emit
// only .dmg). AppImage users install libsecret-tools manually (AppImage
// does not honor .deb deps) and rely on the AppImage's own desktop entry.
// AUR PKGBUILD performs the same FHS bridge defensively (idempotent — the
// symlinks/files are already correct in v0.1.1+ .debs).
// ---------------------------------------------------------------------------
val debRuntimeDependencies = listOf(
    "libsecret-tools",
)

val patchDebPostBuild = tasks.register("patchDebPostBuild") {
    description = "Post-processes the .deb produced by packageDeb: injects Depends (#15) and FHS symlinks (#20)."
    group = "compose desktop"

    val debDir = layout.buildDirectory.dir("compose/binaries/main/deb")
    val depsLine = debRuntimeDependencies.joinToString(", ")

    onlyIf {
        val os = System.getProperty("os.name").lowercase()
        os.contains("linux") && debDir.get().asFile.exists()
    }

    doLast {
        val dir = debDir.get().asFile
        val debs = dir.listFiles { f -> f.isFile && f.name.endsWith(".deb") }
            ?: emptyArray()
        if (debs.isEmpty()) {
            logger.warn("patchDebPostBuild: no .deb files found in $dir; skipping")
            return@doLast
        }
        for (deb in debs) {
            logger.lifecycle("patchDebPostBuild: post-processing ${deb.name}")
            val work = layout.buildDirectory.dir("tmp/patchDeb/${deb.nameWithoutExtension}").get().asFile
            work.deleteRecursively()
            work.mkdirs()
            // Extract full .deb (control + data) into work dir.
            exec {
                workingDir = work
                commandLine("dpkg-deb", "-R", deb.absolutePath, work.absolutePath)
            }.assertNormalExitValue()

            // --- (1) Depends injection ------------------------------------
            val control = File(work, "DEBIAN/control")
            check(control.exists()) { "DEBIAN/control missing in extracted ${deb.name}" }
            val original = control.readText()
            val patched = buildString {
                var dependsWritten = false
                for (line in original.lineSequence()) {
                    if (line.startsWith("Depends:", ignoreCase = true)) {
                        val existing = line.substringAfter(":").trim()
                        val merged = if (existing.isEmpty()) depsLine else "$existing, $depsLine"
                        appendLine("Depends: $merged")
                        dependsWritten = true
                    } else if (line.isNotEmpty() || dependsWritten) {
                        appendLine(line)
                    }
                }
                if (!dependsWritten) {
                    if (!endsWith("\n")) append("\n")
                    append("Depends: $depsLine\n")
                }
            }
            control.writeText(patched)
            logger.lifecycle("patchDebPostBuild:   injected Depends '$depsLine'")

            // --- (2) FHS integration --------------------------------------
            // Compose Desktop layout under work/:
            //   opt/puklic/bin/Puklic
            //   opt/puklic/lib/puklic-Puklic.desktop
            //   opt/puklic/lib/Puklic.png
            val optBin = File(work, "opt/puklic/bin/Puklic")
            val optDesktop = File(work, "opt/puklic/lib/puklic-Puklic.desktop")
            val optIcon = File(work, "opt/puklic/lib/Puklic.png")

            // /usr/bin/puklic symlink (lowercase per Arch / common-shell convention).
            val usrBin = File(work, "usr/bin").apply { mkdirs() }
            val launcherLink = File(usrBin, "puklic")
            if (launcherLink.exists() || Files.isSymbolicLink(launcherLink.toPath())) {
                Files.delete(launcherLink.toPath())
            }
            check(optBin.exists()) { "Expected jpackage launcher at ${optBin}, not found" }
            Files.createSymbolicLink(
                launcherLink.toPath(),
                Paths.get("/opt/puklic/bin/Puklic"),
            )
            logger.lifecycle("patchDebPostBuild:   added /usr/bin/puklic -> /opt/puklic/bin/Puklic")

            // /usr/share/applications/puklic.desktop (rewrite Exec + Icon).
            if (optDesktop.exists()) {
                val appsDir = File(work, "usr/share/applications").apply { mkdirs() }
                val destDesktop = File(appsDir, "puklic.desktop")
                val desktopText = optDesktop.readText()
                    .lineSequence()
                    .map { line ->
                        when {
                            line.startsWith("Exec=") -> "Exec=/opt/puklic/bin/Puklic"
                            line.startsWith("Icon=") -> "Icon=puklic"
                            else -> line
                        }
                    }
                    .joinToString("\n")
                destDesktop.writeText(if (desktopText.endsWith("\n")) desktopText else "$desktopText\n")
                logger.lifecycle("patchDebPostBuild:   added /usr/share/applications/puklic.desktop")
            } else {
                logger.warn("patchDebPostBuild:   ${optDesktop} not present — skipping .desktop bridge")
            }

            // /usr/share/pixmaps/puklic.png.
            if (optIcon.exists()) {
                val pixmaps = File(work, "usr/share/pixmaps").apply { mkdirs() }
                optIcon.copyTo(File(pixmaps, "puklic.png"), overwrite = true)
                logger.lifecycle("patchDebPostBuild:   added /usr/share/pixmaps/puklic.png")
            } else {
                logger.warn("patchDebPostBuild:   ${optIcon} not present — skipping pixmap bridge")
            }

            // Repack in place (dpkg-deb -b <dir> <file>).
            exec {
                commandLine("dpkg-deb", "-b", work.absolutePath, deb.absolutePath)
            }.assertNormalExitValue()
        }
    }
}

tasks.matching { it.name == "packageDeb" }.configureEach {
    finalizedBy(patchDebPostBuild)
}

// ---------------------------------------------------------------------------
// generateVersionKt — emit `dev.puklic.desktop.update.Version.CURRENT` from
// the single-source-of-truth `puklic.version` Gradle property. This replaces
// the previously hand-edited `Version.kt`, eliminating drift between the
// auto-update check string and the packaging version.
// ---------------------------------------------------------------------------
val generatedVersionDir = layout.buildDirectory.dir("generated/source/version/main/kotlin")

val generateVersionKt = tasks.register("generateVersionKt") {
    description = "Generates dev/puklic/desktop/update/Version.kt from puklic.version property."
    group = "build"

    inputs.property("puklicVersion", puklicVersion)
    outputs.dir(generatedVersionDir)

    doLast {
        val outDir = generatedVersionDir.get().asFile.resolve("dev/puklic/desktop/update")
        outDir.mkdirs()
        val file = outDir.resolve("Version.kt")
        file.writeText(
            """
            package dev.puklic.desktop.update

            /**
             * Puklic desktop build version. Generated from `puklic.version` in the root
             * `gradle.properties` by the `:desktop:app:generateVersionKt` task.
             *
             * DO NOT hand-edit this file — changes here are overwritten on every build.
             * To bump the version, edit `gradle.properties` (single source of truth).
             */
            public object Version {
                public const val CURRENT: String = "$puklicVersion"
            }

            """.trimIndent(),
        )
    }
}

kotlin {
    sourceSets["main"].kotlin.srcDir(generateVersionKt.map { generatedVersionDir })
}

// ── macAppStoreTest source set ───────────────────────────────────────────
// Test-only source set holding RED-phase contract tests for the future Mac
// App Store variant of :desktop:app (FP-14b, Issue #55). The matching
// `macAppStoreMain` source set + impl classes are FP-14d scope.
//
// These tests reference classes that DO NOT EXIST YET — running this task
// surfaces ClassNotFoundException, which is the desired red phase per
// HARD RULE #1 step 5.
//
// Intentionally NOT wired into the root `check` task in this slice — FP-14d
// will land the impl + the `check` wiring together so CI does not turn red
// in the meantime. To verify red phase on demand:
//   ./gradlew :desktop:app:macAppStoreTest
//
// See: docs/03_infrastructure/architect-reports/2026-05-29-fp14b-test-first.md
val macAppStoreTestSourceSet = sourceSets.create("macAppStoreTest") {
    java.srcDir("src/macAppStoreTest/kotlin")
    compileClasspath += sourceSets["main"].output + configurations["testRuntimeClasspath"]
    runtimeClasspath += output + compileClasspath
}

configurations["macAppStoreTestImplementation"].extendsFrom(
    configurations["testImplementation"],
)
configurations["macAppStoreTestRuntimeOnly"].extendsFrom(
    configurations["testRuntimeOnly"],
)

dependencies {
    // FP-14c (Issue #56) — JNA bridges over VideoToolbox / libopus / Network.framework.
    // Provides the impl classes the FP-14b contract tests assert exist + implement
    // the :shared:voice-codec interfaces. Restricted to the macAppStoreTest source
    // set so the existing main (Linux / Windows / macOS-DMG) classpath remains
    // unchanged.
    "macAppStoreTestImplementation"(projects.desktop.platformMacosAppstore)
}

val macAppStoreTest by tasks.registering(Test::class) {
    description =
        "Run Mac App Store variant contract tests (FP-14b → FP-14c, Issue #55 + #56). " +
            "JNA codec/transport contract tests turn GREEN after FP-14c lands the impl. " +
            "Entitlements + fastlane + workflow tests stay RED until FP-14d/e."
    group = "verification"
    testClassesDirs = macAppStoreTestSourceSet.output.classesDirs
    classpath = macAppStoreTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    // Bundled libopus.dylib lives next to the platform-macos-appstore module
    // sources — point JNA's library search there so JnaLibopusEncoder /
    // JnaLibopusDecoder can `Native.load("opus", …)` against it. The packaged
    // Mac App Store .app sets the same property to Contents/Resources at
    // launch (FP-14d scope).
    val libsDir = rootProject.file("desktop/platform-macos-appstore/libs").absolutePath
    systemProperty(
        "jna.library.path",
        listOfNotNull(libsDir, System.getProperty("jna.library.path")).joinToString(":"),
    )
}

// ── macAppStore main source set + packaging (FP-14d, Issue #57) ──────────
// The Mac App Store ship is a separate .pkg artifact assembled by jpackage
// from a hand-curated runtime classpath that EXCLUDES `:shared:voice`
// (GPL-3.0 + FFmpeg-GPL + libdave). Voice on this ship is wired as
// `NoOpVoiceClient` — same posture as the iOS App Store ship.
//
// Architect: docs/03_infrastructure/architect-reports/2026-05-29-fp14d-gradle-packaging.md
val macAppStoreMainSourceSet = sourceSets.create("macAppStore") {
    java.srcDir("src/macAppStore/kotlin")
    // Compile against main outputs + main compileClasspath so MacAppStoreMain
    // can reference Compose Desktop + Coil + Decompose + the shared-module
    // types pulled in by main. The runtime classpath below extends the main
    // `implementation` with project-level excludes for `:shared:voice` and
    // every forbidden GPL coordinate — the macAppStore source code MUST NOT
    // reference any symbol from `:shared:voice` (it would
    // NoClassDefFoundError at launch).
    compileClasspath += sourceSets["main"].output + configurations["compileClasspath"]
    runtimeClasspath = output +
        sourceSets["main"].output +
        configurations["macAppStoreRuntimeClasspath"]
}

// FP-14f-fix (F-1): `macAppStoreImplementation` extends `implementation` so
// the resolved runtime classpath inherits Compose Desktop's full closure
// (skiko-awt-runtime-macos-arm64, compose-jb-runtime-desktop, material3-desktop,
// etc.) — those are NOT direct coordinates but transitives of
// `compose.desktop.currentOs` brought in via the Compose plugin's resolution
// rules applied to the main source set. The GPL boundary is preserved by:
//   1. `resolutionStrategy.dependencySubstitution` replacing `:shared:voice`
//      with `:shared:voice-api` (the GPL-free interface module) on this
//      configuration — Gradle's idiomatic way to swap a project dep on a
//      single consumer edge.
//   2. Explicit `exclude(group, module)` for every entry of
//      `FORBIDDEN_MAC_APP_STORE_ARTIFACTS` so transitives (FFmpeg / x264 /
//      wire core-crypto) cannot leak in.
configurations["macAppStoreImplementation"].extendsFrom(configurations["implementation"])

configurations["macAppStoreRuntimeClasspath"].apply {
    resolutionStrategy.dependencySubstitution {
        substitute(project(":shared:voice")).using(project(":shared:voice-api"))
            .because("Mac App Store ship swaps GPL :shared:voice for the API-only module (FP-14f-fix F-1)")
    }
    exclude(group = "org.bytedeco")
    exclude(group = "com.wire", module = "core-crypto")
    exclude(group = "org.libx264")
}

dependencies {
    // Anchor the FP-14c JNA bridges (libopus / VideoToolbox / Network.framework)
    // so they are on the runtime classpath even though they are not pulled by
    // `implementation`.
    "macAppStoreImplementation"(projects.desktop.platformMacosAppstore)
}

// ── verifyMacAppStoreNoGplDeps ──────────────────────────────────────────
// Fails the build if the resolved `macAppStoreRuntimeClasspath` contains any
// forbidden Maven coordinate per `MacAppStoreGplChecker`. Mirror of
// `verifyIosNoGplDeps` in `ios/app/build.gradle.kts`.
val verifyMacAppStoreNoGplDeps by tasks.registering {
    group = "verification"
    description = "Fail if :desktop:app macAppStore runtime classpath pulls any forbidden GPL coord."

    val reportRef =
        "docs/03_infrastructure/architect-reports/2026-05-29-fp14d-gradle-packaging.md"

    // Scope the scan to ONLY `macAppStoreRuntimeClasspath` — the configuration
    // that materialises the JARs jpackage will embed in the .pkg. Other
    // `macAppStore*` configurations (notably `macAppStoreTestRuntimeClasspath`,
    // which extends from the main test classpath) intentionally retain
    // `:shared:voice` for the FP-14b contract tests and must not trigger
    // this guard.
    val violationsProvider: Provider<List<String>> = project.provider {
        val cfg = configurations.findByName("macAppStoreRuntimeClasspath")
            ?: return@provider emptyList()
        if (!cfg.isCanBeResolved) return@provider emptyList()
        runCatching {
            cfg.resolvedConfiguration.lenientConfiguration.allModuleDependencies
        }.getOrElse { emptySet() }
            .filter { dep -> isForbiddenMacAppStoreArtifact(dep.moduleGroup, dep.moduleName) }
            .map { "${it.moduleGroup}:${it.moduleName}:${it.moduleVersion}" }
            .toSortedSet()
            .toList()
    }

    doLast {
        val violations = violationsProvider.get()
        require(violations.isEmpty()) {
            buildString {
                appendLine("Forbidden GPL-3.0 dependency in :desktop:app macAppStore graph:")
                violations.forEach { appendLine("  - $it") }
                appendLine("The Mac App Store ship must stay Apache-2.0 / MIT / BSD only.")
                appendLine("See $reportRef")
            }
        }
    }
}

// ── packageMacAppStore ──────────────────────────────────────────────────
// Invokes jpackage with the FP-14a §7 / FP-14d §7 argv to produce a signed
// Mac App Store .pkg under `build/macAppStore/`. Requires the Mac App
// Distribution + Mac Installer Distribution certs in the keychain
// (FP-14a §3, §8.4 user-action prerequisite).
//
// Skipped on non-macOS hosts (jpackage --mac-* flags are macOS-only).
val packageMacAppStoreInput = layout.buildDirectory.dir("macAppStore/input")
val packageMacAppStoreAppContent = layout.buildDirectory.dir("macAppStore/app-content")
val packageMacAppStoreOutput = layout.buildDirectory.dir("macAppStore/pkg")

val stageMacAppStoreInput = tasks.register<Sync>("stageMacAppStoreInput") {
    description = "Stages the macAppStore runtime classpath JARs + main JAR for jpackage --input."
    group = "compose desktop"
    // FP-14f-fix (F-1): include the main `jar` task output so the main source
    // set's processed resources (`src/main/resources` — icons referenced by
    // `painterResource` in MacAppStoreMain) end up on the launcher classpath.
    // The Kotlin Jvm plugin's `jar` task packs `processResources` output by
    // default; depending on it here is the conceptually-correct way to make
    // resources reachable inside the `--input` directory.
    dependsOn(tasks.named("jar"))
    from(macAppStoreMainSourceSet.runtimeClasspath.filter { it.isFile })
    from(tasks.named("jar").map { it.outputs.files.singleFile })
    into(packageMacAppStoreInput)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val stageMacAppStoreAppContent = tasks.register("stageMacAppStoreAppContent") {
    description = "Stages libopus.dylib so jpackage --app-content places it under .app/Contents/Resources/."
    group = "compose desktop"
    val libopus = rootProject.file("desktop/platform-macos-appstore/libs/libopus.dylib")
    val outDir = packageMacAppStoreAppContent
    inputs.file(libopus).optional()
    outputs.dir(outDir)
    doLast {
        val resourcesDir = outDir.get().asFile.resolve("Resources")
        resourcesDir.deleteRecursively()
        resourcesDir.mkdirs()
        if (libopus.exists()) {
            libopus.copyTo(resourcesDir.resolve("libopus.dylib"), overwrite = true)
            logger.lifecycle("stageMacAppStoreAppContent: bundled $libopus")
        } else {
            logger.warn(
                "stageMacAppStoreAppContent: $libopus does not exist — " +
                    "run dist/apple/build-libopus-dylib-from-xcframework.sh to produce it. " +
                    "Continuing without it; the resulting .pkg will lack libopus.dylib.",
            )
        }
    }
}

val packageMacAppStore = tasks.register<Exec>("packageMacAppStore") {
    description = "Builds a signed Mac App Store .pkg via jpackage (Issue #57, FP-14d)."
    group = "compose desktop"

    dependsOn(stageMacAppStoreInput, stageMacAppStoreAppContent, verifyMacAppStoreNoGplDeps)
    dependsOn("compileMacAppStoreKotlin")

    onlyIf {
        val os = System.getProperty("os.name").lowercase()
        os.contains("mac")
    }

    val javaHome = System.getProperty("java.home")
    val jpackage = "$javaHome/bin/jpackage"

    val entitlements = rootProject.file("dist/apple/macappstore/Puklic.entitlements")
    val resourceDir = rootProject.file("dist/apple/macappstore/jpackage-resources")
    val signAppIdentity =
        "3rd Party Mac Developer Application: Jan Damek (GR74KSG8M9)"
    val signInstallerIdentity =
        "3rd Party Mac Developer Installer: Jan Damek (GR74KSG8M9)"

    val inputDir = packageMacAppStoreInput
    val outputDir = packageMacAppStoreOutput

    // Pick the main jar name by convention; jpackage's --main-jar must be a
    // file name (not path) that lives under --input.
    val mainJarName = "puklic-mac-app-store-app.jar"

    doFirst {
        require(entitlements.exists()) {
            "Entitlements file missing: $entitlements"
        }
        require(resourceDir.exists()) {
            "jpackage resource dir missing: $resourceDir"
        }
        // Materialise the main class jar by packaging the macAppStore source set
        // output into a jar under --input. jpackage needs --main-jar to point at
        // a JAR; the simplest is a thin jar containing just the macAppStore class
        // files + manifest. Compose Desktop's own packaging task assembles a fat
        // launcher; we do the equivalent minimal jar by hand.
        val outJar = inputDir.get().asFile.resolve(mainJarName)
        ant.withGroovyBuilder {
            "jar"("destfile" to outJar.absolutePath) {
                macAppStoreMainSourceSet.output.classesDirs
                    .filter { it.exists() }
                    .forEach { dir -> "fileset"("dir" to dir.absolutePath) }
                "manifest" {
                    "attribute"(
                        "name" to "Main-Class",
                        "value" to "dev.puklic.desktop.macappstore.MacAppStoreMainKt",
                    )
                }
            }
        }
        outputDir.get().asFile.mkdirs()
    }

    val argv = mutableListOf(
        jpackage,
        "--type", "pkg",
        "--mac-app-store",
        "--name", "Puklic",
        "--app-version", puklicVersion,
        "--vendor", "Jan Damek",
        "--copyright", "© 2026 Jan Damek. Apache-2.0.",
        "--dest", packageMacAppStoreOutput.get().asFile.absolutePath,
        "--input", packageMacAppStoreInput.get().asFile.absolutePath,
        "--main-jar", mainJarName,
        "--main-class", "dev.puklic.desktop.macappstore.MacAppStoreMainKt",
        "--mac-package-identifier", "cz.damek.puklic.app",
        "--mac-sign",
        "--mac-app-image-sign-identity", signAppIdentity,
        "--mac-installer-sign-identity", signInstallerIdentity,
        "--mac-entitlements", entitlements.absolutePath,
        "--resource-dir", resourceDir.absolutePath,
        "--app-content", packageMacAppStoreAppContent.get().asFile.absolutePath,
        // jpackage substitutes `${'$'}APPDIR` at launcher-cfg write time (JDK 21
        // `jdk.jpackage.internal.AppImageBundler`). `${'$'}APPDIR` resolves to
        // `Contents/app/` at runtime, so `${'$'}APPDIR/../Resources` points to
        // `Contents/Resources/` where `--app-content Resources/` populated
        // libopus.dylib. The literal must NOT be shell-expanded by Gradle's
        // `Exec.commandLine` — it is passed verbatim to jpackage which does
        // the substitution itself.
        "--java-options", "-Djna.library.path=\$APPDIR/../Resources",
        "--java-options", "-Dpuklic.flavor=macAppStore",
    )

    commandLine(argv)
}
