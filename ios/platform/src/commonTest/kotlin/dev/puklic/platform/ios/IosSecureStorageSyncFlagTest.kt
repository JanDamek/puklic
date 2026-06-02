package dev.puklic.platform.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecAttrSynchronizableAny
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #74 — iCloud Keychain sync.
 *
 * Verifies that every keychain query produced by [IosSecureStorage] carries
 * `kSecAttrSynchronizable`:
 *  - on `SecItemAdd` (write path) it MUST be `true` (kCFBooleanTrue) so the
 *    item is pushed to iCloud Keychain;
 *  - on `SecItemCopyMatching` / `SecItemDelete` (read/delete paths) it MUST
 *    be `kSecAttrSynchronizableAny` so both synced and any legacy non-synced
 *    rows are matched.
 *
 * Without these flags, items are local-only and the Discord token never flows
 * between Mac App Store Puklic and the iOS app.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosSecureStorageSyncFlagTest {

    private val store = IosSecureStorage(serviceName = "puklic-sync-test")

    @Test
    fun addAttrsIncludesSynchronizableTrue() {
        val data: NSData = (("v" as NSString).dataUsingEncoding(NSUTF8StringEncoding))!!
        val attrs = store.addAttrs("token", data)

        val syncEntry = attrs.entries.firstOrNull { it.key === kSecAttrSynchronizable }
        assertNotNull(syncEntry, "addAttrs must include kSecAttrSynchronizable; got keys=${attrs.keys}")
        assertEquals(true, syncEntry.value, "kSecAttrSynchronizable must be true for iCloud sync")
    }

    @Test
    fun lookupAttrsUsesSynchronizableAny() {
        val attrs = store.lookupAttrs("token")
        val syncEntry = attrs.entries.firstOrNull { it.key === kSecAttrSynchronizable }
        assertNotNull(syncEntry, "lookupAttrs must include kSecAttrSynchronizable")
        // kSecAttrSynchronizableAny is a CFTypeRef sentinel; identity comparison.
        assertTrue(
            syncEntry.value === kSecAttrSynchronizableAny,
            "lookup queries must use kSecAttrSynchronizableAny to match synced + legacy rows",
        )
    }
}
