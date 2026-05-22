package dev.puklic.ui.screens.main

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope as lifecycleCoroutineScope
import dev.puklic.domain.ChatMessage
import dev.puklic.ids.ChannelId
import dev.puklic.repositories.MessageOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State sealed family per `docs/04_ui/component-library.md` §MessageList. */
public sealed interface MessageListState {
    public data object Loading : MessageListState
    public data class Loaded(
        val messages: List<ChatMessage>,
        val isLoadingOlder: Boolean = false,
        val hasMoreOlder: Boolean = true,
        val loadOlderError: String? = null,
    ) : MessageListState
    public data object Empty : MessageListState
    public data class Error(val message: String) : MessageListState
}

/**
 * Per-channel ViewModel. Observes [MessageOrchestrator.observeCommitted] and exposes the
 * result as a [MessageListState]. Scroll-back via [loadOlder] dispatches REST page fetches.
 */
public class MessageListViewModel(
    componentContext: ComponentContext,
    private val orchestrator: MessageOrchestrator,
    public val channelId: ChannelId,
    externalScope: CoroutineScope? = null,
) : ComponentContext by componentContext {

    public val scope: CoroutineScope = externalScope ?: lifecycleCoroutineScope(Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<MessageListState>(MessageListState.Loading)
    public val state: StateFlow<MessageListState> = _state.asStateFlow()

    private var observerJob: Job? = null
    private var initialFetchTriggered: Boolean = false

    init {
        orchestrator.setActiveChannel(channelId)
        startObserving()
    }

    private fun startObserving() {
        observerJob?.cancel()
        observerJob = scope.launch {
            orchestrator.observeCommitted(channelId).collect { messages ->
                if (messages.isEmpty()) {
                    if (!initialFetchTriggered) {
                        initialFetchTriggered = true
                        triggerInitialFetch()
                    } else {
                        _state.value = MessageListState.Empty
                    }
                } else {
                    initialFetchTriggered = true
                    _state.value = MessageListState.Loaded(messages = messages)
                }
            }
        }
    }

    private fun triggerInitialFetch() {
        _state.value = MessageListState.Loading
        scope.launch {
            val result = orchestrator.loadInitial(channelId)
            // On success, the storage Flow will re-emit and flip state to Loaded / Empty.
            // On failure, surface an error if no messages have arrived yet.
            if (result.isFailure && _state.value is MessageListState.Loading) {
                _state.value = MessageListState.Error(
                    result.exceptionOrNull()?.message ?: "Failed to load messages.",
                )
            } else if (result.isSuccess && _state.value is MessageListState.Loading) {
                // Safety net: if storage Flow hasn't re-emitted yet (or returns 0 messages), make
                // sure we leave the Loading skeleton. A subsequent storage emit with messages will
                // promote us to Loaded.
                _state.value = MessageListState.Empty
            }
        }
    }

    public fun sendMessage(content: String) {
        if (content.isBlank()) return
        scope.launch { orchestrator.send(channelId, content) }
    }

    public fun loadOlder() {
        val loaded = _state.value as? MessageListState.Loaded ?: return
        val oldest = loaded.messages.firstOrNull() ?: return
        scope.launch {
            _state.value = loaded.copy(isLoadingOlder = true, loadOlderError = null)
            val result = orchestrator.loadOlder(channelId, oldest.id)
            val current = _state.value as? MessageListState.Loaded ?: return@launch
            _state.value = current.copy(
                isLoadingOlder = false,
                hasMoreOlder = result.getOrNull()?.let { it > 0 } ?: current.hasMoreOlder,
                loadOlderError = result.exceptionOrNull()?.message,
            )
        }
    }
}
