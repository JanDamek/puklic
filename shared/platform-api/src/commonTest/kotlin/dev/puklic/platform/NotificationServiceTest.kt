package dev.puklic.platform

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class NotificationServiceTest {

    private fun notification(title: String = "Hello"): Notification = Notification(
        title = title,
        body = "body",
        iconPath = null,
        actions = emptyList(),
        tag = null,
        urgent = false,
    )

    @Test
    fun `show records the notification and returns handle`() = runTest {
        val svc = FakeNotificationService()
        val handle = svc.show(notification("first"))
        svc.shown shouldHaveSize 1
        svc.shown.first().first shouldBe handle
        svc.shown.first().second.title shouldBe "first"
    }

    @Test
    fun `cancel records the cancelled handle`() = runTest {
        val svc = FakeNotificationService()
        val handle = svc.show(notification())
        svc.cancel(handle)
        svc.cancelled shouldBe listOf(handle)
    }

    @Test
    fun `capabilities exposed via supported`() {
        val caps = NotificationCapabilities(actions = true, images = false, markup = true)
        val svc = FakeNotificationService(supported = caps)
        svc.supported shouldBe caps
    }

    @Test
    fun `notification action data class holds id and label`() {
        val action = NotificationAction(id = "reply", label = "Reply")
        action.id shouldBe "reply"
        action.label shouldBe "Reply"
    }
}
