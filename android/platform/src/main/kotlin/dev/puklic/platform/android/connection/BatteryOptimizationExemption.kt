package dev.puklic.platform.android.connection

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import co.touchlab.kermit.Logger

private const val LOG_TAG = "BatteryOptimization"

/**
 * Offers the user the system prompt that exempts the app from battery optimization, which Doze would
 * otherwise use to cut the background connection. The prompt is shown only when the app is not exempt yet.
 */
class BatteryOptimizationExemption(private val context: Context) {

    fun request() {
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(context.packageName)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Logger.w(LOG_TAG, it) { "Cannot show the battery optimization prompt" } }
    }
}
