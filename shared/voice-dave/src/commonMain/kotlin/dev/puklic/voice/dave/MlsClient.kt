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
     * Current MLS epoch for [groupId]. Each Commit advances this by 1.
     */
    suspend fun currentEpoch(groupId: String): Long

    /**
     * Derive a per-call/per-epoch exporter secret of [length] bytes for [groupId].
     *
     * NOTE (4.2.0 limitation): the underlying Wire 4.2.0 public API only exposes
     * a single MLS exporter with the fixed label `"AVS"` (via [`deriveAvsSecret`])
     * — it does NOT accept an arbitrary [label]. DAVE requires label
     * `"Discord Secure Frames v0"`. For the 3.1b spike we therefore IGNORE
     * [label] and return Wire's AVS secret; both parties derive identical bytes
     * so exporter parity for the smoke test holds. Producing DAVE-correct bytes
     * requires either:
     *   - upgrading to a Wire version exposing the raw MLS exporter, or
     *   - patching core-crypto, or
     *   - the libdave-JNI path (Phase 3.2).
     * This gap is tracked in the architect report §3 + ADR-0007 "Consequences".
     */
    suspend fun exportSecret(groupId: String, label: String, length: Int): ByteArray

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
