package dev.puklic.voice.crypto

/**
 * Authenticated cipher used for Discord voice `aead_xchacha20_poly1305_rtpsize`.
 *
 * Per architect report 2026-05-23-voice.md §7:
 *  - Key: 32 B from SessionDescription.
 *  - Nonce: 24 B (XChaCha20 extended nonce).
 *  - AAD: 12 B RTP header.
 *  - Tag: 16 B Poly1305, appended to ciphertext.
 *
 * Pure-Kotlin pluggable contract. Concrete implementations live in
 * platform-specific modules: `:shared:voice` jvmMain (BouncyCastle for the
 * desktop GPL build); CryptoKit-backed actuals will land per FP-4..6 for the
 * App Store iOS / macOS builds.
 */
public interface AeadCipher {
    /** Returns ciphertext concatenated with 16-byte Poly1305 tag. */
    public fun encrypt(plaintext: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray

    /** Verifies the 16-byte tag and returns the plaintext, or throws on auth failure. */
    public fun decrypt(ciphertextWithTag: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray
}
