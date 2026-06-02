package dev.puklic.platform.macos

import com.sun.jna.Pointer
import dev.puklic.platform.macos.bridge.CoreFoundation
import dev.puklic.platform.macos.bridge.Security
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Issue #74 — iCloud Keychain sync.
 *
 * Asserts that the attribute lists built by [MacOsSecureStorage] always
 * include `kSecAttrSynchronizable`:
 *  - the SecItemAdd entry list MUST map `kSecAttrSynchronizable` to
 *    `kCFBooleanTrue` so the item is replicated to iCloud Keychain;
 *  - the SecItemCopyMatching / SecItemDelete entry list MUST map
 *    `kSecAttrSynchronizable` to `kSecAttrSynchronizableAny` so both synced
 *    and any pre-existing local-only rows are matched.
 *
 * These tests run on JVM only and require the host to be macOS (the JNA
 * globals dereference Security.framework symbols at first access). They
 * skip cleanly on non-macOS hosts (CI Linux runners).
 */
class MacOsSecureStorageSyncFlagTest {

    private fun isMacOs(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("mac")

    private val store = MacOsSecureStorage(serviceName = "puklic-sync-test")

    @Test
    fun `addEntries maps kSecAttrSynchronizable to kCFBooleanTrue`() {
        if (!isMacOs()) return
        val dummyService = Pointer(1L)
        val dummyAccount = Pointer(2L)
        val dummyData = Pointer(3L)
        val entries = store.addEntries(dummyService, dummyAccount, dummyData)
        val syncPair = entries.firstOrNull { it.first == Security.K_SEC_ATTR_SYNCHRONIZABLE }
            ?: error("addEntries missing kSecAttrSynchronizable; entries=${entries.size}")
        syncPair.second shouldBe CoreFoundation.K_CF_BOOLEAN_TRUE
    }

    @Test
    fun `lookupEntries maps kSecAttrSynchronizable to kSecAttrSynchronizableAny`() {
        if (!isMacOs()) return
        val dummyService = Pointer(1L)
        val dummyAccount = Pointer(2L)
        val entries = store.lookupEntries(dummyService, dummyAccount)
        val syncPair = entries.firstOrNull { it.first == Security.K_SEC_ATTR_SYNCHRONIZABLE }
            ?: error("lookupEntries missing kSecAttrSynchronizable; entries=${entries.size}")
        syncPair.second shouldBe Security.K_SEC_ATTR_SYNCHRONIZABLE_ANY
    }
}
