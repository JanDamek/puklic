package dev.puklic.platform

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SecureStorageTest {

    @Test
    fun `empty store returns empty list and null for missing key`() = runTest {
        val storage = FakeSecureStorage()
        storage.list() shouldBe emptyList()
        storage.get("missing").shouldBeNull()
    }

    @Test
    fun `put then get returns stored value`() = runTest {
        val storage = FakeSecureStorage()
        storage.put("token", "secret-value")
        storage.get("token") shouldBe "secret-value"
    }

    @Test
    fun `list returns keys only not values`() = runTest {
        val storage = FakeSecureStorage()
        storage.put("a", "value-a")
        storage.put("b", "value-b")
        storage.list() shouldContainExactlyInAnyOrder listOf("a", "b")
    }

    @Test
    fun `remove deletes the entry`() = runTest {
        val storage = FakeSecureStorage()
        storage.put("k", "v")
        storage.remove("k")
        storage.get("k").shouldBeNull()
        storage.list() shouldBe emptyList()
    }

    @Test
    fun `put overwrites existing value`() = runTest {
        val storage = FakeSecureStorage()
        storage.put("k", "v1")
        storage.put("k", "v2")
        storage.get("k") shouldBe "v2"
        storage.list() shouldBe listOf("k")
    }
}
