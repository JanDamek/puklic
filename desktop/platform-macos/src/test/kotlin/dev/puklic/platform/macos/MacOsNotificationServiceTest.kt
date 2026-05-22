package dev.puklic.platform.macos

import dev.puklic.platform.Notification
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

class MacOsNotificationServiceTest {

    private fun notif(title: String = "Hello", body: String = "World") =
        Notification(
            title = title,
            body = body,
            iconPath = null,
            actions = emptyList(),
            tag = null,
            urgent = false,
        )

    @Test
    fun `buildScript produces well-formed osascript`() {
        val script = MacOsNotificationService.buildScript(notif())
        script shouldBe """display notification "World" with title "Hello" subtitle "Puklic""""
    }

    @Test
    fun `buildScript escapes embedded quotes and backslashes`() {
        val script = MacOsNotificationService.buildScript(notif(title = "T\"itle", body = "B\\ack"))
        script shouldContain "\\\"itle"
        script shouldContain "B\\\\ack"
    }

    @Test
    fun `capabilities advertise no actions or images in Phase 1`() {
        val svc = MacOsNotificationService()
        svc.supported.actions shouldBe false
        svc.supported.images shouldBe false
        svc.supported.markup shouldBe false
    }
}
