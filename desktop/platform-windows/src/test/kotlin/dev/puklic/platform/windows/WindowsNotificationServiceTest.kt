package dev.puklic.platform.windows

import dev.puklic.platform.Notification
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class WindowsNotificationServiceTest {

    @Test
    fun `show returns a handle and does not throw when tray is unavailable`() = runTest {
        val service = WindowsNotificationService(environment = HeadlessEnvironment)
        val handle = service.show(
            Notification(
                title = "Hello",
                body = "World",
                iconPath = null,
                actions = emptyList(),
                tag = "tag-1",
                urgent = false,
            ),
        )
        handle.id shouldBe "tag-1"
    }

    @Test
    fun `show falls back to title when tag is null`() = runTest {
        val service = WindowsNotificationService(environment = HeadlessEnvironment)
        val handle = service.show(
            Notification(
                title = "OnlyTitle",
                body = "Body",
                iconPath = null,
                actions = emptyList(),
                tag = null,
                urgent = true,
            ),
        )
        handle.id shouldBe "OnlyTitle"
    }

    @Test
    fun `cancel is a no-op and does not throw`() = runTest {
        val service = WindowsNotificationService(environment = HeadlessEnvironment)
        val handle = service.show(
            Notification(
                title = "T",
                body = "B",
                iconPath = null,
                actions = emptyList(),
                tag = null,
                urgent = false,
            ),
        )
        handle shouldNotBe null
        service.cancel(handle)
    }

    private object HeadlessEnvironment : WindowsNotificationService.Environment {
        override fun trayAvailable(): Boolean = false
    }
}
