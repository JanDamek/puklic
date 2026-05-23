package dev.puklic.voice.dave

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 3.1b spike smoke tests. Verify the `:shared:voice-dave` module wires up
 * `com.wire:core-crypto-jvm` correctly and exposes the minimum DAVE surface.
 *
 * NOT a substitute for a full DAVE integration test — those land in 3.1c-e
 * with the gateway plumbed in.
 */
class MlsClientSmokeTest {

    @Test
    fun `init returns non-empty signature public key`() = runTest {
        val client = mlsClient()
        try {
            val pubKey = client.init("test-user-${UUID.randomUUID()}")
            pubKey.size shouldBeGreaterThan MIN_PUBLIC_KEY_BYTES
            // Same key is cached + retrievable.
            client.signaturePublicKey().toList() shouldContainExactly pubKey.toList()
        } finally {
            client.close()
        }
    }

    @Test
    fun `generateKeyPackage returns non-trivial bytes`() = runTest {
        val client = mlsClient()
        try {
            client.init("test-user-${UUID.randomUUID()}")
            val keyPackage = client.generateKeyPackage()
            keyPackage.size shouldBeGreaterThan MIN_KEY_PACKAGE_BYTES
        } finally {
            client.close()
        }
    }

    /**
     * End-to-end: alice creates group, adds bob via his KeyPackage, bob processes
     * the captured Welcome bundle, both sides derive identical exporter secrets.
     *
     * This is the canonical "two clients agree on a shared MLS exporter" test.
     * If this passes, the Wire integration is mechanically sound; DAVE's
     * remaining work (correct exporter label, frame AEAD, gateway opcodes 21-31)
     * is separate.
     */
    @Test
    fun `two clients exchange Welcome and derive identical exporter`() = runTest {
        val alice = WireMlsClient()
        val bob = WireMlsClient()
        try {
            alice.init("alice-${UUID.randomUUID()}")
            bob.init("bob-${UUID.randomUUID()}")

            val groupId = "channel-${UUID.randomUUID()}"
            alice.createGroup(groupId)

            val bobKeyPackage = bob.generateKeyPackage()
            val captured = alice.addMemberCapturingCommit(groupId, bobKeyPackage)
            captured.commit.size shouldBeGreaterThan 0
            val welcomeBytes = captured.welcome
                ?: error("expected non-null Welcome after addMember+commit")
            welcomeBytes.size shouldBeGreaterThan 0

            val bobGroupId = bob.processWelcome(welcomeBytes)
            bobGroupId shouldBe groupId

            // Both at the same post-add epoch (1, after one Commit on top of epoch 0).
            alice.currentEpoch(groupId) shouldBe bob.currentEpoch(groupId)

            // Exporter parity: identical bytes on both sides at the same epoch.
            val aliceExporter = alice.exportSecret(groupId, "Discord Secure Frames v0", EXPORTER_LEN)
            val bobExporter = bob.exportSecret(groupId, "Discord Secure Frames v0", EXPORTER_LEN)
            aliceExporter.toList() shouldContainExactly bobExporter.toList()
        } finally {
            alice.close()
            bob.close()
        }
    }

    private companion object {
        // X25519 raw pub key is 32 bytes; signature pub key (Ed25519) is 32 bytes.
        // Wire wraps with TLS framing; be conservative.
        const val MIN_PUBLIC_KEY_BYTES = 16
        // A KeyPackage TLS-encodes pub keys + extensions + a signature; ~200 bytes minimum.
        const val MIN_KEY_PACKAGE_BYTES = 100
        const val EXPORTER_LEN = 32
    }
}
