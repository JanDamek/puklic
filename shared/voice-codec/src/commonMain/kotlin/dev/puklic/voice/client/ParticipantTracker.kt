package dev.puklic.voice.client

import dev.puklic.ids.UserId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Maps voice SSRCs to Discord users from Speaking / ClientDisconnect gateway events. */
internal class ParticipantTracker {

    private val usersBySsrc = MutableStateFlow<Map<Int, UserId>>(emptyMap())
    private val _participants = MutableStateFlow<Set<UserId>>(emptySet())
    val participants: StateFlow<Set<UserId>> = _participants.asStateFlow()

    fun userFor(ssrc: Int): UserId? = usersBySsrc.value[ssrc]

    fun usersFor(ssrcs: Set<Int>): Set<UserId> = ssrcs.mapNotNull(::userFor).toSet()

    fun onSpeaking(userId: String, ssrc: Int) {
        val user = userId.toLongOrNull()?.let(::UserId) ?: return
        publish { it + (ssrc to user) }
    }

    fun onDisconnect(userId: String) {
        val user = userId.toLongOrNull()?.let(::UserId) ?: return
        publish { known -> known.filterValues { it != user } }
    }

    fun clear() = publish { emptyMap() }

    private fun publish(transform: (Map<Int, UserId>) -> Map<Int, UserId>) {
        usersBySsrc.update(transform)
        _participants.value = usersBySsrc.value.values.toSet()
    }
}
