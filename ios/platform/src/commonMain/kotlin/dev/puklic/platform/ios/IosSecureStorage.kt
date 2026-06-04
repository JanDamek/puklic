package dev.puklic.platform.ios

import dev.puklic.platform.PlatformFailed
import dev.puklic.platform.SecureStorage
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFBooleanRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
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
        val status = withSecQuery(addAttrs(key, data)) { cfDict ->
            SecItemAdd(cfDict, null)
        }
        when (status) {
            errSecSuccess -> Unit
            errSecDuplicateItem -> {
                remove(key)
                val status2 = withSecQuery(addAttrs(key, data)) { cfDict ->
                    SecItemAdd(cfDict, null)
                }
                if (status2 != errSecSuccess) {
                    throw PlatformFailed("SecItemAdd (replace) failed: $status2")
                }
            }
            else -> throw PlatformFailed("SecItemAdd failed: $status")
        }
    }

    override suspend fun get(key: String): String? {
        val entries = lookupAttrs(key) + listOf(
            kSecMatchLimit to kSecMatchLimitOne,
            kSecReturnData to true,
        )
        return memScoped {
            val out = alloc<CFTypeRefVar>()
            val status = withSecQuery(entries) { cfDict ->
                SecItemCopyMatching(cfDict, out.ptr)
            }
            when (status) {
                errSecSuccess -> {
                    val data = CFBridgingRelease(out.value) as? NSData
                    data?.toUtf8String()
                }
                errSecItemNotFound -> null
                else -> throw PlatformFailed("SecItemCopyMatching failed: $status")
            }
        }
    }

    override suspend fun remove(key: String) {
        val status = withSecQuery(lookupAttrs(key)) { cfDict ->
            SecItemDelete(cfDict)
        }
        if (status != errSecSuccess && status != errSecItemNotFound) {
            throw PlatformFailed("SecItemDelete failed: $status")
        }
    }

    override suspend fun list(): List<String> {
        val entries = listOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to serviceName,
            kSecAttrSynchronizable to kSecAttrSynchronizableAny,
            kSecMatchLimit to kSecMatchLimitAll,
            kSecReturnAttributes to true,
        )
        return memScoped {
            val out = alloc<CFTypeRefVar>()
            val status = withSecQuery(entries) { cfDict ->
                SecItemCopyMatching(cfDict, out.ptr)
            }
            when (status) {
                errSecSuccess -> {
                    val items = CFBridgingRelease(out.value) as? List<*>
                    items.orEmpty()
                        .mapNotNull { it as? NSDictionary }
                        .mapNotNull { dict -> dict.objectForKey(ACCOUNT_ATTR_KEY) as? String }
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
    internal fun addAttrs(key: String, value: NSData): List<Pair<CFTypeRef?, Any?>> = listOf(
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
    internal fun lookupAttrs(key: String): List<Pair<CFTypeRef?, Any?>> = listOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to serviceName,
        kSecAttrAccount to key,
        kSecAttrSynchronizable to kSecAttrSynchronizableAny,
    )

    companion object {
        internal const val DEFAULT_SERVICE = "puklic-client"

        /** The keychain attribute key string under which the account name is returned. */
        private const val ACCOUNT_ATTR_KEY = "acct"
    }
}

/**
 * Builds a real CoreFoundation dictionary from the given attribute [entries],
 * runs [block] with it, and releases the dictionary plus every CFTypeRef
 * created for value bridging afterwards.
 *
 * Keys are the `kSec*` constants (already `CFStringRef` pointers). Values are
 * bridged per type:
 *  - a `kSec*` CFType constant → passed directly (already a CFTypeRef pointer)
 *  - [Boolean] → `kCFBooleanTrue` / `kCFBooleanFalse`
 *  - [String] → `CFBridgingRetain(s as NSString)` (released in `finally`)
 *  - [NSData] → `CFBridgingRetain(data)` (released in `finally`)
 *
 * The dictionary retains its own +1 on each inserted value, so the bridged
 * CFTypeRefs we created can be released once the SecItem call has returned.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun <R> withSecQuery(
    entries: List<Pair<CFTypeRef?, Any?>>,
    block: (CFDictionaryRef?) -> R,
): R {
    val dict = CFDictionaryCreateMutable(
        kCFAllocatorDefault,
        entries.size.toLong(),
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
    )
    val owned = mutableListOf<CFTypeRef>()
    try {
        for ((key, raw) in entries) {
            val cfValue: CFTypeRef? = when (raw) {
                is Boolean -> if (raw) kCFBooleanTrue.toCFType() else kCFBooleanFalse.toCFType()
                is String -> CFBridgingRetain(raw as NSString).also { it?.let(owned::add) }
                is NSData -> CFBridgingRetain(raw).also { it?.let(owned::add) }
                else -> @Suppress("UNCHECKED_CAST") (raw as? CFTypeRef)
            }
            CFDictionaryAddValue(dict, key, cfValue)
        }
        return block(dict)
    } finally {
        owned.forEach { CFRelease(it) }
        CFRelease(dict)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CFBooleanRef?.toCFType(): CFTypeRef? = this

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun String.toNSData(): NSData? =
    (this as NSString).dataUsingEncoding(NSUTF8StringEncoding)

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun NSData.toUtf8String(): String? =
    NSString.create(this, NSUTF8StringEncoding) as String?
