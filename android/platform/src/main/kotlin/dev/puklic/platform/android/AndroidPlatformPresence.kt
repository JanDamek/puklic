package dev.puklic.platform.android

import dev.puklic.platform.PlatformPresence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 1 stub. Phase 2 will observe PowerManager.isInteractive + NotificationManager.currentInterruptionFilter.
 * Flows are wired but never transition off the initial `false` state.
 */
class AndroidPlatformPresence : PlatformPresence {
    private val away = MutableStateFlow(false)
    private val dnd = MutableStateFlow(false)
    override val systemAway: StateFlow<Boolean> = away.asStateFlow()
    override val dndActive: StateFlow<Boolean> = dnd.asStateFlow()
}
