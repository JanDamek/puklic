// :desktop:platform-macos-appstore — Apache-2.0 JVM-only module containing JNA
// bridges over Apple-native codec / network primitives for the Mac App Store
// build variant (FP-14c, Issue #56).
//
// Module scope:
//   - JNA Library interfaces for VideoToolbox.framework, CoreMedia.framework,
//     CoreVideo.framework, Network.framework, libdispatch, libobjc, CoreFoundation
//     and the bundled libopus.dylib.
//   - Production Kotlin classes implementing
//     dev.puklic.voice.codec.video.H264Encoder / H264Decoder
//     dev.puklic.voice.codec.OpusEncoder / OpusDecoder
//     dev.puklic.voice.codec.transport.VoiceUdpTransport
//     against Apple-native APIs (no FFmpeg-GPL).
//
// Dependencies:
//   - :shared:voice-codec — KMP commonMain interfaces only. Gradle's "java" /
//     "jvm" compilation pulls the jvm artifact transitively, but the only
//     symbols this module touches are the commonMain ones. The verifier task
//     in FP-14d (`verifyMacAppStoreNoGplDeps`) ensures no FFmpeg-GPL classes
//     leak via Gradle resolution.
//   - :shared:voice-api — AudioConstants + KMP voice public types.
//   - JNA + JNA Platform — already on the repo classpath via :shared:screencast.
//   - kotlinx-coroutines-core — for suspendCancellableCoroutine in the transport.
//   - kermit — structured logging consistent with the rest of the codebase.
//
// libs/libopus.dylib (committed) is produced by
//   dist/apple/build-libopus-dylib-from-xcframework.sh
// and exposed to JNA via System.setProperty("jna.library.path", ...) by both
// the macAppStoreTest Gradle task in :desktop:app and the packaged
// Mac App Store .app (FP-14d).
//
// See: docs/03_infrastructure/architect-reports/2026-05-29-fp14c-codec-wrappers.md

plugins {
    id("puklic.jvm-library")
}

dependencies {
    api(projects.shared.voiceApi)
    api(projects.shared.voiceCodec)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kermit)
}
