package dev.puklic.ui.components

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** Issue #23 — Send-button gating: blank draft + attachments OK; while uploading, disabled. */
class ComposerCanSendTest {

    @Test
    fun blank_draft_no_attachments_cannot_send() {
        composerCanSend(draftBlank = true, attachmentCount = 0, anyUploading = false, enabled = true) shouldBe false
    }

    @Test
    fun blank_draft_with_attachment_can_send() {
        composerCanSend(draftBlank = true, attachmentCount = 1, anyUploading = false, enabled = true) shouldBe true
    }

    @Test
    fun non_blank_draft_no_attachments_can_send() {
        composerCanSend(draftBlank = false, attachmentCount = 0, anyUploading = false, enabled = true) shouldBe true
    }

    @Test
    fun any_uploading_disables_send() {
        composerCanSend(draftBlank = false, attachmentCount = 1, anyUploading = true, enabled = true) shouldBe false
    }

    @Test
    fun disabled_prevents_send_even_with_content() {
        composerCanSend(draftBlank = false, attachmentCount = 0, anyUploading = false, enabled = false) shouldBe false
    }
}
