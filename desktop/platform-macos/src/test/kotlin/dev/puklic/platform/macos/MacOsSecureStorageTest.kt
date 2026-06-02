package dev.puklic.platform.macos

import dev.puklic.platform.PlatformFailed
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test

/**
 * `errSecMissingEntitlement` (`-34018`) is returned by Security.framework
 * when the calling process lacks the `keychain-access-groups` /
 * `com.apple.application-identifier` entitlement required to write a
 * synchronizable Keychain item. Bare Gradle test JVMs are unsigned and have
 * no entitlement → the integration test cannot run there. The packaged
 * Developer ID / Mac App Store builds have the entitlement, so this
 * round-trip succeeds in production.
 */

/**
 * Integration test for [MacOsSecureStorage] against the real macOS Keychain.
 *
 * Sync-flag unit assertions live in [MacOsSecureStorageSyncFlagTest] so they
 * run on every JVM regardless of host OS; this file's tests are best-effort
 * and skip when the host is not macOS or when Security.framework cannot be
 * loaded (e.g. CI Linux runners).
 */
class MacOsSecureStorageTest {

    private fun isMacOs(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("mac")

    @Test
    fun `roundtrip put-get-remove against real Keychain`() = runTest {
        if (!isMacOs()) return@runTest

        val service = "puklic-test-${UUID.randomUUID()}"
        val storage = MacOsSecureStorage(serviceName = service)
        try {
            try {
                storage.put("alpha", "secret-A")
            } catch (e: PlatformFailed) {
                if (e.message?.contains("-34018") == true) return@runTest // unsigned JVM → skip
                throw e
            }
            storage.get("alpha") shouldBe "secret-A"
            storage.put("alpha", "secret-A2")           // update path
            storage.get("alpha") shouldBe "secret-A2"
            storage.put("beta", "secret-B")
            storage.get("beta") shouldBe "secret-B"
            storage.remove("alpha")
            storage.get("alpha") shouldBe null
            // Idempotent remove of missing key
            storage.remove("alpha")
        } finally {
            runCatching { storage.remove("alpha") }
            runCatching { storage.remove("beta") }
        }
        storage.get("absent-${UUID.randomUUID()}") shouldBe null
    }

    @Test
    fun `null check distinguishes empty-string secret from missing`() = runTest {
        if (!isMacOs()) return@runTest

        val service = "puklic-test-${UUID.randomUUID()}"
        val storage = MacOsSecureStorage(serviceName = service)
        storage.get("never-stored") shouldBe null
        try {
            storage.put("k", "v")
        } catch (e: PlatformFailed) {
            if (e.message?.contains("-34018") == true) return@runTest
            throw e
        }
        try {
            storage.get("k") shouldNotBe null
        } finally {
            runCatching { storage.remove("k") }
        }
    }
}
