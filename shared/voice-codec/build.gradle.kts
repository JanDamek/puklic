// :shared:voice-codec — Apache-2.0 pure-Kotlin Discord voice transport codec.
//
// Apache-2.0 + KMP-wide (jvm + android + iOS) so that future App Store iOS /
// macOS builds can share the AEAD packet framing / nonce sequencing / RTP
// header code path with the GPL desktop build without pulling in
// :shared:voice's BouncyCastle / FFmpeg-GPL / libdave.
//
// Contains:
//   - AeadCipher interface (the pluggable cipher contract)
//   - NonceGenerator (24-byte XChaCha20 nonce counter, _rtpsize layout)
//   - RtpPacket (12-byte RTP header read/write)
//   - VoicePacketCodec (encode/decode RTP + AEAD glue)
//   - EncodedFrame (Annex-B video payload + RTP timestamp + keyframe flag) — FP-2
//   - H264Encoder / H264Decoder + factories (KMP video codec contract) — FP-2,
//     platform impls land in FP-5 (iOS / macOS VideoToolbox)
//
// Does NOT contain a concrete AeadCipher impl. JVM impl lives in
// :shared:voice/jvmMain (BouncyCastle); iOS impl will land in FP-4..6
// (CryptoKit cinterop).
//
// See docs/03_infrastructure/architect-reports/2026-05-29-fp1-voice-codec-extraction.md
// See docs/03_infrastructure/architect-reports/2026-05-29-full-feature-parity.md §3.1
// See docs/03_infrastructure/dep-policy.md

plugins {
    id("puklic.kmp-library")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotest.assertions.core)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
        }
    }
}

android {
    namespace = "dev.puklic.shared.voicecodec"
}
