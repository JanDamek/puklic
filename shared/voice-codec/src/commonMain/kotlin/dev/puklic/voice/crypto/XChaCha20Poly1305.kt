package dev.puklic.voice.crypto

import dev.puklic.voice.codec.PuklicVoiceCodec

/**
 * XChaCha20-Poly1305 AEAD (per `draft-irtf-cfrg-xchacha`), as used by Discord's
 * `aead_xchacha20_poly1305_rtpsize` voice transport cipher.
 *
 *  - Key:   32 bytes
 *  - Nonce: 24 bytes (extended nonce; the first 16 bytes derive a ChaCha20 subkey
 *           via HChaCha20, the last 8 bytes form the inner ChaCha20-Poly1305 nonce
 *           padded with 4 leading zero bytes)
 *  - Tag:   16 bytes Poly1305 — appended to ciphertext on encrypt, verified and
 *           stripped on decrypt
 *
 * The returned [AeadCipher] is stateless w.r.t. nonce sequencing — the caller (see
 * [dev.puklic.voice.transport.NonceGenerator]) advances the 24-byte nonce per packet.
 *
 * Platform implementations:
 *  - JVM: BouncyCastle `ChaCha20Poly1305` + pure-Kotlin HChaCha20 subkey derivation
 *    ([dev.puklic.voice.crypto in jvmMain]).
 *  - iOS: Apple CryptoKit `ChaChaPoly` via a committed Swift bridge linked through
 *    Kotlin/Native cinterop, plus pure-Kotlin/Native HChaCha20 subkey derivation.
 *
 * See architect report
 * `docs/03_infrastructure/architect-reports/2026-05-29-fp14h-2c-implementation.md` §1.
 *
 * @throws IllegalArgumentException if [key] is not exactly 32 bytes.
 */
@PuklicVoiceCodec
public expect fun xchacha20Poly1305(key: ByteArray): AeadCipher
