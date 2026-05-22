package dev.puklic.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * OS-reported user presence: feeds Discord "idle" status and DND-aware notification gating.
 */
interface PlatformPresence {
    val systemAway: StateFlow<Boolean>
    val dndActive: StateFlow<Boolean>
}
