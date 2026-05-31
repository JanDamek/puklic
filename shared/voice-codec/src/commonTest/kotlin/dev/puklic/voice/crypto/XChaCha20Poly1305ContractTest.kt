package dev.puklic.voice.crypto

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.test.Test

/**
 * Cross-actual KAT (known-answer test) contract for [xchacha20Poly1305].
 *
 * Lives in commonTest so it runs against the JVM BouncyCastle actual AND the iOS
 * CryptoKit-backed actual, locking byte-for-byte interop. If either platform deviates
 * from the canonical XChaCha20-Poly1305 spec (`draft-irtf-cfrg-xchacha`), one or both
 * actuals fail and the slice cannot land.
 *
 * The KAT vector is from `draft-arciszewski-xchacha-03` §A.3.1 — the canonical
 * XChaCha20-Poly1305 vector reproduced in libsodium's test suite and across all
 * widely-deployed implementations.
 *
 * See architect report
 * `docs/03_infrastructure/architect-reports/2026-05-29-fp14h-2c-implementation.md` §4.
 */
class XChaCha20Poly1305ContractTest {

    // draft-arciszewski-xchacha-03 §A.3.1 — canonical XChaCha20-Poly1305 KAT.
    //
    //   Plaintext: "Ladies and Gentlemen of the class of '99: If I could offer you only
    //               one tip for the future, sunscreen would be it."
    private val katPlaintext: ByteArray = hex(
        "4c616469657320616e642047656e746c656d656e206f662074686520636c6173" +
            "73206f66202739393a204966204920636f756c64206f6666657220796f75206f" +
            "6e6c79206f6e652074697020666f7220746865206675747572652c2073756e73" +
            "637265656e20776f756c642062652069742e",
    )
    private val katAad: ByteArray = hex("50515253c0c1c2c3c4c5c6c7")
    private val katKey: ByteArray = hex(
        "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f",
    )
    private val katNonce: ByteArray = hex("404142434445464748494a4b4c4d4e4f5051525354555657")

    // Expected ciphertext || 16-byte Poly1305 tag, per the draft vector.
    private val katCiphertextWithTag: ByteArray = hex(
        "bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb" +
            "731c7f1b0b4aa6440bf3a82f4eda7e39ae64c6708c54c216cb96b72e1213b452" +
            "2f8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565d3fff9" +
            "21f9664c97637da9768812f615c68b13b52e" +
            "c0875924c1c7987947deafd8780acf49",
    )

    @Test
    fun `KAT encrypt produces canonical XChaCha20-Poly1305 ciphertext`() {
        val cipher = xchacha20Poly1305(katKey)
        val produced = cipher.encrypt(katPlaintext, katNonce, katAad)
        produced.toHex() shouldBe katCiphertextWithTag.toHex()
    }

    @Test
    fun `KAT decrypt of canonical ciphertext returns plaintext`() {
        val cipher = xchacha20Poly1305(katKey)
        val recovered = cipher.decrypt(katCiphertextWithTag, katNonce, katAad)
        recovered.toHex() shouldBe katPlaintext.toHex()
    }

    @Test
    fun `random round-trip recovers plaintext`() {
        val key = Random(1).nextBytes(32)
        val nonce = Random(2).nextBytes(24)
        val aad = Random(3).nextBytes(12)
        val plaintext = Random(4).nextBytes(200)

        val cipher = xchacha20Poly1305(key)
        val ciphertext = cipher.encrypt(plaintext, nonce, aad)
        ciphertext.size shouldBe (plaintext.size + 16)
        cipher.decrypt(ciphertext, nonce, aad).toHex() shouldBe plaintext.toHex()
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
    fun `decrypt with tampered tag throws`() {
        val key = Random(40).nextBytes(32)
        val nonce = Random(41).nextBytes(24)
        val aad = ByteArray(0)
        val plaintext = Random(42).nextBytes(16)

        val cipher = xchacha20Poly1305(key)
        val ciphertext = cipher.encrypt(plaintext, nonce, aad)
        val last = ciphertext.size - 1
        ciphertext[last] = (ciphertext[last].toInt() xor 0x80).toByte()
        shouldThrow<Exception> { cipher.decrypt(ciphertext, nonce, aad) }
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
        shouldThrow<IllegalArgumentException> { xchacha20Poly1305(ByteArray(31)) }
        shouldThrow<IllegalArgumentException> { xchacha20Poly1305(ByteArray(33)) }
    }

    @Test
    fun `nonce of wrong length is rejected`() {
        val key = Random(50).nextBytes(32)
        val cipher = xchacha20Poly1305(key)
        shouldThrow<IllegalArgumentException> {
            cipher.encrypt(byteArrayOf(1), ByteArray(12), ByteArray(0))
        }
        shouldThrow<IllegalArgumentException> {
            cipher.encrypt(byteArrayOf(1), ByteArray(23), ByteArray(0))
        }
    }

    @Test
    fun `empty plaintext produces just a tag`() {
        val key = Random(60).nextBytes(32)
        val nonce = Random(61).nextBytes(24)
        val aad = Random(62).nextBytes(12)

        val cipher = xchacha20Poly1305(key)
        val ciphertext = cipher.encrypt(ByteArray(0), nonce, aad)
        ciphertext.size shouldBe 16
        cipher.decrypt(ciphertext, nonce, aad).size shouldBe 0
    }

    // ─────────────────────────────────────────────────────────────────────

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        require(clean.length % 2 == 0)
        return ByteArray(clean.length / 2) { i ->
            val hi = clean[i * 2].digitToInt(16)
            val lo = clean[i * 2 + 1].digitToInt(16)
            ((hi shl 4) or lo).toByte()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { ((it.toInt() and 0xFF).toString(16)).padStart(2, '0') }
}
