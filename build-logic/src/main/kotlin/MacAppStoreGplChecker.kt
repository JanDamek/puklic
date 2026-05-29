// MacAppStoreGplChecker — pure matcher used by the future :desktop:app
// `verifyMacAppStoreNoGplDeps` Gradle task (FP-14d).
//
// Why this exists: the Mac App Store variant of Puklic (JVM Compose Desktop
// re-packaged via jpackage --mac-app-store) MUST be Apache-2.0 / MIT only —
// same posture as the iOS App Store build. The default desktop GitHub
// Releases (.dmg) variant retains FFmpeg-GPL + libdave + JNA, but the
// `macAppStore` source set of :desktop:app MUST NOT.
//
// This file holds the artifact-coordinate matcher; the Gradle task that
// consumes it lives in `desktop/app/build.gradle.kts` (FP-14d scope).
//
// Mirror of IosGplChecker with one INTENTIONAL difference: JNA is ALLOWED
// in the Mac App Store build because FP-14c uses JNA to bind libopus.dylib
// (Apache-2.0 / BSD-3-Clause) and the system VideoToolbox / Network
// frameworks. The libdave JNA bridge stays forbidden via the `libdave`
// substring rule.
//
// See: docs/03_infrastructure/dep-policy.md
//      docs/03_infrastructure/architect-reports/2026-05-29-fp14a-mac-app-store-architect.md §2 + §4

/**
 * Forbidden Maven coordinates for the Mac App Store variant of :desktop:app
 * (case-insensitive substring match on `group:artifact`). Same family as
 * [FORBIDDEN_GPL_ARTIFACTS] minus the JNA entries (JNA is REQUIRED by the
 * Apple-native voice/screencast bridge — bound to libopus.dylib, VideoToolbox,
 * Network.framework — all Apache-2.0 / BSD-3-Clause / system frameworks).
 *
 * The `libdave` substring rule still blocks the libdave JNA wrapper
 * specifically; only the generic `net.java.dev.jna` Maven coordinate is
 * permitted.
 */
val FORBIDDEN_MAC_APP_STORE_ARTIFACTS: List<String> = listOf(
    // FFmpeg GPL variants via JavaCPP presets.
    "org.bytedeco:ffmpeg-platform-gpl",
    "org.bytedeco:ffmpeg", // catches ffmpeg-macosx-arm64-gpl etc.
    // JavaCPP — bridge for FFmpeg-GPL bundle; no place in Mac App Store build.
    "org.bytedeco:javacpp",
    // libdave bridge (in-repo bundled — guard the Maven coord too)
    "libdave",
    // x264 / x265 native bindings (GPL-2.0+)
    "org.libx264",
    // Wire core-crypto: GPL-3.0, used by :shared:voice-dave for MLS
    "com.wire:core-crypto",
    // libvpx GPL variants (if ever introduced via JavaCPP)
    "org.bytedeco:libvpx",
    // libpipewire JNA — Linux-only platform binding; not GPL but it pulls a
    // PipeWire client whose licence on macOS is N/A. Keeping it out of the
    // macAppStore graph keeps the audit surface mechanical.
    "libpipewire",
)

/**
 * Returns true iff the given Maven coordinate matches any forbidden Mac App
 * Store artifact pattern. Match is case-insensitive substring against
 * `"$group:$name"`.
 */
fun isForbiddenMacAppStoreArtifact(group: String, name: String): Boolean {
    val coord = "$group:$name"
    return FORBIDDEN_MAC_APP_STORE_ARTIFACTS.any { pattern ->
        coord.contains(pattern, ignoreCase = true)
    }
}
