package dev.puklic.repositories

import co.touchlab.kermit.Logger
import dev.puklic.domain.VoiceMember
import dev.puklic.ids.ChannelId
import dev.puklic.ids.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private const val VOICE_STATE_TAG = "VoiceStateRepository"

/**
 * In-memory store of voice-channel occupants per channel. Built from two gateway inputs:
 *  - [GatewayDomainEvent.VoiceStatesBootstrap] — initial snapshot delivered with each
 *    `GUILD_CREATE` (or embedded in `READY`).
 *  - [GatewayDomainEvent.VoiceStateUpdated] — incremental join/leave/move/mute dispatches.
 *
 * Voice presence is ephemeral by design (ADR-0003 cache strategy + voice architect report
 * 2026-05-23-voice §5): nothing is persisted to SQLite. The state lives for the duration of
 * the gateway session and is rebuilt on reconnect.
 */
public class VoiceStateRepository(
    sessionScope: CoroutineScope,
    gatewaySource: GatewayEventSource,
) {
    private val _statesByChannel = MutableStateFlow<Map<ChannelId, List<VoiceMember>>>(emptyMap())

    /** Map of voice-channel-id → ordered list of current occupants. Channels with zero
     *  occupants are absent from the map (never present with an empty list). */
    public val voiceStates: StateFlow<Map<ChannelId, List<VoiceMember>>> =
        _statesByChannel.asStateFlow()

    init {
        gatewaySource.events
            .onEach { event ->
                when (event) {
                    is GatewayDomainEvent.VoiceStateUpdated -> handleUpdate(event)
                    is GatewayDomainEvent.VoiceStatesBootstrap -> handleBootstrap(event)
                    else -> Unit
                }
            }
            .catch { t ->
                Logger.w(VOICE_STATE_TAG) {
                    "voice state repo: flow error caught cause=${t::class.simpleName} " +
                        "msg=${t.message?.take(200)}"
                }
            }
            .launchIn(sessionScope)
    }

    /** Synchronous lookup of the current occupant list for [channelId]. */
    public fun membersIn(channelId: ChannelId): List<VoiceMember> =
        _statesByChannel.value[channelId] ?: emptyList()

    private fun handleUpdate(event: GatewayDomainEvent.VoiceStateUpdated) {
        _statesByChannel.value = applyUpdate(_statesByChannel.value, event)
    }

    private fun handleBootstrap(event: GatewayDomainEvent.VoiceStatesBootstrap) {
        if (event.states.isEmpty()) return
        val next = _statesByChannel.value.toMutableMap()
        // Drop any stale entries for this guild before re-seeding — a fresh bootstrap supersedes
        // whatever we had (e.g. resume from a stale snapshot).
        next.keys.toList().forEach { cid ->
            val filtered = next.getValue(cid).filterNot { it.guildId == event.guildId }
            if (filtered.isEmpty()) next.remove(cid) else next[cid] = filtered
        }
        event.states.forEach { member ->
            val list = next[member.channelId] ?: emptyList()
            next[member.channelId] = list + member
        }
        _statesByChannel.value = next
    }

    private fun applyUpdate(
        current: Map<ChannelId, List<VoiceMember>>,
        event: GatewayDomainEvent.VoiceStateUpdated,
    ): Map<ChannelId, List<VoiceMember>> {
        val next = current.toMutableMap()
        // Remove this user from any channel they currently occupy (covers leave + channel-move).
        next.keys.toList().forEach { cid ->
            val without = next.getValue(cid).filterNot { it.userId == event.userId }
            if (without.isEmpty()) next.remove(cid) else next[cid] = without
        }
        // Re-add to the new channel (unless leaving / DM-call with no guild context).
        if (event.channelId != null && event.guildId != null) {
            val member = VoiceMember(
                userId = event.userId,
                channelId = event.channelId,
                guildId = event.guildId,
                selfMute = event.selfMute,
                selfDeaf = event.selfDeaf,
                muted = event.mute,
                deafened = event.deaf,
            )
            val list = next[event.channelId] ?: emptyList()
            next[event.channelId] = list + member
        }
        return next
    }

    /** Helper for UI/debug code: total occupant count across all voice channels. */
    @Suppress("unused")
    public fun totalOccupants(): Int = _statesByChannel.value.values.sumOf { it.size }

    /** Helper to locate which voice channel (if any) a given user is currently in. */
    @Suppress("unused")
    public fun channelOf(userId: UserId): ChannelId? =
        _statesByChannel.value.entries.firstOrNull { (_, list) -> list.any { it.userId == userId } }?.key
}
