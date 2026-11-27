package dev.puklic.android

import android.content.Context
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import dev.puklic.persistence.repository.UserPreferencesRepository
import dev.puklic.platform.BackgroundConnection
import dev.puklic.platform.PlatformDenied
import dev.puklic.platform.android.RuntimePermissions
import dev.puklic.platform.android.connection.BackgroundConnectionHost
import dev.puklic.platform.android.connection.BackgroundConnectionService
import dev.puklic.platform.android.connection.BackgroundConnectionStatus
import dev.puklic.platform.android.connection.BatteryOptimizationExemption
import dev.puklic.platform.android.voice.isSessionActive
import dev.puklic.session.SessionManager
import dev.puklic.session.SessionState
import dev.puklic.voice.VoiceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val LOG_TAG = "AndroidBackgroundConnection"

/** What [AndroidBackgroundConnection] is built from. */
internal class BackgroundConnectionEnvironment(
    val context: Context,
    val permissions: RuntimePermissions,
    val preferences: UserPreferencesRepository,
    val sessionManager: SessionManager,
)

/**
 * The Android "receive messages when the app is closed" feature: persists the user's choice, runs
 * [BackgroundConnectionService] while it is on and a session exists, and feeds the service the connection
 * status. The Discord session itself lives in the application scope, so the service only has to keep the
 * process alive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AndroidBackgroundConnection(
    private val env: BackgroundConnectionEnvironment,
    private val scope: CoroutineScope,
) : BackgroundConnection, BackgroundConnectionHost {
    private val _enabled = MutableStateFlow(false)
    private val restored = CompletableDeferred<Unit>()
    private val batteryExemption = BatteryOptimizationExemption(env.context)

    override val supported: Boolean = true
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override val connectionStatus: StateFlow<BackgroundConnectionStatus> = env.sessionManager.activeSession
        .flatMapLatest { session -> session?.state ?: flowOf(SessionState.Disconnected) }
        .map { state -> if (state is SessionState.Connected) BackgroundConnectionStatus.Connected else BackgroundConnectionStatus.Connecting }
        .stateIn(scope, SharingStarted.Eagerly, BackgroundConnectionStatus.Connecting)

    override val voiceActive: StateFlow<Boolean> = env.sessionManager.activeSession
        .flatMapLatest { session -> session?.voiceClient?.state ?: flowOf(VoiceState.Idle) }
        .map(VoiceState::isSessionActive)
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch {
            _enabled.value = env.preferences.backgroundConnectionEnabled()
            restored.complete(Unit)
        }
        scope.launch { turnOffWhenSessionEnds() }
    }

    override suspend fun isBackgroundConnectionEnabled(): Boolean {
        restored.await()
        return _enabled.value
    }

    override fun disableBackgroundConnection() {
        scope.launch { setEnabled(false) }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        if (enabled) turnOn() else turnOff()
    }

    /** Starts the service when the setting is on; called while the activity is visible, when Android allows it. */
    fun ensureRunning() {
        scope.launch { if (isBackgroundConnectionEnabled()) startService() }
    }

    private suspend fun turnOn() {
        if (!notificationsGranted()) return
        persist(true)
        startService()
        batteryExemption.request()
    }

    private suspend fun turnOff() {
        persist(false)
        env.context.stopService(BackgroundConnectionService.startIntent(env.context))
    }

    private suspend fun notificationsGranted(): Boolean = try {
        env.permissions.ensureGranted(RuntimePermissions.NOTIFICATIONS)
    } catch (denied: PlatformDenied) {
        Logger.w(LOG_TAG, denied) { "Notification permission unavailable" }
        false
    }

    private suspend fun persist(enabled: Boolean) {
        env.preferences.setBackgroundConnectionEnabled(enabled)
        _enabled.value = enabled
    }

    private fun startService() {
        runCatching { ContextCompat.startForegroundService(env.context, BackgroundConnectionService.startIntent(env.context)) }
            .onFailure { Logger.w(LOG_TAG, it) { "Cannot start the background connection service" } }
    }

    private suspend fun turnOffWhenSessionEnds() {
        var hadSession = false
        env.sessionManager.activeSession.collect { session ->
            if (session != null) hadSession = true
            if (session == null && hadSession) {
                hadSession = false
                turnOff()
            }
        }
    }
}
