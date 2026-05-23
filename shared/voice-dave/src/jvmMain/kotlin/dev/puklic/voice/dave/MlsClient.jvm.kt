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
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
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
 * Limitations vs DAVE (deferred to Phase 3.2 libdave-JNI):
 *   - Wire 4.2.0's MLS exporter label is hardcoded to `"exporter"` (empty
 *     context). DAVE wants label `"Discord Secure Frames v0"` with context =
 *     SSRC || generation. We layer a local HKDF-Expand-Label on top of Wire's
 *     secret so the bytes are keyed by (label, context) locally; this is NOT
 *     wire-compatible with other DAVE clients.
 *   - [processExternalSender] cannot retrofit a sender onto an already-created
 *     group in Wire 4.2.0; tracked as a structural gap.
 *   - JVM-only; iOS/Android backends land later.
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

    override suspend fun processCommit(groupId: String, handshakeBytes: ByteArray) = mutex.withLock {
        requireInited()
        cc.transaction { ctx ->
            ctx.decryptMessage(
                MLSGroupId(groupId.toGroupIdBytes()),
                MlsMessage(handshakeBytes),
            )
        }
        Unit
    }

    override suspend fun processExternalSender(
        channelId: String,
        externalSenderBytes: ByteArray,
    ) {
        // Wire 4.2.0 only accepts external senders at createGroup-time. We cannot
        // attach one post-hoc through the public API. Recording for future use;
        // a full fix requires Phase 3.2 libdave-JNI or a reorder where we defer
        // createGroup until ESPP has arrived. See architect report §3.
        logger.w {
            "processExternalSender: Wire 4.2.0 has no post-hoc attach API. " +
                "Ignoring ${externalSenderBytes.size} bytes for channelId=$channelId."
        }
    }

    override suspend fun currentEpoch(groupId: String): Long = mutex.withLock {
        requireInited()
        val ulong: ULong = cc.transaction { ctx ->
            ctx.conversationEpoch(MLSGroupId(groupId.toGroupIdBytes()))
        }
        ulong.toLong()
    }

    override suspend fun exportSecret(
        groupId: String,
        label: String,
        context: ByteArray,
        length: Int,
    ): ByteArray = mutex.withLock {
        requireInited()
        // Wire's MLS exporter is hardcoded to label="exporter", context=[]. We pull
        // its `length`-byte output (= IKM) and re-derive via local HKDF-Expand with
        // DAVE's label + caller context. NOT wire-compatible with Discord/other DAVE
        // clients (their MLS-Exporter output differs because the label feeds into
        // the MLS key schedule itself); is byte-stable on both sides locally.
        val wireSecret: AvsSecret = cc.transaction { ctx ->
            ctx.deriveAvsSecret(MLSGroupId(groupId.toGroupIdBytes()), EXPORTER_IKM_LEN.toUInt())
        }
        hkdfExpandLabel(wireSecret.value, label, context, length)
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
        const val KEY_ROTATION_DAYS = 30L
        const val KEYSTORE_KEY_RADIX = 16
        val DEFAULT_NB_KEY_PACKAGE: UInt = 1u
        // Length of IKM pulled from Wire's exporter before our local HKDF-Expand.
        // 32 B = SHA-256 output size = a full HKDF PRK.
        const val EXPORTER_IKM_LEN = 32

        /**
         * RFC 5869 HKDF-Expand with `info = label || context`. Used as a local
         * post-process on top of Wire's MLS exporter to inject DAVE's label +
         * SSRC/generation context. NOT MLS-Exporter spec-compliant — see [exportSecret]
         * KDoc for why.
         */
        fun hkdfExpandLabel(prk: ByteArray, label: String, context: ByteArray, length: Int): ByteArray {
            val labelBytes = label.toByteArray(Charsets.UTF_8)
            val info = ByteArray(labelBytes.size + context.size).also { out ->
                labelBytes.copyInto(out, 0)
                context.copyInto(out, labelBytes.size)
            }
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            // skip=true == HKDF-Expand only (PRK is already a PRK from Wire's exporter)
            hkdf.init(HKDFParameters.skipExtractParameters(prk, info))
            val out = ByteArray(length)
            hkdf.generateBytes(out, 0, length)
            return out
        }
    }
}

internal actual fun mlsClient(): MlsClient = WireMlsClient()
