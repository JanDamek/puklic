package dev.puklic.voice.dave

/**
 * Thin Puklic-internal wrapper around an MLS (RFC 9420) implementation.
 *
 * Exposes only the surface DAVE needs:
 *   - identity bootstrap
 *   - KeyPackage publication
 *   - group lifecycle (create / join via Welcome / advance epoch via Commit)
 *   - per-epoch exporter secret for DAVE per-frame key derivation
 *
 * NO Wire-specific types (CoreCrypto, MLSKeyPackage, AvsSecret, ...) cross this
 * boundary. Callers see only opaque ByteArrays + primitives. This keeps the
 * domain layer free of GPL-3.0 type imports and lets us swap the backend
 * (Wire core-crypto -> libdave-JNI, in Phase 3.2) without disturbing call sites.
 *
 * All methods are suspending: the underlying core-crypto API is async (Rust
 * JNI with a coroutine bridge) and we propagate that.
 *
 * Lifecycle: build via [mlsClient], call [init] exactly once, use group methods,
 * call [close] when done.
 */
internal interface MlsClient {

    /**
     * Initialize a fresh MLS identity bound to [userId] (typically the Discord
     * user id as a stable string). Returns the signature public key bytes — the
     * input to pairwise fingerprint derivation (SHA-256 over concatenated keys).
     *
     * Idempotent across [close]/recreate cycles only via on-disk state (path
     * decided by the backend implementation). Calling [init] twice on the same
     * instance is an error.
     */
    suspend fun init(userId: String): ByteArray

    /**
     * Generate exactly one KeyPackage suitable for being uploaded to Discord
     * over voice-gateway opcode 26 (DAVE_MLS_KEY_PACKAGE). The returned bytes
     * are the wire-format MLS KeyPackage TLS-encoded.
     */
    suspend fun generateKeyPackage(): ByteArray

    /**
     * Create a new MLS group (initially one-member: just self) using [channelId]
     * as the group/conversation id. Discord uses voice channel id; we accept any
     * string and hash it to bytes internally if the backend needs that.
     *
     * Returns the canonical group id as a string (typically the input echoed).
     */
    suspend fun createGroup(channelId: String): String

    /**
     * Process an MLS Welcome message (delivered over voice-gateway opcode 30
     * DAVE_MLS_WELCOME). Installs group state at the joining epoch.
     *
     * @return the group id the local client is now a member of.
     */
    suspend fun processWelcome(welcomeBytes: ByteArray): String

    /**
     * Process an MLS Commit / Proposal handshake message for an already-joined
     * [groupId]. Used for voice-gateway opcodes 27 (DAVE_MLS_PROPOSALS) and
     * 29 (DAVE_MLS_ANNOUNCE_COMMIT_TRANSITION). Advances the epoch when the
     * payload is a Commit.
     */
    suspend fun processCommit(groupId: String, handshakeBytes: ByteArray)

    /**
     * Register the Discord-supplied external sender public key for [groupId].
     * Voice-gateway opcode 25 (DAVE_MLS_EXTERNAL_SENDER_PACKAGE).
     *
     * In Wire 4.2.0 the external sender is supplied at [createGroup] time as a
     * `List<ExternalSenderKey>`; there is no post-hoc API to attach one. For now
     * this method is a no-op placeholder that records the bytes; a full fix
     * requires either reordering the protocol (defer createGroup until ESPP
     * arrives) or upgrading Wire. Tracked in architect report §3.
     */
    suspend fun processExternalSender(channelId: String, externalSenderBytes: ByteArray)

    /**
     * Current MLS epoch for [groupId]. Each Commit advances this by 1.
     */
    suspend fun currentEpoch(groupId: String): Long

    /**
     * Derive a per-call/per-epoch exporter secret of [length] bytes for [groupId],
     * keyed by [label] + [context] (DAVE uses label `"Discord Secure Frames v0"`
     * and context = SSRC || generation).
     *
     * **Wire 4.2.0 limitation (HARD).** The Wire 4.2.0 public + lower uniffi
     * APIs both expose only `exportSecretKey(conversationId, keyLength)` — neither
     * accepts a label nor a context. Inspection of the Wire `crypto/src/mls/
     * conversation/export.rs` source (v4.2.0) shows the label is hardcoded to
     * `"exporter"` with empty context inside the Rust core, so reflection cannot
     * help — the FFI symbol simply does not exist. Consequence: bytes returned
     * here are NOT DAVE-byte-compatible with the Discord protocol and other DAVE
     * clients. They are stable, per-epoch, per-group, but keyed by Wire's
     * `"exporter"` label, not DAVE's.
     *
     * We do, however, apply DAVE-shaped local HKDF on top of Wire's secret using
     * [label] and [context] (RFC 5869 HKDF-Expand) so that the bytes are also
     * keyed by SSRC + generation locally. This is wrong-on-the-wire (will not
     * interop with Discord) but correct-by-shape (any change in label or context
     * changes the bytes; identical (label, context) on both sides yields
     * identical bytes; new epoch yields new bytes).
     *
     * The structurally-correct fix lands with the libdave-JNI backend in Phase
     * 3.2 (architect report §3 path B). See ADR-0007.
     */
    suspend fun exportSecret(
        groupId: String,
        label: String,
        context: ByteArray,
        length: Int,
    ): ByteArray

    /**
     * Own signature public key (same value as returned by [init]; cached after
     * init so callers don't need to retain the init return value).
     */
    suspend fun signaturePublicKey(): ByteArray

    /** Release native resources. Idempotent. */
    fun close()
}

/** Backend factory. JVM actual returns the Wire core-crypto-backed impl. */
internal expect fun mlsClient(): MlsClient
