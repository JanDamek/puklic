package dev.puklic.voice.dave

import co.touchlab.kermit.Logger
import com.wire.crypto.AvsSecret
import com.wire.crypto.Ciphersuite
import com.wire.crypto.Ciphersuites
import com.wire.crypto.ClientId
import com.wire.crypto.CommitBundle
import com.wire.crypto.CoreCrypto
import com.wire.crypto.CoreCryptoContext
import com.wire.crypto.CredentialType
import com.wire.crypto.CustomConfiguration
import com.wire.crypto.ExternalSenderKey
import com.wire.crypto.MLSGroupId
import com.wire.crypto.MLSKeyPackage
import com.wire.crypto.MlsMessage
import com.wire.crypto.MlsTransport
import com.wire.crypto.MlsTransportResponse
import com.wire.crypto.MlsWirePolicy
import com.wire.crypto.SignaturePublicKey
import com.wire.crypto.Welcome
import com.wire.crypto.WelcomeBundle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.time.Duration

/**
 * Wire core-crypto-backed [MlsClient]. JVM-only — depends on the Rust JNI native
 * shipped by `com.wire:core-crypto-jvm`.
 *
 * State is held in a per-instance temp directory (cleaned on JVM exit). DAVE
 * groups are ephemeral per call, so we do NOT persist across restarts — a new
 * call gets a new instance + new identity. If/when persistence becomes desired
 * (e.g. for stable per-account device identity), point the keystore at a
 * project-defined location instead of the temp dir.
 *
 * Wire 4.2.0 exposes MLS operations only inside [CoreCrypto.transaction] blocks
 * (single-writer pattern). We funnel all suspending calls through a [Mutex] to
 * surface a sequential, simple API to callers.
 *
 * Limitations vs DAVE (deferred to Phase 3.2):
 *   - [exportSecret] ignores its label argument (Wire 4.2.0 fixes it to "AVS").
 *   - No JVM->Android->iOS portability yet; this file is JVM-only.
 */
@Suppress("TooManyFunctions")
internal class WireMlsClient : MlsClient {

    private val logger = Logger.withTag("dave.WireMlsClient")
    private val mutex = Mutex()
    private val ciphersuite = Ciphersuite.MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519
    private val ciphersuites = Ciphersuites(setOf(ciphersuite))
    private val credentialType = CredentialType.Basic
    private val customConfig = CustomConfiguration(
        Duration.ofDays(KEY_ROTATION_DAYS),
        MlsWirePolicy.PLAINTEXT,
    )

    private lateinit var cc: CoreCrypto
    private var ownPublicKey: ByteArray? = null
    private var closed = false

    /**
     * Transport sink that captures the most recent CommitBundle emitted by Wire
     * for any conversation. Tests + the eventual gateway adapter both consume
     * `lastCommitBundle` to read `welcome` / `commit` bytes after an op that
     * advances the group (e.g. `addMember`).
     */
    private val capturingTransport = object : MlsTransport {
        @Volatile var lastCommitBundle: CommitBundle? = null
        override suspend fun sendMessage(mlsMessage: ByteArray): MlsTransportResponse =
            MlsTransportResponse.Success
        override suspend fun sendCommitBundle(commitBundle: CommitBundle): MlsTransportResponse {
            lastCommitBundle = commitBundle
            return MlsTransportResponse.Success
        }
    }

    override suspend fun init(userId: String): ByteArray = mutex.withLock {
        check(!::cc.isInitialized) { "MlsClient.init called twice" }
        val keystoreDir = Files.createTempDirectory("puklic-mls-").toFile().apply { deleteOnExit() }
        val keystorePath = keystoreDir.resolve("keystore").absolutePath
        // Random per-instance keystore key — group state is ephemeral, no need to derive.
        val keystoreKey = userId.hashCode().toUInt().toString(KEYSTORE_KEY_RADIX) + "-puklic-dave"
        cc = CoreCrypto(keystorePath, keystoreKey)
        cc.transaction { ctx: CoreCryptoContext ->
            ctx.mlsInit(ClientId(userId), ciphersuites, DEFAULT_NB_KEY_PACKAGE)
        }
        cc.provideTransport(capturingTransport)
        val pk: SignaturePublicKey = cc.transaction { ctx ->
            ctx.getPublicKey(ciphersuite, credentialType)
        }
        val bytes = pk.value
        ownPublicKey = bytes
        bytes
    }

    override suspend fun generateKeyPackage(): ByteArray = mutex.withLock {
        requireInited()
        val packages: List<MLSKeyPackage> = cc.transaction { ctx ->
            ctx.generateKeyPackages(1u, ciphersuite, credentialType)
        }
        packages.first().value
    }

    override suspend fun createGroup(channelId: String): String = mutex.withLock {
        requireInited()
        cc.transaction { ctx ->
            ctx.createConversation(
                MLSGroupId(channelId.toGroupIdBytes()),
                ciphersuite,
                credentialType,
                // Discord supplies the external-sender key via gateway op 25
                // (DAVE_MLS_EXTERNAL_SENDER_PACKAGE). For local-only smoke tests we
                // pass an empty list — `createConversation` accepts this.
                emptyList<ExternalSenderKey>(),
            )
        }
        channelId
    }

    override suspend fun processWelcome(welcomeBytes: ByteArray): String = mutex.withLock {
        requireInited()
        val bundle: WelcomeBundle = cc.transaction { ctx ->
            ctx.processWelcomeMessage(Welcome(welcomeBytes), customConfig)
        }
        bundle.id.value.decodeGroupIdString()
    }

    override suspend fun currentEpoch(groupId: String): Long = mutex.withLock {
        requireInited()
        val ulong: ULong = cc.transaction { ctx ->
            ctx.conversationEpoch(MLSGroupId(groupId.toGroupIdBytes()))
        }
        ulong.toLong()
    }

    override suspend fun exportSecret(groupId: String, label: String, length: Int): ByteArray =
        mutex.withLock {
            requireInited()
            if (label != DAVE_EXPORTER_LABEL) {
                logger.w {
                    "exportSecret: ignoring requested label='$label'. Wire 4.2.0 only exposes " +
                        "the AVS exporter. Tracked in ADR-0007 + architect report §3."
                }
            }
            val secret: AvsSecret = cc.transaction { ctx ->
                ctx.deriveAvsSecret(MLSGroupId(groupId.toGroupIdBytes()), length.toUInt())
            }
            secret.value
        }

    override suspend fun signaturePublicKey(): ByteArray = mutex.withLock {
        requireInited()
        ownPublicKey ?: error("public key not cached")
    }

    /**
     * Add a remote client to the group via its [keyPackage] and capture the
     * Wire-emitted [CommitBundle]. Returns the bytes that DAVE would forward over
     * voice-gateway opcode 28 (DAVE_MLS_COMMIT_WELCOME).
     *
     * NOT part of the [MlsClient] interface because Discord drives commit
     * production on the server side; this method exists for the local two-client
     * smoke test only.
     */
    internal suspend fun addMemberCapturingCommit(
        groupId: String,
        keyPackage: ByteArray,
    ): CapturedCommit = mutex.withLock {
        requireInited()
        capturingTransport.lastCommitBundle = null
        cc.transaction { ctx ->
            ctx.addMember(MLSGroupId(groupId.toGroupIdBytes()), listOf(MLSKeyPackage(keyPackage)))
            ctx.commitPendingProposals(MLSGroupId(groupId.toGroupIdBytes()))
        }
        val captured = capturingTransport.lastCommitBundle
            ?: error("CommitBundle was not emitted to MlsTransport after addMember/commit")
        CapturedCommit(commit = captured.commit.value, welcome = captured.welcome?.value)
    }

    override fun close() {
        if (closed) return
        closed = true
        if (::cc.isInitialized) {
            runCatching { cc.close() }.onFailure { logger.w(it) { "close() failed" } }
        }
    }

    private fun requireInited() {
        check(::cc.isInitialized) { "MlsClient.init() not called" }
        check(!closed) { "MlsClient is closed" }
    }

    private fun String.toGroupIdBytes(): ByteArray = this.toByteArray(Charsets.UTF_8)
    private fun ByteArray.decodeGroupIdString(): String = this.toString(Charsets.UTF_8)

    /** Result of a local commit-capture helper used by the smoke test. */
    internal data class CapturedCommit(val commit: ByteArray, val welcome: ByteArray?)

    private companion object {
        const val DAVE_EXPORTER_LABEL = "Discord Secure Frames v0"
        const val KEY_ROTATION_DAYS = 30L
        const val KEYSTORE_KEY_RADIX = 16
        val DEFAULT_NB_KEY_PACKAGE: UInt = 1u
    }
}

internal actual fun mlsClient(): MlsClient = WireMlsClient()
