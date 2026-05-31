package dev.puklic.voice.crypto

import dev.puklic.voice.codec.PuklicVoiceCodec
import dev.puklic.voice.crypto.cryptokit.puklic_chachapoly_open
import dev.puklic.voice.crypto.cryptokit.puklic_chachapoly_seal
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.size_tVar

/**
 * iOS `actual` for [xchacha20Poly1305] backed by Apple CryptoKit.
 *
 * The 24-byte XChaCha20 nonce is split into:
 *   - nonce[0..16] → HChaCha20(key, nonce[0..16]) → 32-byte subkey
 *   - nonce[16..24] → suffix of the 12-byte inner ChaCha20-Poly1305 nonce, with
 *                     4 leading zero bytes prepended
 *
 * The inner ChaCha20-Poly1305 (RFC 7539) operation is performed by Apple's CryptoKit
 * `ChaChaPoly` via a Swift bridge (Bridge.swift) exposed as C symbols. HChaCha20
 * subkey derivation is implemented in pure Kotlin/Native (byte-for-byte identical to
 * the JVM impl in `:shared:voice-codec/jvmMain`).
 *
 * Apple does not expose HChaCha20 directly; CryptoKit only ships the AEAD wrapper.
 * Re-implementing the ChaCha20 block function in ~100 lines is acceptable because
 * it is non-secret transformation (the subkey output is fed back into a vetted Apple
 * primitive for the actual encryption + Poly1305 MAC).
 *
 * Moved-here precedent: cinterop pattern mirrors libopus.def from FP-4. Swift bridge
 * compilation pattern mirrors dist/apple/build-libopus-xcframework.sh.
 *
 * See architect report
 * `docs/03_infrastructure/architect-reports/2026-05-29-fp14h-2c-implementation.md` §3.4.
 */
@PuklicVoiceCodec
public actual fun xchacha20Poly1305(key: ByteArray): AeadCipher {
    require(key.size == KEY_SIZE) { "XChaCha20-Poly1305 key must be $KEY_SIZE bytes, got ${key.size}" }
    return CryptoKitXChaCha20Poly1305(key.copyOf())
}

private const val KEY_SIZE = 32
private const val NONCE_SIZE = 24
private const val TAG_BYTES = 16
private const val HCHACHA_NONCE_PREFIX = 16
private const val INNER_NONCE_SIZE = 12

@OptIn(ExperimentalForeignApi::class)
private class CryptoKitXChaCha20Poly1305(private val key: ByteArray) : AeadCipher {

    override fun encrypt(plaintext: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray {
        val (subKey, innerNonce) = deriveSubkeyAndNonce(nonce)
        val out = ByteArray(plaintext.size + TAG_BYTES)
        val writtenLen = memScoped {
            val outLen = alloc<size_tVar>()
            val rc = withPin(subKey, innerNonce, aad, plaintext, out) {
                    keyPtr, noncePtr, aadPtr, ptPtr, outPtr ->
                puklic_chachapoly_seal(
                    keyPtr, subKey.size.convert(),
                    noncePtr, innerNonce.size.convert(),
                    if (aad.isEmpty()) null else aadPtr, aad.size.convert(),
                    if (plaintext.isEmpty()) null else ptPtr, plaintext.size.convert(),
                    outPtr!!, outLen.ptr,
                )
            }
            check(rc == 0) { "CryptoKit ChaChaPoly seal failed (rc=$rc)" }
            outLen.value.toInt()
        }
        return if (writtenLen == out.size) out else out.copyOf(writtenLen)
    }

    override fun decrypt(ciphertextWithTag: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray {
        require(ciphertextWithTag.size >= TAG_BYTES) {
            "AEAD payload shorter than tag (${ciphertextWithTag.size} < $TAG_BYTES)"
        }
        val (subKey, innerNonce) = deriveSubkeyAndNonce(nonce)
        val plaintextSize = ciphertextWithTag.size - TAG_BYTES
        // Allocate at least 1 byte so the pinned address is non-null even when the recovered
        // plaintext is empty (CryptoKit decrypt of a tag-only sealed box).
        val out = ByteArray(maxOf(plaintextSize, 1))
        val writtenLen = memScoped {
            val outLen = alloc<size_tVar>()
            val rc = withPin(subKey, innerNonce, aad, ciphertextWithTag, out) {
                    keyPtr, noncePtr, aadPtr, ctPtr, outPtr ->
                puklic_chachapoly_open(
                    keyPtr, subKey.size.convert(),
                    noncePtr, innerNonce.size.convert(),
                    if (aad.isEmpty()) null else aadPtr, aad.size.convert(),
                    ctPtr!!, ciphertextWithTag.size.convert(),
                    outPtr!!, outLen.ptr,
                )
            }
            check(rc == 0) { "CryptoKit ChaChaPoly open failed (rc=$rc)" }
            outLen.value.toInt()
        }
        return if (writtenLen == plaintextSize && writtenLen == out.size) out else out.copyOf(writtenLen)
    }

    private fun deriveSubkeyAndNonce(nonce: ByteArray): Pair<ByteArray, ByteArray> {
        require(nonce.size == NONCE_SIZE) { "XChaCha20 nonce must be $NONCE_SIZE bytes, got ${nonce.size}" }
        val subKey = HChaCha20.derive(key, nonce.copyOfRange(0, HCHACHA_NONCE_PREFIX))
        val innerNonce = ByteArray(INNER_NONCE_SIZE).also { dst ->
            // 4 zero bytes || nonce[16..24]
            nonce.copyInto(dst, destinationOffset = 4, startIndex = HCHACHA_NONCE_PREFIX, endIndex = NONCE_SIZE)
        }
        return subKey to innerNonce
    }
}

/**
 * Pins five `ByteArray`s simultaneously and exposes their `UByte*` start pointers (matching the
 * cinterop-generated C signatures, where `uint8_t*` maps to `CValuesRef<UByteVar>?`). All five
 * arrays remain pinned for the duration of [block]; on Native, `usePinned` guarantees the GC
 * does not relocate the backing memory while the pointer is live.
 *
 * The optional-pointer convention: callers pass null for `aad`/`pt` when those arrays are empty;
 * this helper still pins them (so the receiving pointer parameter is non-null when forwarded) but
 * the caller passes `null` to the C function in that case.
 */
@OptIn(ExperimentalForeignApi::class)
private inline fun withPin(
    a: ByteArray,
    b: ByteArray,
    c: ByteArray,
    d: ByteArray,
    e: ByteArray,
    block: (
        kotlinx.cinterop.CPointer<UByteVar>,
        kotlinx.cinterop.CPointer<UByteVar>,
        kotlinx.cinterop.CPointer<UByteVar>?,
        kotlinx.cinterop.CPointer<UByteVar>?,
        kotlinx.cinterop.CPointer<UByteVar>?,
    ) -> Int,
): Int = a.usePinned { pa ->
    b.usePinned { pb ->
        c.usePinned { pc ->
            d.usePinned { pd ->
                e.usePinned { pe ->
                    block(
                        pa.addressOf(0).reinterpret(),
                        pb.addressOf(0).reinterpret(),
                        if (c.isEmpty()) null else pc.addressOf(0).reinterpret(),
                        if (d.isEmpty()) null else pd.addressOf(0).reinterpret(),
                        if (e.isEmpty()) null else pe.addressOf(0).reinterpret(),
                    )
                }
            }
        }
    }
}

/**
 * HChaCha20 subkey derivation (per `draft-irtf-cfrg-xchacha`).
 *
 * Byte-for-byte identical to the JVM impl; uses Kotlin stdlib's `Int.rotateLeft`
 * (available on Kotlin/Native) in place of `Integer.rotateLeft`.
 */
private object HChaCha20 {
    private const val C0 = 0x61707865
    private const val C1 = 0x3320646e
    private const val C2 = 0x79622d32
    private const val C3 = 0x6b206574

    fun derive(key: ByteArray, nonce16: ByteArray): ByteArray {
        require(key.size == KEY_SIZE)
        require(nonce16.size == HCHACHA_NONCE_PREFIX)
        val state = IntArray(16)
        state[0] = C0; state[1] = C1; state[2] = C2; state[3] = C3
        for (i in 0 until 8) state[4 + i] = leInt(key, i * 4)
        for (i in 0 until 4) state[12 + i] = leInt(nonce16, i * 4)

        repeat(10) {
            quarter(state, 0, 4, 8, 12)
            quarter(state, 1, 5, 9, 13)
            quarter(state, 2, 6, 10, 14)
            quarter(state, 3, 7, 11, 15)
            quarter(state, 0, 5, 10, 15)
            quarter(state, 1, 6, 11, 12)
            quarter(state, 2, 7, 8, 13)
            quarter(state, 3, 4, 9, 14)
        }

        val out = ByteArray(KEY_SIZE)
        for (i in 0 until 4) leWrite(out, i * 4, state[i])
        for (i in 0 until 4) leWrite(out, 16 + i * 4, state[12 + i])
        return out
    }

    private fun quarter(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(16)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(12)
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(8)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(7)
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leWrite(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v ushr 8) and 0xFF).toByte()
        dst[off + 2] = ((v ushr 16) and 0xFF).toByte()
        dst[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
