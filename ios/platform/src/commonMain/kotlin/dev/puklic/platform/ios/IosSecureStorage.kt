package dev.puklic.platform.ios

import dev.puklic.platform.PlatformFailed
import dev.puklic.platform.SecureStorage
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecAttrSynchronizableAny
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitAll
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * Keychain-backed [SecureStorage] using Security framework `SecItem*` APIs.
 *
 * Items are stored as `kSecClassGenericPassword` keyed by
 * `(kSecAttrService = serviceName, kSecAttrAccount = key)`. The default
 * service name matches the desktop macOS implementation
 * (`MacOsSecureStorage.DEFAULT_SERVICE = "puklic-client"`) so the same
 * conceptual key namespace is used across Apple platforms.
 *
 * **iCloud Keychain sync (Issue #74):** every write sets
 * `kSecAttrSynchronizable = true`, which marks the item for iCloud Keychain
 * propagation across the user's Apple devices. Reads / deletes use
 * `kSecAttrSynchronizableAny` so both synced and any pre-existing
 * non-synced rows are matched. The Discord token written on one Apple
 * device (Mac App Store build of Puklic or iOS app) thus appears on every
 * other signed-in device a few seconds later — no manual paste needed.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosSecureStorage(
    private val serviceName: String = DEFAULT_SERVICE,
) : SecureStorage {

    override suspend fun put(key: String, value: String) {
        val data = value.toNSData() ?: throw PlatformFailed("Unable to encode value as UTF-8")
        val query = addAttrs(key, data)
        val status = SecItemAdd(query.toCF(), null)
        when (status) {
            errSecSuccess -> Unit
            errSecDuplicateItem -> {
                remove(key)
                val status2 = SecItemAdd(query.toCF(), null)
                if (status2 != errSecSuccess) {
                    throw PlatformFailed("SecItemAdd (replace) failed: $status2")
                }
            }
            else -> throw PlatformFailed("SecItemAdd failed: $status")
        }
    }

    override suspend fun get(key: String): String? {
        val query = lookupAttrs(key) + mapOf<Any?, Any?>(
            kSecMatchLimit to kSecMatchLimitOne,
            kSecReturnData to true,
        )
        return memScoped {
            val out = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query.toCF(), out.ptr)
            when (status) {
                errSecSuccess -> {
                    @Suppress("UNCHECKED_CAST")
                    val data = out.value as? NSData
                    data?.toUtf8String()
                }
                errSecItemNotFound -> null
                else -> throw PlatformFailed("SecItemCopyMatching failed: $status")
            }
        }
    }

    override suspend fun remove(key: String) {
        val status = SecItemDelete(lookupAttrs(key).toCF())
        if (status != errSecSuccess && status != errSecItemNotFound) {
            throw PlatformFailed("SecItemDelete failed: $status")
        }
    }

    override suspend fun list(): List<String> {
        val query = mapOf<Any?, Any?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to serviceName,
            kSecAttrSynchronizable to kSecAttrSynchronizableAny,
            kSecMatchLimit to kSecMatchLimitAll,
            kSecReturnAttributes to true,
        )
        return memScoped {
            val out = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query.toCF(), out.ptr)
            when (status) {
                errSecSuccess -> {
                    @Suppress("UNCHECKED_CAST")
                    val items = out.value as? List<Map<Any?, Any?>>
                    items.orEmpty()
                        .mapNotNull { entry ->
                            val acct = entry.entries.firstOrNull { (k, _) ->
                                (k as? NSString)?.toString() == "acct" || (k as? String) == "acct"
                            }?.value
                            acct as? String
                        }
                        .distinct()
                }
                errSecItemNotFound -> emptyList()
                else -> throw PlatformFailed("SecItemCopyMatching (list) failed: $status")
            }
        }
    }

    /**
     * Attributes for [SecItemAdd]: marks the item as iCloud-synchronizable
     * with `kSecAttrSynchronizable = true`. The added value bytes go in
     * `kSecValueData`.
     */
    internal fun addAttrs(key: String, value: NSData): Map<Any?, Any?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to serviceName,
        kSecAttrAccount to key,
        kSecAttrSynchronizable to true,
        kSecValueData to value,
    )

    /**
     * Attributes for [SecItemCopyMatching] / [SecItemDelete]: uses
     * `kSecAttrSynchronizable = kSecAttrSynchronizableAny` so both synced
     * and any pre-existing non-synced rows are matched (avoids leaking a
     * pre-#74 local-only row that would otherwise become invisible).
     */
    internal fun lookupAttrs(key: String): Map<Any?, Any?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to serviceName,
        kSecAttrAccount to key,
        kSecAttrSynchronizable to kSecAttrSynchronizableAny,
    )

    companion object {
        internal const val DEFAULT_SERVICE = "puklic-client"
    }
}

/**
 * Build an immutable NSDictionary and toll-free-bridge it to CFDictionaryRef.
 * Kotlin/Native's Apple binding accepts NSDictionary where CFDictionaryRef is
 * expected via reinterpret cast — this is the documented K/N pattern (toll-free
 * bridging between NS and CF collection types).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Suppress("UNCHECKED_CAST")
private fun Map<Any?, Any?>.toCF(): CFDictionaryRef {
    return (this as NSDictionary) as CFDictionaryRef
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun String.toNSData(): NSData? =
    (this as NSString).dataUsingEncoding(NSUTF8StringEncoding)

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun NSData.toUtf8String(): String? =
    NSString.create(this, NSUTF8StringEncoding) as String?
