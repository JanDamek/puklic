package dev.puklic.platform.windows

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Integration test for [WindowsSecureStorage] against the real Win32
 * Credential Manager. Gated to Windows hosts; on Linux / macOS CI runners
 * the test body short-circuits with a success (`assumeWindows` no-op
 * pattern matched on `os.name`).
 */
class WindowsSecureStorageTest {

    @Test
    fun `roundtrip put-get-list-remove against real Credential Manager`() = runTest {
        if (!isWindows()) return@runTest
        val storage = WindowsSecureStorage(serviceName = "puklic-test-${System.nanoTime()}")
        val key = "session"
        val value = "token-abc-123"
        try {
            storage.put(key, value)
            storage.get(key) shouldBe value
            storage.list() shouldContain key
            storage.remove(key)
            storage.get(key) shouldBe null
            storage.list() shouldNotContain key
        } finally {
            // Defensive cleanup — remove is idempotent vs ERROR_NOT_FOUND.
            storage.remove(key)
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")
}
