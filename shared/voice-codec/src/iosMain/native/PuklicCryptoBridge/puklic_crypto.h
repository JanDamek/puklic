/*
 * puklic_crypto.h — C-callable bridge for Apple CryptoKit ChaChaPoly AEAD.
 *
 * Implemented in Swift (Bridge.swift) and exposed to Kotlin/Native via cinterop
 * (puklic_crypto.def). All buffers are caller-owned; the bridge does not retain.
 *
 * Convention:
 *   - returns 0 on success
 *   - returns a negative error code on failure (-1 nonce, -2 seal init/op,
 *     -3 tag-verify / open, -4 size mismatch)
 *   - *outLen written only on success
 *
 * See architect report
 *   docs/03_infrastructure/architect-reports/2026-05-29-fp14h-2c-implementation.md §3.
 */
#ifndef PUKLIC_CRYPTO_H
#define PUKLIC_CRYPTO_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * ChaCha20-Poly1305 (RFC 7539) seal. Inner cipher; XChaCha20 subkey + 12-byte
 * inner nonce derived by the Kotlin caller (HChaCha20).
 *
 *   key      : 32 bytes
 *   nonce    : 12 bytes
 *   aad      : aadLen bytes (may be NULL only when aadLen == 0)
 *   pt       : ptLen bytes (may be NULL only when ptLen == 0)
 *   out      : capacity ptLen + 16 bytes; receives ciphertext || tag
 *   outLen   : on success, set to ptLen + 16
 */
int32_t puklic_chachapoly_seal(
    const uint8_t* key, size_t keyLen,
    const uint8_t* nonce, size_t nonceLen,
    const uint8_t* aad, size_t aadLen,
    const uint8_t* pt, size_t ptLen,
    uint8_t* out, size_t* outLen);

/**
 * ChaCha20-Poly1305 (RFC 7539) open. Verifies the 16-byte tag and writes the
 * plaintext.
 *
 *   key      : 32 bytes
 *   nonce    : 12 bytes
 *   aad      : aadLen bytes (may be NULL only when aadLen == 0)
 *   ctTag    : ctTagLen bytes = ciphertext || tag; ctTagLen >= 16
 *   out      : capacity ctTagLen - 16 bytes; receives plaintext
 *   outLen   : on success, set to ctTagLen - 16
 */
int32_t puklic_chachapoly_open(
    const uint8_t* key, size_t keyLen,
    const uint8_t* nonce, size_t nonceLen,
    const uint8_t* aad, size_t aadLen,
    const uint8_t* ctTag, size_t ctTagLen,
    uint8_t* out, size_t* outLen);

#ifdef __cplusplus
}
#endif

#endif /* PUKLIC_CRYPTO_H */
