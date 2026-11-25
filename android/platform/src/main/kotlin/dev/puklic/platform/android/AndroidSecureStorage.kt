package dev.puklic.platform.android

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import dev.puklic.platform.PlatformFailed
import dev.puklic.platform.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException

/**
 * [SecureStorage] that keeps every value as AES-256-GCM ciphertext (see [KeystoreAesGcm]) in a private
 * `SharedPreferences` file; the key never leaves the Android Keystore. The entry key is authenticated
 * as associated data, so ciphertexts cannot be swapped between entries.
 *
 * `androidx.security:security-crypto` is deliberately not used: its `EncryptedSharedPreferences` is
 * deprecated and the last release is still an alpha-era API.
 *
 * An entry that can no longer be decrypted (the Keystore key was invalidated, for example after a
 * device restore) is dropped and reported as absent, so the user is sent through sign-in again.
 * Auto Backup is disabled for the app because Keystore keys are never restored on another device.
 */
class AndroidSecureStorage(context: Context) : SecureStorage {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val cipher = KeystoreAesGcm(KEY_ALIAS)

    override suspend fun put(key: String, value: String) {
        withContext(Dispatchers.IO) {
            val sealed = sealOrThrow(key, value)
            preferences.edit().putString(key, sealed).commitOrFail("Cannot persist secure entry")
        }
    }

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        preferences.getString(key, null)?.let { encoded -> openOrDrop(key, encoded) }
    }

    override suspend fun remove(key: String) {
        withContext(Dispatchers.IO) {
            preferences.edit().remove(key).commitOrFail("Cannot remove secure entry")
        }
    }

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        preferences.all.keys.sorted()
    }

    private fun SharedPreferences.Editor.commitOrFail(failure: String) {
        if (!commit()) throw PlatformFailed(failure)
    }

    private fun sealOrThrow(key: String, value: String): String =
        try {
            val sealed = cipher.seal(value.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(sealed, Base64.NO_WRAP)
        } catch (e: GeneralSecurityException) {
            throw PlatformFailed("Keystore encryption failed: ${e.message}")
        }

    private fun openOrDrop(key: String, encoded: String): String? =
        try {
            val sealed = Base64.decode(encoded, Base64.NO_WRAP)
            String(cipher.open(sealed, key.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            preferences.edit().remove(key).apply()
            null
        } catch (e: IllegalArgumentException) {
            preferences.edit().remove(key).apply()
            null
        }

    private companion object {
        const val PREFERENCES_NAME = "puklic_secure_storage"
        const val KEY_ALIAS = "puklic.secure-storage.v1"
    }
}
