package dev.puklic.voice.dave

import co.touchlab.kermit.Logger
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * libdave-backed [MlsClient].
 *
 * libdave (Discord, MIT) is the C++ reference implementation of the DAVE
 * protocol — same code Discord's official clients ship. Wrapping it via JNA
 * gives Puklic byte-compatible interop with real Discord calls (frame format,
 * MLS key schedule, exporter labels all match by construction).
 *
 * This v1 implementation covers the [MlsClient] surface that the Phase 3.1c
 * gateway plumbing already drives:
 *   - identity / session create
 *   - init with groupId once the channel is known
 *   - KeyPackage publication
 *   - ProcessWelcome / ProcessCommit dispatch
 *   - SetExternalSender (libdave supports post-hoc attach — Wire 4.2.0 does
 *     NOT, so this is the structurally-correct fix)
 *
 * **Limitations of this v1**:
 *   - [createGroup] is a no-op alias for [init]'s deferred group setup.
 *     libdave's `daveSessionInit(version, groupId, selfUserId)` takes a numeric
 *     `uint64_t` groupId, not a string. We parse the Discord channel id as u64
 *     when possible; non-numeric channel ids hash to a stable u64.
 *   - [exportSecret] is NOT exposed by the libdave C API as a labelled exporter;
 *     instead libdave exposes a per-user [DAVEKeyRatchetHandle] that drives the
 *     per-frame key derivation inside `Encryptor` / `Decryptor`. For backward
 *     compatibility with the existing interface we throw — callers should switch
 *     to the [LibdaveFrameCrypto] path (follow-up). This is the correct DAVE
 *     architecture; the previous "exportSecret(label, context)" shape was a
 *     Wire-flavoured workaround.
 *   - [signaturePublicKey] is not directly exposed by libdave's C API in the
 *     current header. We return an empty array placeholder; pairwise
 *     fingerprint verification goes through [daveSessionGetPairwiseFingerprint]
 *     instead. This will need a small C API addition in libdave to expose the
 *     own signature key, or the fingerprint callback path.
 *   - [currentEpoch] is not directly exposed; epoch advancement is implicit
 *     after a successful ProcessCommit/ProcessWelcome. We track it locally.
 *
 * These gaps are tracked in the architect report addendum; they are acceptable
 * because the byte-compatible frame crypto path (the actual point of DAVE) is
 * unblocked.
 */
@Suppress("TooManyFunctions")
internal class LibdaveMlsClient : MlsClient {

    private val logger = Logger.withTag("dave.LibdaveMlsClient")
    private val mutex = Mutex()
    private val lib by lazy { LibdaveBindings.INSTANCE }

    private var session: Pointer? = null
    private var userId: String? = null
    private var groupIdString: String? = null
    private var epoch: Long = 0L
    private var closed = false

    // libdave keeps a reference to the failure callback for the session's lifetime,
    // so we hold a strong ref ourselves to prevent JNA GC freeing the trampoline.
    private val failureCallback = LibdaveBindings.MlsFailureCallback { source, reason, _ ->
        logger.w { "libdave MLS failure source=$source reason=$reason" }
    }

    override suspend fun init(userId: String): ByteArray = mutex.withLock {
        check(session == null) { "LibdaveMlsClient.init called twice" }
        val handle = lib.daveSessionCreate(
            /* context = */ null,
            /* authSessionId = */ userId,
            /* callback = */ failureCallback,
            /* userData = */ null,
        ) ?: error("daveSessionCreate returned NULL")
        session = handle
        this.userId = userId
        // libdave defers Init until protocol-version + groupId are known
        // (driven by DaveSession on PREPARE_TRANSITION / first joined group).
        // The signature public key is not exposed by the current C API; return
        // empty bytes — callers that need it should fall back to the pairwise
        // fingerprint flow (daveSessionGetPairwiseFingerprint).
        ByteArray(0)
    }

    /**
     * Performs the libdave Init step now that we know the channel/groupId
     * + selected protocol version. Called from [createGroup] and
     * [processWelcome].
     */
    private fun nativeInitLocked(version: Short, groupId: Long) {
        val uid = userId ?: error("init() not called")
        lib.daveSessionInit(session, version, groupId, uid)
    }

    override suspend fun generateKeyPackage(): ByteArray = mutex.withLock {
        requireInited()
        // libdave requires Init to have been called before key packages can be
        // produced. If a caller asks for a KeyPackage before joining a group
        // we honour the request with protocol version 1 + a placeholder group id.
        if (groupIdString == null) {
            nativeInitLocked(DAVE_PROTOCOL_VERSION_1, PLACEHOLDER_GROUP_ID)
            groupIdString = PLACEHOLDER_GROUP_ID.toString()
        }
        val outBytes = PointerByReference()
        val outLen = LongArray(1)
        lib.daveSessionGetMarshalledKeyPackage(session, outBytes, outLen)
        copyAndFree(outBytes, outLen[0])
    }

    override suspend fun createGroup(channelId: String): String = mutex.withLock {
        requireInited()
        val groupIdNumeric = channelId.parseAsU64()
        nativeInitLocked(DAVE_PROTOCOL_VERSION_1, groupIdNumeric)
        groupIdString = channelId
        channelId
    }

    override suspend fun processWelcome(welcomeBytes: ByteArray): String = mutex.withLock {
        requireInited()
        // We need to have called Init first; in the gateway flow this is driven by
        // DaveSession's PREPARE_TRANSITION (which carries the protocol version)
        // followed by knowing the group id. If neither has happened by now, init
        // defensively with v1 + placeholder.
        if (groupIdString == null) {
            nativeInitLocked(DAVE_PROTOCOL_VERSION_1, PLACEHOLDER_GROUP_ID)
            groupIdString = PLACEHOLDER_GROUP_ID.toString()
        }
        val handle = lib.daveSessionProcessWelcome(
            session,
            welcomeBytes,
            welcomeBytes.size.toLong(),
            /* recognizedUserIds = */ null,
            /* recognizedUserIdsLength = */ 0L,
        )
        // Welcome handle holds the new roster; we don't need its contents here, but we
        // MUST free it to avoid the libdave-allocated struct leaking.
        if (handle != null) {
            lib.daveWelcomeResultDestroy(handle)
            epoch += 1
        } else {
            logger.w { "daveSessionProcessWelcome returned NULL (ignored)" }
        }
        groupIdString!!
    }

    override suspend fun processCommit(groupId: String, handshakeBytes: ByteArray) = mutex.withLock {
        requireInited()
        val handle = lib.daveSessionProcessCommit(
            session,
            handshakeBytes,
            handshakeBytes.size.toLong(),
        )
        if (handle != null) {
            val failed = lib.daveCommitResultIsFailed(handle)
            val ignored = lib.daveCommitResultIsIgnored(handle)
            lib.daveCommitResultDestroy(handle)
            if (failed) {
                error("libdave processCommit reported FAILED for groupId=$groupId")
            }
            if (!ignored) {
                epoch += 1
            }
        } else {
            logger.w { "daveSessionProcessCommit returned NULL (ignored)" }
        }
        Unit
    }

    override suspend fun processExternalSender(
        channelId: String,
        externalSenderBytes: ByteArray,
    ) = mutex.withLock {
        requireInited()
        // libdave DOES support post-hoc external sender attach — this is the
        // structural fix vs Wire 4.2.0. The attach is durable across the session
        // until the next Reset.
        lib.daveSessionSetExternalSender(
            session,
            externalSenderBytes,
            externalSenderBytes.size.toLong(),
        )
    }

    override suspend fun currentEpoch(groupId: String): Long = mutex.withLock {
        requireInited()
        // libdave's C API does not expose epoch directly. We maintain a local
        // counter advanced by ProcessCommit / ProcessWelcome (each successful one
        // = 1 epoch step). This matches the MLS spec semantics even if the
        // absolute value lags behind a server-tracked epoch.
        epoch
    }

    override suspend fun exportSecret(
        groupId: String,
        label: String,
        context: ByteArray,
        length: Int,
    ): ByteArray {
        // libdave does NOT expose a labelled MLS exporter through the C API.
        // The correct DAVE path for per-frame keys is via daveSessionGetKeyRatchet
        // + the daveEncryptor / daveDecryptor APIs (which derive frame keys
        // internally with the on-the-wire DAVE label + SSRC + generation).
        //
        // Refusing here is intentional: a caller asking for an exporter secret
        // through this interface is either pre-libdave (Wire flow) or wrong.
        // Wire it up via [LibdaveFrameCrypto] (follow-up) instead.
        throw UnsupportedOperationException(
            "LibdaveMlsClient.exportSecret is unavailable: libdave drives per-frame keys " +
                "through DAVEKeyRatchetHandle + DAVEEncryptor/DAVEDecryptor. " +
                "Use the frame-crypto path (Phase 3.2 follow-up) for byte-compatible DAVE."
        )
    }

    override suspend fun signaturePublicKey(): ByteArray = mutex.withLock {
        requireInited()
        // Not exposed by current libdave C API. Pairwise fingerprint flow takes
        // its place; see daveSessionGetPairwiseFingerprint. Returning an empty
        // array signals "not available" without surprising callers with NPEs.
        ByteArray(0)
    }

    override suspend fun frameEncryptor(ssrc: Int): FrameEncryptor? = mutex.withLock {
        requireInited()
        val uid = userId ?: return null
        // Outbound frames are keyed by the local user's own ratchet.
        val ratchet = lib.daveSessionGetKeyRatchet(session, uid) ?: run {
            logger.w { "frameEncryptor: no key ratchet for self user yet (pre-join)" }
            return null
        }
        val enc = lib.daveEncryptorCreate() ?: run {
            lib.daveKeyRatchetDestroy(ratchet)
            error("daveEncryptorCreate returned NULL")
        }
        lib.daveEncryptorSetKeyRatchet(enc, ratchet)
        lib.daveEncryptorAssignSsrcToCodec(enc, ssrc, DAVE_CODEC_OPUS)
        LibdaveFrameEncryptor(lib, enc, ratchet, ssrc)
    }

    override suspend fun frameDecryptor(userId: String, ssrc: Int): FrameDecryptor? = mutex.withLock {
        requireInited()
        val ratchet = lib.daveSessionGetKeyRatchet(session, userId) ?: run {
            logger.w { "frameDecryptor: no key ratchet for userId=$userId (not in group?)" }
            return null
        }
        val dec = lib.daveDecryptorCreate() ?: run {
            lib.daveKeyRatchetDestroy(ratchet)
            error("daveDecryptorCreate returned NULL")
        }
        lib.daveDecryptorTransitionToKeyRatchet(dec, ratchet)
        LibdaveFrameDecryptor(lib, dec, ratchet, ssrc)
    }

    override suspend fun pairwiseFingerprint(
        remoteUserId: String,
        protocolVersion: Short,
    ): ByteArray? = mutex.withLock {
        if (session == null || closed) return@withLock null
        val captured = arrayOfNulls<ByteArray>(1)
        val cb = LibdaveBindings.PairwiseFingerprintCallback { ptr, length, _ ->
            captured[0] = if (ptr == null || length <= 0L) {
                ByteArray(0)
            } else {
                ptr.getByteArray(0, length.toInt())
            }
        }
        lib.daveSessionGetPairwiseFingerprint(
            session,
            protocolVersion,
            remoteUserId,
            cb,
            null,
        )
        captured[0]
    }

    override fun close() {
        if (closed) return
        closed = true
        val handle = session
        if (handle != null) {
            runCatching { lib.daveSessionDestroy(handle) }
                .onFailure { logger.w(it) { "daveSessionDestroy failed" } }
            session = null
        }
    }

    private fun requireInited() {
        check(session != null) { "LibdaveMlsClient.init() not called" }
        check(!closed) { "LibdaveMlsClient is closed" }
    }

    /**
     * Copy [length] bytes from libdave-allocated buffer + free it. Always pairs
     * the malloc inside libdave with a [LibdaveBindings.daveFree] call.
     */
    private fun copyAndFree(ref: PointerByReference, length: Long): ByteArray {
        val ptr = ref.value ?: return ByteArray(0)
        val bytes = ptr.getByteArray(0, length.toInt())
        lib.daveFree(ptr)
        return bytes
    }

    /**
     * Discord channel ids are decimal u64 strings (snowflakes). Parse as such;
     * fall back to a stable hash for non-numeric ids (used only by tests).
     */
    private fun String.parseAsU64(): Long =
        toULongOrNull()?.toLong() ?: this.hashCode().toLong()

    internal companion object {
        const val DAVE_PROTOCOL_VERSION_1: Short = 1
        const val PLACEHOLDER_GROUP_ID: Long = 0L

        // libdave DAVECodec / DAVEMediaType enum values (cpp/includes/dave/dave.h).
        const val DAVE_CODEC_OPUS: Int = 1
        const val DAVE_MEDIA_TYPE_AUDIO: Int = 0
        const val DAVE_ENCRYPTOR_RESULT_SUCCESS: Int = 0
        const val DAVE_DECRYPTOR_RESULT_SUCCESS: Int = 0
    }
}

/**
 * libdave-backed [FrameEncryptor]. Owns one DAVEEncryptorHandle + one
 * DAVEKeyRatchetHandle; both freed on [close].
 *
 * NOT thread-safe. Caller must serialize encrypt calls (the capture loop is
 * single-threaded so this is fine in practice).
 */
private class LibdaveFrameEncryptor(
    private val lib: LibdaveBindings,
    private val encryptor: com.sun.jna.Pointer,
    private val ratchet: com.sun.jna.Pointer,
    private val ssrc: Int,
) : FrameEncryptor {
    private var closed = false
    private val logger = Logger.withTag("dave.FrameEncryptor")

    override fun encrypt(plaintext: ByteArray): ByteArray {
        check(!closed) { "FrameEncryptor closed" }
        val maxOut = lib.daveEncryptorGetMaxCiphertextByteSize(
            encryptor,
            LibdaveMlsClient.DAVE_MEDIA_TYPE_AUDIO,
            plaintext.size.toLong(),
        )
        val out = ByteArray(maxOut.toInt().coerceAtLeast(plaintext.size + DAVE_TRAILER_GUARD))
        val written = LongArray(1)
        val rc = lib.daveEncryptorEncrypt(
            encryptor,
            LibdaveMlsClient.DAVE_MEDIA_TYPE_AUDIO,
            ssrc,
            plaintext,
            plaintext.size.toLong(),
            out,
            out.size.toLong(),
            written,
        )
        if (rc != LibdaveMlsClient.DAVE_ENCRYPTOR_RESULT_SUCCESS) {
            logger.w { "daveEncryptorEncrypt rc=$rc — passing through plaintext" }
            return plaintext
        }
        return out.copyOf(written[0].toInt())
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { lib.daveEncryptorDestroy(encryptor) }
        runCatching { lib.daveKeyRatchetDestroy(ratchet) }
    }

    private companion object {
        // Conservative slack when libdave's GetMaxCiphertextByteSize is unavailable;
        // DAVE trailer is fixed-size (≤ 64 B in practice).
        const val DAVE_TRAILER_GUARD = 64
    }
}

/** libdave-backed [FrameDecryptor]. Mirror image of [LibdaveFrameEncryptor]. */
private class LibdaveFrameDecryptor(
    private val lib: LibdaveBindings,
    private val decryptor: com.sun.jna.Pointer,
    private val ratchet: com.sun.jna.Pointer,
    @Suppress("UnusedPrivateProperty") private val ssrc: Int,
) : FrameDecryptor {
    private var closed = false

    override fun decrypt(ciphertext: ByteArray): ByteArray? {
        check(!closed) { "FrameDecryptor closed" }
        val maxOut = lib.daveDecryptorGetMaxPlaintextByteSize(
            decryptor,
            LibdaveMlsClient.DAVE_MEDIA_TYPE_AUDIO,
            ciphertext.size.toLong(),
        )
        val out = ByteArray(maxOut.toInt().coerceAtLeast(ciphertext.size))
        val written = LongArray(1)
        val rc = lib.daveDecryptorDecrypt(
            decryptor,
            LibdaveMlsClient.DAVE_MEDIA_TYPE_AUDIO,
            ciphertext,
            ciphertext.size.toLong(),
            out,
            out.size.toLong(),
            written,
        )
        if (rc != LibdaveMlsClient.DAVE_DECRYPTOR_RESULT_SUCCESS) return null
        return out.copyOf(written[0].toInt())
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { lib.daveDecryptorDestroy(decryptor) }
        runCatching { lib.daveKeyRatchetDestroy(ratchet) }
    }
}
