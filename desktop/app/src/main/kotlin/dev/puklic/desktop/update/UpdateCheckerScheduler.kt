package dev.puklic.desktop.update

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private const val LOG_TAG = "UpdateCheckerScheduler"

/**
 * Schedules periodic [UpdateChecker.check] calls on the supplied [scope]. The first check is
 * deferred by [initialDelay] to avoid competing with startup work; subsequent checks fire every
 * [interval]. Checks are skipped while [enabled] returns false, but the loop keeps running so a
 * later toggle in user preferences picks up without restart.
 *
 * Results are exposed via [update] and additionally forwarded to the optional [onUpdate]
 * callback. Call [dismiss] to clear the banner state for the current process lifetime.
 */
public class UpdateCheckerScheduler(
    private val checker: UpdateChecker,
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val initialDelay: Duration = INITIAL_DELAY,
    private val interval: Duration = CHECK_INTERVAL,
    private val onUpdate: ((UpdateChecker.UpdateInfo) -> Unit)? = null,
) {

    private val _update = MutableStateFlow<UpdateChecker.UpdateInfo?>(null)
    public val update: StateFlow<UpdateChecker.UpdateInfo?> = _update.asStateFlow()

    public fun start() {
        scope.launch {
            delay(initialDelay)
            while (isActive) {
                runOnce()
                delay(interval)
            }
        }
    }

    /** Manually trigger a check (e.g. Settings -> Check now). */
    public suspend fun checkNow(): UpdateChecker.UpdateInfo? = runOnce()

    public fun dismiss() {
        _update.value = null
    }

    private suspend fun runOnce(): UpdateChecker.UpdateInfo? {
        if (!enabled()) return null
        val info = checker.check() ?: return null
        Logger.i(LOG_TAG) { "Update available: ${info.version}" }
        _update.value = info
        onUpdate?.invoke(info)
        return info
    }

    private companion object {
        val INITIAL_DELAY = 5.minutes
        val CHECK_INTERVAL = 6.hours
    }
}
