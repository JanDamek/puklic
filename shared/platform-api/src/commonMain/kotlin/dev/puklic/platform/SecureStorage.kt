package dev.puklic.platform

/**
 * Platform-native secret store (Keychain / libsecret / Credential Manager / Keystore).
 *
 * Used for the Discord token and other long-lived credentials.
 * Implementations MUST NOT leak the underlying native exception types — wrap into [PlatformException].
 */
interface SecureStorage {
    suspend fun put(key: String, value: String)
    suspend fun get(key: String): String?
    suspend fun remove(key: String)

    /** Returns the keys currently stored. Values are never enumerated. */
    suspend fun list(): kotlin.collections.List<String>
}
