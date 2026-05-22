package dev.puklic.platform

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Compile-level verification that fake implementations satisfy their interfaces.
 * If an interface signature drifts, this file will fail to compile.
 */
class FakeFixtureTest {

    @Test
    fun `fakes satisfy their interfaces`() = runTest {
        val storage: SecureStorage = FakeSecureStorage()
        val notifications: NotificationService = FakeNotificationService()
        val paths: PlatformPaths = FakePlatformPaths()
        val clipboard: PlatformClipboard = FakeClipboard()

        storage.list() shouldBe emptyList()
        notifications.supported shouldBe NotificationCapabilities(
            actions = true,
            images = true,
            markup = false,
        )
        paths.dataDir shouldBe "/tmp/puklic/data"
        clipboard.setText("hi")
        clipboard.getText() shouldBe "hi"
    }
}
