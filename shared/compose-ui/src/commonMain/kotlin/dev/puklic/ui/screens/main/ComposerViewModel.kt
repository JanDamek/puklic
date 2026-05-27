package dev.puklic.ui.screens.main

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope as lifecycleCoroutineScope
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import dev.puklic.platform.FilePicker
import dev.puklic.platform.PickedFile
import dev.puklic.repositories.AttachmentLimits
import dev.puklic.repositories.AttachmentTooLargeError
import dev.puklic.repositories.MessageOrchestrator
import dev.puklic.repositories.PendingAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Status of one pending attachment slot. Currently we don't distinguish "uploading" from "queued"
 * in the wire protocol — the orchestrator runs the upload synchronously inside [send]. The state
 * exists so the UI can disable the Send button while a send-in-progress holds the slot.
 */
public enum class PendingAttachmentStatus { READY, UPLOADING, FAILED }

public data class PendingAttachmentItem(
    val attachment: PendingAttachment,
    val status: PendingAttachmentStatus = PendingAttachmentStatus.READY,
    val errorMessage: String? = null,
)

public data class ComposerState(
    val draft: String = "",
    val isSending: Boolean = false,
    val error: String? = null,
    val attachments: List<PendingAttachmentItem> = emptyList(),
)

/**
 * Per-channel composer view-model. Owns the draft, the list of in-memory pending attachments
 * (issue #23), and the send pipeline. The view-model never persists attachments — per architect
 * decision Q1, a crash mid-upload simply means the user re-attaches.
 *
 * [guildPremiumTierProvider] resolves the per-guild boost tier so [AttachmentLimits] can return
 * the right max bytes. Returns `null` for DMs / unknown guilds and the function falls back to the
 * default 25 MiB limit, which is the safest behaviour Discord won't reject.
 */
public class ComposerViewModel(
    componentContext: ComponentContext,
    private val orchestrator: MessageOrchestrator,
    public val channelId: ChannelId,
    externalScope: CoroutineScope? = null,
    private val guildPremiumTierProvider: suspend (ChannelId) -> Int? = { null },
    private val filePicker: FilePicker? = null,
) : ComponentContext by componentContext {

    public val scope: CoroutineScope = externalScope ?: lifecycleCoroutineScope(Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ComposerState())
    public val state: StateFlow<ComposerState> = _state.asStateFlow()

    /**
     * The in-flight send coroutine (which holds the Ktor upload calls). Cancelled by
     * [removeAttachment] so the user removing a chip during an upload aborts the whole send —
     * mirrors the official client's behaviour and is what architect decision Q3 prescribes.
     */
    private var currentSendJob: Job? = null

    public fun onDraftChange(value: String) {
        _state.value = _state.value.copy(draft = value, error = null)
    }

    /** Trigger the platform file-picker and add each selection. No-op if no picker is wired. */
    public fun openFilePicker() {
        val picker = filePicker ?: return
        scope.launch {
            picker.pick(allowMultiple = true).forEach { addAttachment(it) }
        }
    }

    /** Add a picked file to the pending list. Caller controls picking; this VM only manages state. */
    public fun addAttachment(file: PickedFile) {
        val id = nextAttachmentId()
        val pending = PendingAttachment(
            id = id,
            filename = file.filename,
            bytes = file.bytes,
            contentType = file.contentType,
        )
        _state.value = _state.value.copy(
            attachments = _state.value.attachments + PendingAttachmentItem(pending),
            error = null,
        )
    }

    /** Cancel any in-flight send and drop the chip from the pending list. */
    public fun removeAttachment(attachmentId: String) {
        val wasSending = _state.value.isSending
        if (wasSending) {
            currentSendJob?.cancel()
            currentSendJob = null
        }
        _state.value = _state.value.copy(
            attachments = _state.value.attachments.filterNot { it.attachment.id == attachmentId },
            isSending = if (wasSending) false else _state.value.isSending,
        )
    }

    /**
     * Submit: validates size limits, then runs the upload+send pipeline. Empty text plus at least
     * one attachment is a valid send.
     */
    /** Spec-name alias for [submit] per issue #23 architect-comment Q-bonus. */
    public fun send(): Unit = submit()

    public fun submit() {
        val current = _state.value
        val hasContent = current.draft.isNotBlank() || current.attachments.isNotEmpty()
        if (!hasContent || current.isSending) return
        currentSendJob = scope.launch { runSubmit(current) }
    }

    private suspend fun runSubmit(current: ComposerState) {
        val limit = AttachmentLimits.maxBytesFor(guildPremiumTierProvider(channelId))
        val tooLarge = current.attachments.firstOrNull { it.attachment.sizeBytes > limit }
        if (tooLarge != null) {
            val err = AttachmentTooLargeError(
                filename = tooLarge.attachment.filename,
                sizeBytes = tooLarge.attachment.sizeBytes,
                limitBytes = limit,
            )
            _state.value = current.copy(error = err.message)
            return
        }
        _state.value = current.copy(
            isSending = true,
            attachments = current.attachments.map {
                it.copy(status = PendingAttachmentStatus.UPLOADING)
            },
        )
        val result = if (current.attachments.isEmpty()) {
            orchestrator.send(channelId, current.draft.trim()).map { }
        } else {
            orchestrator.sendWithAttachments(
                channelId = channelId,
                content = current.draft.trim(),
                attachments = current.attachments.map { it.attachment },
            )
        }
        _state.value = if (result.isFailure) {
            current.copy(
                isSending = false,
                error = result.exceptionOrNull()?.message,
                attachments = current.attachments.map {
                    it.copy(status = PendingAttachmentStatus.FAILED)
                },
            )
        } else {
            ComposerState()
        }
    }

    public fun edit(messageId: MessageId, newContent: String) {
        scope.launch { orchestrator.edit(messageId, channelId, newContent) }
    }

    public fun delete(messageId: MessageId) {
        scope.launch { orchestrator.delete(messageId, channelId) }
    }

    private fun nextAttachmentId(): String = "att-${attachmentIdCounter++}"

    private var attachmentIdCounter: Int = 0
}
