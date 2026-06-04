package dev.puklic.ui.screens.main

import co.touchlab.kermit.Logger
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope as lifecycleCoroutineScope
import dev.puklic.domain.ChatMessage
import dev.puklic.domain.EmojiRef
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import dev.puklic.repositories.MessageOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** State sealed family per `docs/04_ui/component-library.md` §MessageList. */
public sealed interface MessageListState {
    public data object Loading : MessageListState
    public data class Loaded(
        val messages: List<ChatMessage>,
        val isLoadingOlder: Boolean = false,
        val hasMoreOlder: Boolean = true,
        val loadOlderError: String? = null,
        /**
         * Anchor for the NOVÉ divider: the first unread message (oldest-first order) snapshotted
         * once when the channel first opens, so acking while the view is open does not erase it.
         */
        val firstUnreadId: MessageId? = null,
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
    private val lastReadMessageId: MessageId? = null,
) : ComponentContext by componentContext {

    public val scope: CoroutineScope = externalScope ?: lifecycleCoroutineScope(Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<MessageListState>(MessageListState.Loading)
    public val state: StateFlow<MessageListState> = _state.asStateFlow()

    private var observerJob: Job? = null
    private var initialFetchTriggered: Boolean = false

    // Computed once, on the first non-empty load for this channel, then held stable so subsequent
    // emissions (e.g. after a MESSAGE_ACK) do not move or clear the NOVÉ divider mid-view.
    private var firstUnreadSnapshot: MessageId? = null
    private var firstUnreadComputed: Boolean = false

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
                    if (!firstUnreadComputed) {
                        firstUnreadComputed = true
                        firstUnreadSnapshot = firstUnreadMessageId(messages.map { it.id }, lastReadMessageId)
                    }
                    _state.value = MessageListState.Loaded(
                        messages = messages,
                        firstUnreadId = firstUnreadSnapshot,
                    )
                }
            }
        }
    }

    private fun triggerInitialFetch() {
        _state.value = MessageListState.Loading
        scope.launch {
            // Bounded loadInitial: even if REST hangs, the skeleton must not stick forever.
            val outcome = withTimeoutOrNull(LOAD_INITIAL_TIMEOUT_MS) {
                var attempt = orchestrator.loadInitial(channelId)
                // Retry once on 50001 (Missing Access). After OP14 lazy_request, Discord may still
                // be processing the subscription — a short wait + retry frequently succeeds.
                if (attempt.isFailure && attempt.exceptionOrNull()?.message?.contains("50001") == true) {
                    Logger.i("MessageListViewModel") {
                        "loadInitial got 50001, retrying after ${RETRY_BACKOFF_MS}ms"
                    }
                    delay(RETRY_BACKOFF_MS)
                    attempt = orchestrator.loadInitial(channelId)
                    Logger.i("MessageListViewModel") {
                        "loadInitial retry result: success=${attempt.isSuccess}"
                    }
                }
                attempt
            }
            when {
                outcome == null && _state.value is MessageListState.Loading -> {
                    _state.value = MessageListState.Error("Timed out loading messages.")
                }
                outcome != null && outcome.isFailure && _state.value is MessageListState.Loading -> {
                    _state.value = MessageListState.Error(
                        outcome.exceptionOrNull()?.message ?: "Failed to load messages.",
                    )
                }
                outcome != null && outcome.isSuccess && _state.value is MessageListState.Loading -> {
                    // Safety net: if storage Flow hasn't re-emitted yet (or returns 0 messages),
                    // leave the Loading skeleton. A subsequent storage emit with messages will
                    // promote us to Loaded.
                    _state.value = MessageListState.Empty
                }
            }
        }
    }

    private companion object {
        const val LOAD_INITIAL_TIMEOUT_MS = 10_000L
        const val RETRY_BACKOFF_MS = 1_000L
    }

    public fun toggleReaction(messageId: MessageId, emoji: EmojiRef, alreadyReacted: Boolean) {
        scope.launch {
            orchestrator.toggleReaction(channelId, messageId, emoji, alreadyReacted)
        }
    }

    /** Delete a message via the orchestrator (issues REST DELETE + removes the local row). */
    public fun deleteMessage(messageId: MessageId) {
        scope.launch {
            orchestrator.delete(messageId, channelId)
                .onFailure { Logger.w("MessageListViewModel", it) { "delete failed mid=$messageId" } }
        }
    }

    /** Edit a message via the orchestrator (issues REST PATCH with new content). */
    public fun editMessage(messageId: MessageId, newContent: String) {
        scope.launch {
            orchestrator.edit(messageId, channelId, newContent)
                .onFailure { Logger.w("MessageListViewModel", it) { "edit failed mid=$messageId" } }
        }
    }

    public fun loadOlder() {
        val loaded = _state.value as? MessageListState.Loaded ?: return
        if (loaded.isLoadingOlder || !loaded.hasMoreOlder) return
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
