package dev.puklic.voice.crypto

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.test.Test

class XChaCha20Poly1305Test {

    @Test
    fun `encrypt then decrypt round-trip returns original plaintext`() {
        val key = Random(1).nextBytes(32)
        val nonce = Random(2).nextBytes(24)
        val aad = Random(3).nextBytes(12)
        val plaintext = Random(4).nextBytes(200)

        val cipher = xchacha20Poly1305(key)
        val ciphertext = cipher.encrypt(plaintext, nonce, aad)
        ciphertext.size shouldBe (plaintext.size + 16)
        cipher.decrypt(ciphertext, nonce, aad).toList() shouldBe plaintext.toList()
    }

    @Test
    fun `decrypt with wrong key throws`() {
        val key = Random(10).nextBytes(32)
        val wrongKey = Random(11).nextBytes(32)
        val nonce = ByteArray(24)
        val aad = ByteArray(12)
        val plaintext = byteArrayOf(1, 2, 3, 4, 5)

        val ciphertext = xchacha20Poly1305(key).encrypt(plaintext, nonce, aad)
        shouldThrow<Exception> {
            xchacha20Poly1305(wrongKey).decrypt(ciphertext, nonce, aad)
        }
    }

    @Test
    fun `decrypt with tampered ciphertext throws`() {
        val key = Random(20).nextBytes(32)
        val nonce = Random(21).nextBytes(24)
        val aad = ByteArray(12)
        val plaintext = Random(22).nextBytes(64)

        val cipher = xchacha20Poly1305(key)
        val ciphertext = cipher.encrypt(plaintext, nonce, aad)
        ciphertext[0] = (ciphertext[0].toInt() xor 0x01).toByte()
        shouldThrow<Exception> { cipher.decrypt(ciphertext, nonce, aad) }
    }

    @Test
    fun `decrypt with wrong aad throws`() {
        val key = Random(30).nextBytes(32)
        val nonce = Random(31).nextBytes(24)
        val aad = ByteArray(12) { 1 }
        val plaintext = Random(32).nextBytes(32)

        val cipher = xchacha20Poly1305(key)
        val ciphertext = cipher.encrypt(plaintext, nonce, aad)
        val badAad = aad.copyOf().also { it[0] = 9 }
        shouldThrow<Exception> { cipher.decrypt(ciphertext, nonce, badAad) }
    }

    @Test
    fun `key of wrong length is rejected`() {
        shouldThrow<IllegalArgumentException> { xchacha20Poly1305(ByteArray(16)) }
    }
}
