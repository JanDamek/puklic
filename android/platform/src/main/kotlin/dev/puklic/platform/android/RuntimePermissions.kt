package dev.puklic.platform.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.puklic.platform.PlatformDenied
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Requests Android runtime permissions on demand, only when a feature first needs them.
 *
 * The foreground activity owns the actual `ActivityResultLauncher`: it calls [attach] while it is
 * started, forwards results through [onResult] and calls [detach] when it stops. Without an
 * attached activity no dialog can be shown, so a missing permission is reported as denied.
 */
class RuntimePermissions(private val context: Context) {
    private val requestLock = Mutex()
    private val launcher = AtomicReference<((Array<String>) -> Unit)?>(null)
    private val pending = AtomicReference<CompletableDeferred<Map<String, Boolean>>?>(null)

    fun attach(launch: (Array<String>) -> Unit) = launcher.set(launch)

    fun detach() {
        launcher.set(null)
        pending.getAndSet(null)?.complete(emptyMap())
    }

    fun onResult(results: Map<String, Boolean>) {
        pending.getAndSet(null)?.complete(results)
    }

    /** Returns true when every permission in [permissions] is granted, asking the user for the missing ones. */
    suspend fun ensureGranted(permissions: List<String>): Boolean {
        val missing = permissions.filterNot(::isGranted)
        if (missing.isEmpty()) return true
        val results = request(missing)
        return missing.all { results[it] == true }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private suspend fun request(permissions: List<String>): Map<String, Boolean> = requestLock.withLock {
        val launch = launcher.get() ?: throw PlatformDenied(NO_ACTIVITY)
        val result = CompletableDeferred<Map<String, Boolean>>()
        pending.set(result)
        withContext(Dispatchers.Main.immediate) { launch(permissions.toTypedArray()) }
        result.await()
    }

    companion object {
        private const val NO_ACTIVITY = "No foreground activity available to show the permission dialog"

        val MICROPHONE: List<String> = listOf(Manifest.permission.RECORD_AUDIO)

        /** `POST_NOTIFICATIONS` is a runtime permission only on Android 13 and newer. */
        val NOTIFICATIONS: List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyList()
            }
    }
}
