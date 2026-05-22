package dev.puklic.platform.linux

import dev.puklic.platform.Notification
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LinuxNotificationServiceTest {

    private fun notif(title: String = "T", body: String = "B", urgent: Boolean = false, icon: String? = null) =
        Notification(
            title = title,
            body = body,
            iconPath = icon,
            actions = emptyList(),
            tag = null,
            urgent = urgent,
        )

    @Test
    fun `builds notify-send args with app name and urgency`() {
        val args = LinuxNotificationService.buildArgs(notif())
        args.first() shouldBe "notify-send"
        args shouldContain "--app-name=Puklic"
        args shouldContain "--urgency=normal"
        args shouldContainInOrder listOf("T", "B")
    }

    @Test
    fun `urgent notifications use critical urgency`() {
        val args = LinuxNotificationService.buildArgs(notif(urgent = true))
        args shouldContain "--urgency=critical"
    }

    @Test
    fun `icon path is forwarded when provided`() {
        val args = LinuxNotificationService.buildArgs(notif(icon = "/tmp/icon.png"))
        args shouldContain "--icon=/tmp/icon.png"
    }

    @Test
    fun `capabilities reflect Phase 1 limitations`() {
        val svc = LinuxNotificationService()
        svc.supported.actions shouldBe false
        svc.supported.images shouldBe false
        svc.supported.markup shouldBe true
    }
}
