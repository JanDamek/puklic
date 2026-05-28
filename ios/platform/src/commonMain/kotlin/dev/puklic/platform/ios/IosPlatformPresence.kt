package dev.puklic.platform.ios

import dev.puklic.platform.PlatformPresence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS has no public API for third-party apps to read the system idle / Do Not
 * Disturb state. The Focus Status API is opt-in per app and published only when
 * the user explicitly opts into sharing focus to a particular app — there is no
 * generic "is the user away" or "is DND on" query.
 *
 * Both flows therefore stay at `false`. This is a permanent OS limitation, not
 * a placeholder for a future implementation.
 */
class IosPlatformPresence : PlatformPresence {
    private val away = MutableStateFlow(false)
    private val dnd = MutableStateFlow(false)
    override val systemAway: StateFlow<Boolean> = away.asStateFlow()
    override val dndActive: StateFlow<Boolean> = dnd.asStateFlow()
}
