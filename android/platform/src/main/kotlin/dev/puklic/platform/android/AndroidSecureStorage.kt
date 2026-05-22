package dev.puklic.platform.android

import dev.puklic.platform.SecureStorage

/**
 * Phase 1 stub. Phase 2 will back this with Android Keystore + EncryptedSharedPreferences.
 */
class AndroidSecureStorage : SecureStorage {
    override suspend fun put(key: String, value: String): Unit = throw NotImplementedError(PHASE_2)
    override suspend fun get(key: String): String? = throw NotImplementedError(PHASE_2)
    override suspend fun remove(key: String): Unit = throw NotImplementedError(PHASE_2)
    override suspend fun list(): List<String> = throw NotImplementedError(PHASE_2)

    private companion object {
        const val PHASE_2 = "AndroidSecureStorage: Phase 2"
    }
}
