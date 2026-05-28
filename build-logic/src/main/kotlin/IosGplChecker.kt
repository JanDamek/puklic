// IosGplChecker — pure matcher used by the :ios:app `verifyIosNoGplDeps` task.
//
// Why this exists: the iOS App Store build (chat-only, TestFlight) MUST be
// Apache-2.0 / MIT only. GPL-3.0 deps used by the desktop GitHub Releases
// build (FFmpeg-GPL, libdave, core-crypto for DAVE MLS, x264) are forbidden
// in the iOS module graph. This file holds the artifact-coordinate matcher;
// the Gradle task lives in `ios/app/build.gradle.kts`.
//
// See: docs/03_infrastructure/dep-policy.md
//      docs/03_infrastructure/architect-reports/2026-05-28-apple-distribution.md §8 row 1

/**
 * Forbidden Maven coordinates (case-insensitive substring match on
 * `group:artifact`). In-repo bundled native libs (file deps, no Maven coords)
 * are NOT covered by this list — they cannot appear in a resolved Maven
 * dependency graph anyway.
 */
val FORBIDDEN_GPL_ARTIFACTS: List<String> = listOf(
    // FFmpeg GPL variants via JavaCPP presets (FFmpeg itself is LGPL but the
    // -gpl classifier pulls in x264/x265/etc. that flip the bundle to GPL-3.0)
    "org.bytedeco:ffmpeg-platform-gpl",
    "org.bytedeco:ffmpeg", // catches ffmpeg-linux-x86_64-gpl, ffmpeg-macosx-arm64-gpl, etc.
    // JavaCPP itself is the JNI bridge for the FFmpeg-GPL bundle and has no
    // place in the iOS module graph.
    "org.bytedeco:javacpp",
    // libdave bridge (in-repo bundled — guard the Maven coord just in case
    // anyone publishes one in the future)
    "libdave",
    // JNA — Apache-2.0 itself, but in this repo it is the libdave JNI bridge;
    // chat-only iOS build must not pull it.
    "net.java.dev.jna:jna",
    // x264 / x265 native bindings (GPL-2.0+ — incompatible with App Store)
    "org.libx264",
    // Wire core-crypto: GPL-3.0, used by :shared:voice-dave for MLS
    "com.wire:core-crypto",
)

/**
 * Returns true iff the given Maven coordinate matches any forbidden GPL
 * artifact pattern. Match is case-insensitive substring against
 * `"$group:$name"`.
 */
fun isForbiddenGplArtifact(group: String, name: String): Boolean {
    val coord = "$group:$name"
    return FORBIDDEN_GPL_ARTIFACTS.any { pattern ->
        coord.contains(pattern, ignoreCase = true)
    }
}
