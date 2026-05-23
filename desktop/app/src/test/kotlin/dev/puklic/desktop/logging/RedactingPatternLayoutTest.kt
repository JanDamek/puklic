package dev.puklic.desktop.logging

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

class RedactingPatternLayoutTest {

    @Test
    fun `redacts bearer token`() {
        val token = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefg"
        val raw = "request headers: Bearer $token end"
        val redacted = RedactingPatternLayout.redact(raw)
        redacted shouldNotContain token
        redacted shouldContain "Bearer <REDACTED>"
    }

    @Test
    fun `redacts authorization header`() {
        val token = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefg"
        val raw = "Authorization: $token tail"
        val redacted = RedactingPatternLayout.redact(raw)
        redacted shouldNotContain token
        redacted shouldContain "<REDACTED>"
    }

    @Test
    fun `redacts authorization header with bearer prefix`() {
        val token = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefg"
        val raw = "Authorization: Bearer $token"
        val redacted = RedactingPatternLayout.redact(raw)
        redacted shouldNotContain token
    }

    @Test
    fun `redacts mfa token`() {
        val mfa = "mfa.abcdefghijklmnopqrstuvwxyz0123456789ABCDEF"
        val raw = "login response token=$mfa stored"
        val redacted = RedactingPatternLayout.redact(raw)
        redacted shouldNotContain mfa
        redacted shouldContain "<REDACTED-MFA>"
    }

    @Test
    fun `leaves unrelated short tokens alone`() {
        val raw = "user authorized for guild 1234567890"
        RedactingPatternLayout.redact(raw) shouldBe raw
    }
}
