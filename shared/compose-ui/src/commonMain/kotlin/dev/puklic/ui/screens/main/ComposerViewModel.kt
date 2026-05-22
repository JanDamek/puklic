package dev.puklic.ui.screens.main

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope as lifecycleCoroutineScope
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import dev.puklic.repositories.MessageOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Composer state per channel. Phase 1 omits draft persistence (deferred to step 15-16). */
public data class ComposerState(
    val draft: String = "",
    val isSending: Boolean = false,
    val error: String? = null,
)

public class ComposerViewModel(
    componentContext: ComponentContext,
    private val orchestrator: MessageOrchestrator,
    public val channelId: ChannelId,
    externalScope: CoroutineScope? = null,
) : ComponentContext by componentContext {

    public val scope: CoroutineScope = externalScope ?: lifecycleCoroutineScope(Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ComposerState())
    public val state: StateFlow<ComposerState> = _state.asStateFlow()

    public fun onDraftChange(value: String) {
        _state.value = _state.value.copy(draft = value, error = null)
    }

    public fun submit() {
        val current = _state.value
        if (current.draft.isBlank() || current.isSending) return
        _state.value = current.copy(isSending = true)
        scope.launch {
            val result = orchestrator.send(channelId, current.draft)
            _state.value = if (result.isFailure) {
                current.copy(isSending = false, error = result.exceptionOrNull()?.message)
            } else {
                ComposerState()
            }
        }
    }

    public fun edit(messageId: MessageId, newContent: String) {
        scope.launch { orchestrator.edit(messageId, channelId, newContent) }
    }

    public fun delete(messageId: MessageId) {
        scope.launch { orchestrator.delete(messageId, channelId) }
    }
}
