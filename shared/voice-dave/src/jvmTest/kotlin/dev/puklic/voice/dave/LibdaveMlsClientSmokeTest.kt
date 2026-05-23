package dev.puklic.voice.dave

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Smoke test for the libdave (Discord MIT) backend.
 *
 * Only runs on platforms with a bundled native lib (currently macOS arm64).
 * On unsupported platforms the test is skipped (assumeTrue) — CI matrix
 * expansion is tracked as a Phase 3.2 follow-up.
 */
class LibdaveMlsClientSmokeTest {

    @Test
    fun `native lib loads`() {
        assumeTrue(isSupportedPlatform(), "libdave bundle missing for this platform")
        // Touching INSTANCE triggers System.load + Native.load. Should not throw.
        val max = LibdaveBindings.INSTANCE.daveMaxSupportedProtocolVersion()
        max.toInt() shouldBeGreaterThan 0
    }

    @Test
    fun `session create + init + close does not crash`() = runTest {
        assumeTrue(isSupportedPlatform(), "libdave bundle missing for this platform")
        val client = LibdaveMlsClient()
        try {
            val pk = client.init("test-user-${UUID.randomUUID()}")
            // libdave does not expose own signature key via C API yet — see KDoc on
            // [LibdaveMlsClient.init]. The contract is "empty bytes means unavailable".
            pk.size shouldBe 0

            // createGroup -> native daveSessionInit(version, groupId, userId). Must not crash.
            // (KeyPackage production requires the external-sender package to have arrived
            // first via the gateway's opcode 25 — that flow is exercised end-to-end only
            // once the gateway wiring lands; out of scope for this smoke test.)
            client.createGroup("12345")
        } finally {
            client.close()
        }
    }

    @Test
    fun `external sender attach succeeds before commit`() = runTest {
        assumeTrue(isSupportedPlatform(), "libdave bundle missing for this platform")
        val client = LibdaveMlsClient()
        try {
            client.init("test-user-${UUID.randomUUID()}")
            client.createGroup("12345")
            // libdave allows post-hoc external sender attach (this is the structural fix
            // vs Wire 4.2.0). The call must not crash even with dummy bytes; libdave
            // will log a parse error but not throw across the FFI boundary.
            client.processExternalSender("12345", ByteArray(MIN_KEY_PACKAGE_BYTES))
        } finally {
            client.close()
        }
    }

    private fun isSupportedPlatform(): Boolean {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val isMac = "mac" in os || "darwin" in os
        val isArm64 = arch == "aarch64" || arch == "arm64"
        return isMac && isArm64
    }

    private companion object {
        const val MIN_KEY_PACKAGE_BYTES = 100
    }
}
