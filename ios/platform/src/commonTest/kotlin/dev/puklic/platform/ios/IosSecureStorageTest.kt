package dev.puklic.platform.ios

import kotlinx.coroutines.runBlocking
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Keychain round-trip test. Uses a unique random service per test instance so
 * runs are isolated from each other and from any leftover simulator state.
 */
class IosSecureStorageTest {

    private val service = "puklic-test-" + NSUUID.UUID().UUIDString
    private val store = IosSecureStorage(serviceName = service)

    @AfterTest
    fun cleanup() {
        runBlocking {
            runCatching { store.list().forEach { store.remove(it) } }
        }
    }

    @Test
    fun putThenGetReturnsStoredValue() = runBlocking {
        store.put("token", "abc-123")
        assertEquals("abc-123", store.get("token"))
    }

    @Test
    fun getOnUnknownKeyReturnsNull() = runBlocking {
        assertNull(store.get("nope"))
    }

    @Test
    fun putReplacesExistingValue() = runBlocking {
        store.put("token", "v1")
        store.put("token", "v2")
        assertEquals("v2", store.get("token"))
    }

    @Test
    fun removeDeletesValue() = runBlocking {
        store.put("token", "abc")
        store.remove("token")
        assertNull(store.get("token"))
    }

    @Test
    fun listEnumeratesKeys() = runBlocking {
        store.put("alpha", "1")
        store.put("beta", "2")
        val keys = store.list().toSet()
        assertTrue("alpha" in keys, "expected alpha in $keys")
        assertTrue("beta" in keys, "expected beta in $keys")
    }
}
