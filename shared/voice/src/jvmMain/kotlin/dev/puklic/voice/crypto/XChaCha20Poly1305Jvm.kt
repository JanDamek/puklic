package dev.puklic.voice.crypto

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * BouncyCastle 1.78 has [ChaCha20Poly1305] (RFC 7539, 12-byte nonce) but no dedicated
 * `XChaCha20Poly1305` class. We implement XChaCha20-Poly1305 (RFC 8439-inspired draft
 * `draft-irtf-cfrg-xchacha`) by:
 *
 *   1. Deriving a 32-byte subkey via HChaCha20(key, nonce[0..16]).
 *   2. Running ChaCha20-Poly1305 with the subkey and a 12-byte nonce composed of
 *      4 zero bytes || nonce[16..24].
 *
 * The Discord nonce layout (counter || zero[20]) means nonce[16..24] = 8 zero bytes,
 * so the inner 12-byte nonce ends up being all-zero, while the entropy lives in the
 * derived subkey through HChaCha20 of the 16-byte counter-prefix. This matches the
 * `aead_xchacha20_poly1305_rtpsize` scheme used by Discord and is interoperable with
 * libsodium's `crypto_aead_xchacha20poly1305_ietf_*`.
 */
internal actual fun xchacha20Poly1305(key: ByteArray): AeadCipher {
    require(key.size == 32) { "XChaCha20-Poly1305 key must be 32 bytes, got ${key.size}" }
    return BcXChaCha20Poly1305(key)
}

private const val KEY_SIZE = 32
private const val NONCE_SIZE = 24
private const val TAG_BITS = 128
private const val TAG_BYTES = 16
private const val HCHACHA_NONCE_PREFIX = 16
private const val INNER_NONCE_SIZE = 12

private class BcXChaCha20Poly1305(private val key: ByteArray) : AeadCipher {

    override fun encrypt(plaintext: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray {
        val (subKey, innerNonce) = deriveSubkeyAndNonce(nonce)
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(subKey), TAG_BITS, innerNonce, aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val n = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, n)
        return out
    }

    override fun decrypt(ciphertextWithTag: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray {
        require(ciphertextWithTag.size >= TAG_BYTES) {
            "AEAD payload shorter than tag (${ciphertextWithTag.size} < $TAG_BYTES)"
        }
        val (subKey, innerNonce) = deriveSubkeyAndNonce(nonce)
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(subKey), TAG_BITS, innerNonce, aad))
        val out = ByteArray(cipher.getOutputSize(ciphertextWithTag.size))
        val n = cipher.processBytes(ciphertextWithTag, 0, ciphertextWithTag.size, out, 0)
        val total = n + cipher.doFinal(out, n)
        return if (total == out.size) out else out.copyOf(total)
    }

    private fun deriveSubkeyAndNonce(nonce: ByteArray): Pair<ByteArray, ByteArray> {
        require(nonce.size == NONCE_SIZE) { "XChaCha20 nonce must be $NONCE_SIZE bytes, got ${nonce.size}" }
        val subKey = HChaCha20.derive(key, nonce.copyOfRange(0, HCHACHA_NONCE_PREFIX))
        val innerNonce = ByteArray(INNER_NONCE_SIZE).also { dst ->
            // 4 zero bytes || nonce[16..24]
            System.arraycopy(nonce, HCHACHA_NONCE_PREFIX, dst, 4, NONCE_SIZE - HCHACHA_NONCE_PREFIX)
        }
        return subKey to innerNonce
    }
}

/**
 * HChaCha20 subkey derivation (per `draft-irtf-cfrg-xchacha`).
 *
 * Inputs: 32-byte key, 16-byte nonce.
 * Output: 32-byte subkey = ChaCha20 block function on the state
 *   [c0 c1 c2 c3 | k0..k7 | n0..n3]
 * after 20 rounds, keeping words 0..3 and 12..15 (skipping the counter slot).
 *
 * Constants ("expand 32-byte k") from RFC 7539.
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
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
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
