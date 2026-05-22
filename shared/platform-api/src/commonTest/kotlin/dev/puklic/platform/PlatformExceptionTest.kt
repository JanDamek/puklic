package dev.puklic.platform

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PlatformExceptionTest {

    private fun classify(ex: PlatformException): String = when (ex) {
        is PlatformUnavailable -> "unavailable"
        is PlatformDenied -> "denied"
        is PlatformFailed -> "failed"
    }

    @Test
    fun `unavailable carries message`() {
        val ex: PlatformException = PlatformUnavailable("no D-Bus")
        ex.message shouldBe "no D-Bus"
        classify(ex) shouldBe "unavailable"
    }

    @Test
    fun `denied carries message`() {
        val ex: PlatformException = PlatformDenied("user denied notification permission")
        classify(ex) shouldBe "denied"
    }

    @Test
    fun `failed carries detail message`() {
        val ex: PlatformException = PlatformFailed("libsecret returned -1")
        classify(ex) shouldBe "failed"
    }
}
