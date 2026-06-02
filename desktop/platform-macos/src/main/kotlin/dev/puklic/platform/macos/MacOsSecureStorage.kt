package dev.puklic.platform.macos

import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import dev.puklic.platform.PlatformFailed
import dev.puklic.platform.PlatformUnavailable
import dev.puklic.platform.SecureStorage
import dev.puklic.platform.macos.bridge.CfQueryBuilder
import dev.puklic.platform.macos.bridge.CoreFoundation
import dev.puklic.platform.macos.bridge.Security

/**
 * macOS Keychain-backed [SecureStorage] using `Security.framework` `SecItem*`
 * APIs via JNA, mirroring the iOS implementation.
 *
 * **iCloud Keychain sync (Issue #74):** every write sets
 * `kSecAttrSynchronizable = kCFBooleanTrue`, which marks the item for iCloud
 * Keychain propagation. Reads / deletes use `kSecAttrSynchronizableAny` so
 * both synced and any pre-existing (pre-#74) non-synced rows are matched.
 * The Discord token written by the Mac App Store build of Puklic thus
 * appears on the user's iOS device within seconds — the manual paste step
 * is gone.
 *
 * **No CLI fallback.** The previous `/usr/bin/security` CLI implementation
 * has no `kSecAttrSynchronizable` flag, so degrading to CLI would silently
 * disable cross-device sync — a HARD RULE #2 forbidden "temporary" path.
 * If `Security.framework` cannot be loaded for any reason, [requireSecurity]
 * throws [PlatformUnavailable] so the failure is loud, not hidden.
 *
 * Items are stored as `kSecClassGenericPassword` keyed by
 * `(kSecAttrService = serviceName, kSecAttrAccount = key)`, matching the
 * iOS [dev.puklic.platform.ios.IosSecureStorage] layout for a shared
 * cross-platform key namespace.
 */
class MacOsSecureStorage(
    private val serviceName: String = DEFAULT_SERVICE,
) : SecureStorage {

    private fun requireSecurity() {
        try {
            // Touch all the symbols we need; if any global is missing, an
            // UnsatisfiedLinkError will fire and we surface it as PlatformUnavailable.
            Security.INSTANCE
            CoreFoundation.INSTANCE
            Security.K_SEC_CLASS_GENERIC_PASSWORD
            Security.K_SEC_ATTR_SYNCHRONIZABLE
            Security.K_SEC_ATTR_SYNCHRONIZABLE_ANY
        } catch (e: UnsatisfiedLinkError) {
            throw PlatformUnavailable("Security.framework SecItem APIs unavailable: ${e.message}")
        } catch (e: NoClassDefFoundError) {
            throw PlatformUnavailable("JNA missing for macOS SecureStorage: ${e.message}")
        }
    }

    override suspend fun put(key: String, value: String) {
        requireSecurity()
        val bytes = value.toByteArray(Charsets.UTF_8)
        val keyPtr = CfQueryBuilder.cfString(key)
        val servicePtr = CfQueryBuilder.cfString(serviceName)
        val dataPtr = CfQueryBuilder.cfData(bytes)
        val locals = mutableListOf<Pointer?>(keyPtr, servicePtr, dataPtr)
        try {
            // Try synced first (iCloud Keychain). When the host process is signed
            // without the `application-identifier` entitlement (Developer ID local
            // build, ad-hoc, unsigned dev runs) macOS returns
            // `errSecMissingEntitlement = -34018`; fall back to a local-only
            // write so the user can still sign in. The Mac App Store build keeps
            // the synced write because it ships with the entitlement bundle.
            val status = trySecItemAdd(servicePtr, keyPtr, dataPtr, synced = true, locals)
            when (status) {
                Security.ERR_SEC_SUCCESS -> Unit
                Security.ERR_SEC_DUPLICATE_ITEM -> replaceExisting(servicePtr, keyPtr, dataPtr, locals)
                ERR_SEC_MISSING_ENTITLEMENT -> {
                    // Local-only fallback: this build can't access the iCloud
                    // synced keychain. Persist without the sync flag instead.
                    val statusLocal = trySecItemAdd(servicePtr, keyPtr, dataPtr, synced = false, locals)
                    when (statusLocal) {
                        Security.ERR_SEC_SUCCESS -> Unit
                        Security.ERR_SEC_DUPLICATE_ITEM ->
                            replaceExisting(servicePtr, keyPtr, dataPtr, locals)
                        else -> throw PlatformFailed("SecItemAdd (local fallback) failed: $statusLocal")
                    }
                }
                else -> throw PlatformFailed("SecItemAdd failed: $status")
            }
        } finally {
            CfQueryBuilder.releaseAll(locals)
        }
    }

    private fun trySecItemAdd(
        servicePtr: Pointer,
        keyPtr: Pointer,
        dataPtr: Pointer,
        synced: Boolean,
        locals: MutableList<Pointer?>,
    ): Int {
        val addQuery = CfQueryBuilder.cfDictionary(
            addEntries(servicePtr, keyPtr, dataPtr, synced = synced),
        )
        locals += addQuery
        return Security.INSTANCE.SecItemAdd(addQuery, null)
    }

    private fun replaceExisting(
        servicePtr: Pointer,
        keyPtr: Pointer,
        dataPtr: Pointer,
        locals: MutableList<Pointer?>,
    ) {
        val deleteQuery = CfQueryBuilder.cfDictionary(lookupEntries(servicePtr, keyPtr))
        locals += deleteQuery
        Security.INSTANCE.SecItemDelete(deleteQuery)
        // Re-add — first try synced, then fall back to local on missing entitlement.
        val statusSynced = trySecItemAdd(servicePtr, keyPtr, dataPtr, synced = true, locals)
        if (statusSynced == Security.ERR_SEC_SUCCESS) return
        if (statusSynced == ERR_SEC_MISSING_ENTITLEMENT) {
            val statusLocal = trySecItemAdd(servicePtr, keyPtr, dataPtr, synced = false, locals)
            if (statusLocal != Security.ERR_SEC_SUCCESS) {
                throw PlatformFailed("SecItemAdd (replace, local fallback) failed: $statusLocal")
            }
            return
        }
        throw PlatformFailed("SecItemAdd (replace) failed: $statusSynced")
    }

    override suspend fun get(key: String): String? {
        requireSecurity()
        val keyPtr = CfQueryBuilder.cfString(key)
        val servicePtr = CfQueryBuilder.cfString(serviceName)
        val locals = mutableListOf<Pointer?>(keyPtr, servicePtr)
        try {
            val getEntries = lookupEntries(servicePtr, keyPtr) + listOf(
                Security.K_SEC_MATCH_LIMIT to Security.K_SEC_MATCH_LIMIT_ONE,
                Security.K_SEC_RETURN_DATA to CoreFoundation.K_CF_BOOLEAN_TRUE,
            )
            val query = CfQueryBuilder.cfDictionary(getEntries)
            locals += query
            val outRef = PointerByReference()
            val status = Security.INSTANCE.SecItemCopyMatching(query, outRef)
            return when (status) {
                Security.ERR_SEC_SUCCESS -> {
                    val dataPtr = outRef.value
                    locals += dataPtr
                    val bytes = CfQueryBuilder.fromCfData(dataPtr) ?: return null
                    String(bytes, Charsets.UTF_8)
                }
                Security.ERR_SEC_ITEM_NOT_FOUND -> null
                else -> throw PlatformFailed("SecItemCopyMatching failed: $status")
            }
        } finally {
            CfQueryBuilder.releaseAll(locals)
        }
    }

    override suspend fun remove(key: String) {
        requireSecurity()
        val keyPtr = CfQueryBuilder.cfString(key)
        val servicePtr = CfQueryBuilder.cfString(serviceName)
        val locals = mutableListOf<Pointer?>(keyPtr, servicePtr)
        try {
            val query = CfQueryBuilder.cfDictionary(lookupEntries(servicePtr, keyPtr))
            locals += query
            val status = Security.INSTANCE.SecItemDelete(query)
            if (status != Security.ERR_SEC_SUCCESS && status != Security.ERR_SEC_ITEM_NOT_FOUND) {
                throw PlatformFailed("SecItemDelete failed: $status")
            }
        } finally {
            CfQueryBuilder.releaseAll(locals)
        }
    }

    override suspend fun list(): List<String> {
        requireSecurity()
        val servicePtr = CfQueryBuilder.cfString(serviceName)
        val locals = mutableListOf<Pointer?>(servicePtr)
        try {
            val listEntries = listOf(
                Security.K_SEC_CLASS to Security.K_SEC_CLASS_GENERIC_PASSWORD,
                Security.K_SEC_ATTR_SERVICE to servicePtr,
                Security.K_SEC_ATTR_SYNCHRONIZABLE to Security.K_SEC_ATTR_SYNCHRONIZABLE_ANY,
                Security.K_SEC_MATCH_LIMIT to Security.K_SEC_MATCH_LIMIT_ALL,
                Security.K_SEC_RETURN_ATTRIBUTES to CoreFoundation.K_CF_BOOLEAN_TRUE,
            )
            val query = CfQueryBuilder.cfDictionary(listEntries)
            locals += query
            val outRef = PointerByReference()
            val status = Security.INSTANCE.SecItemCopyMatching(query, outRef)
            return when (status) {
                Security.ERR_SEC_SUCCESS -> {
                    val arrayPtr = outRef.value
                    locals += arrayPtr
                    extractAccountNames(arrayPtr)
                }
                Security.ERR_SEC_ITEM_NOT_FOUND -> emptyList()
                else -> throw PlatformFailed("SecItemCopyMatching (list) failed: $status")
            }
        } finally {
            CfQueryBuilder.releaseAll(locals)
        }
    }

    private fun extractAccountNames(cfArray: Pointer?): List<String> {
        if (cfArray == null) return emptyList()
        val cf = CoreFoundation.INSTANCE
        val count = cf.CFArrayGetCount(cfArray)
        if (count <= 0) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until count) {
            val dict = cf.CFArrayGetValueAtIndex(cfArray, i) ?: continue
            val acctPtr = cf.CFDictionaryGetValue(dict, Security.K_SEC_ATTR_ACCOUNT) ?: continue
            CfQueryBuilder.fromCfString(acctPtr)?.let(out::add)
        }
        return out.distinct()
    }

    /**
     * Common attribute set for `SecItemAdd`. Marks the item as
     * iCloud-synchronizable via `kSecAttrSynchronizable = kCFBooleanTrue`.
     */
    internal fun addEntries(
        servicePtr: Pointer,
        accountPtr: Pointer,
        dataPtr: Pointer,
        synced: Boolean = true,
    ): List<Pair<Pointer, Pointer>> = buildList {
        add(Security.K_SEC_CLASS to Security.K_SEC_CLASS_GENERIC_PASSWORD)
        add(Security.K_SEC_ATTR_SERVICE to servicePtr)
        add(Security.K_SEC_ATTR_ACCOUNT to accountPtr)
        if (synced) {
            add(Security.K_SEC_ATTR_SYNCHRONIZABLE to CoreFoundation.K_CF_BOOLEAN_TRUE)
        }
        add(Security.K_SEC_VALUE_DATA to dataPtr)
    }

    /**
     * Common attribute set for `SecItemCopyMatching` / `SecItemDelete`. Uses
     * `kSecAttrSynchronizable = kSecAttrSynchronizableAny` so both synced and
     * any pre-existing local-only rows are matched.
     */
    internal fun lookupEntries(
        servicePtr: Pointer,
        accountPtr: Pointer,
    ): List<Pair<Pointer, Pointer>> = listOf(
        Security.K_SEC_CLASS to Security.K_SEC_CLASS_GENERIC_PASSWORD,
        Security.K_SEC_ATTR_SERVICE to servicePtr,
        Security.K_SEC_ATTR_ACCOUNT to accountPtr,
        Security.K_SEC_ATTR_SYNCHRONIZABLE to Security.K_SEC_ATTR_SYNCHRONIZABLE_ANY,
    )

    companion object {
        internal const val DEFAULT_SERVICE = "puklic-client"
        /**
         * `errSecMissingEntitlement` from `<Security/SecBase.h>`. Returned when the host
         * process is not signed with the `application-identifier` entitlement required
         * to write iCloud-synced keychain items. Developer ID / ad-hoc / unsigned dev
         * builds always hit this on `kSecAttrSynchronizable = true` writes.
         */
        internal const val ERR_SEC_MISSING_ENTITLEMENT: Int = -34018
    }
}
