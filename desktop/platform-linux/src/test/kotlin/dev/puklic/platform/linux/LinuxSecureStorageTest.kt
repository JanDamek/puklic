package dev.puklic.platform.linux

import dev.puklic.platform.PlatformUnavailable
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LinuxSecureStorageTest {

    @Test
    fun `parseAccounts extracts attribute account values`() {
        val out = """
            [/org/freedesktop/secrets/collection/login/123]
            label = Puklic — token-a
            attribute.service = puklic-client
            attribute.account = token-a
            [/org/freedesktop/secrets/collection/login/124]
            attribute.service = puklic-client
            attribute.account = refresh-token
        """.trimIndent()
        LinuxSecureStorage.parseAccounts(out) shouldContainExactly listOf("token-a", "refresh-token")
    }

    @Test
    fun `parseAccounts returns empty list when no matches`() {
        LinuxSecureStorage.parseAccounts("no attributes here") shouldContainExactly emptyList()
    }

    @Test
    fun `throws PlatformUnavailable when secret-tool missing`() = runTest {
        val storage = LinuxSecureStorage(runner = AbsentRunner())
        assertFailsWith<PlatformUnavailable> { storage.get("anything") }
        assertFailsWith<PlatformUnavailable> { storage.put("k", "v") }
        assertFailsWith<PlatformUnavailable> { storage.remove("k") }
        assertFailsWith<PlatformUnavailable> { storage.list() }
    }

    @Test
    fun `roundtrip put-get-list-remove against real libsecret`() = runTest {
        val runner = CommandRunner()
        if (!runner.isOnPath(LinuxSecureStorage.EXEC)) return@runTest // skip when CLI missing

        val service = "puklic-test-${UUID.randomUUID()}"
        val storage = LinuxSecureStorage(serviceName = service)
        try {
            storage.put("alpha", "secret-A")
            storage.put("beta", "secret-B")
            storage.get("alpha") shouldBe "secret-A"
            storage.get("beta") shouldBe "secret-B"
            storage.list() shouldContainExactlyInAnyOrder listOf("alpha", "beta")
            storage.remove("alpha")
            storage.get("alpha") shouldBe null
            storage.list() shouldContainExactly listOf("beta")
        } finally {
            storage.remove("alpha")
            storage.remove("beta")
        }
    }

    /** CommandRunner subclass that reports every executable as absent. */
    private class AbsentRunner : CommandRunner() {
        override fun isOnPath(executable: String): Boolean = false
    }
}
