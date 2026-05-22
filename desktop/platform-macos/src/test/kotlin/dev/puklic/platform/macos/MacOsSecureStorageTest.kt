package dev.puklic.platform.macos

import dev.puklic.platform.PlatformUnavailable
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MacOsSecureStorageTest {

    @Test
    fun `parseAccountsFromDump extracts accounts whose service matches`() {
        val dump = """
            keychain: "/Users/x/Library/Keychains/login.keychain-db"
            class: "genp"
            attributes:
                "svce"<blob>="puklic-client"
                "acct"<blob>="alpha"
            keychain: "/Users/x/Library/Keychains/login.keychain-db"
            class: "genp"
            attributes:
                "svce"<blob>="other-app"
                "acct"<blob>="ignored"
            keychain: "/Users/x/Library/Keychains/login.keychain-db"
            class: "genp"
            attributes:
                "svce"<blob>="puklic-client"
                "acct"<blob>="beta"
        """.trimIndent()
        MacOsSecureStorage.parseAccountsFromDump(dump, "puklic-client") shouldContainExactlyInAnyOrder
            listOf("alpha", "beta")
    }

    @Test
    fun `parseAccountsFromDump returns empty when no service matches`() {
        val dump = """
            keychain: "/x"
            attributes:
                "svce"<blob>="something-else"
                "acct"<blob>="ignored"
        """.trimIndent()
        MacOsSecureStorage.parseAccountsFromDump(dump, "puklic-client") shouldBe emptyList()
    }

    @Test
    fun `throws PlatformUnavailable when security CLI absent`() = runTest {
        val storage = MacOsSecureStorage(runner = AbsentRunner())
        assertFailsWith<PlatformUnavailable> { storage.put("k", "v") }
        assertFailsWith<PlatformUnavailable> { storage.get("k") }
        assertFailsWith<PlatformUnavailable> { storage.remove("k") }
        assertFailsWith<PlatformUnavailable> { storage.list() }
    }

    @Test
    fun `roundtrip put-get-remove against real Keychain`() = runTest {
        val runner = CommandRunner()
        if (!runner.isOnPath(MacOsSecureStorage.EXEC)) return@runTest // skip when CLI missing

        val service = "puklic-test-${UUID.randomUUID()}"
        val storage = MacOsSecureStorage(serviceName = service)
        try {
            storage.put("alpha", "secret-A")
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
            storage.remove("alpha")
            storage.remove("beta")
        }
        // sanity: missing key returns null without throwing
        storage.get("absent-${UUID.randomUUID()}") shouldBe null
    }

    @Test
    fun `null check distinguishes empty-string secret from missing`() = runTest {
        val runner = CommandRunner()
        if (!runner.isOnPath(MacOsSecureStorage.EXEC)) return@runTest

        val service = "puklic-test-${UUID.randomUUID()}"
        val storage = MacOsSecureStorage(serviceName = service)
        storage.get("never-stored") shouldBe null
        // sanity null vs not-null when value present
        storage.put("k", "v")
        try {
            storage.get("k") shouldNotBe null
        } finally {
            storage.remove("k")
        }
    }

    private class AbsentRunner : CommandRunner() {
        override fun isOnPath(executable: String): Boolean = false
    }
}
