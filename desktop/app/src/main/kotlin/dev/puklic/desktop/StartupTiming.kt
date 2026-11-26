package dev.puklic.desktop

import co.touchlab.kermit.Logger
import java.lang.management.ManagementFactory

private const val STARTUP_TAG: String = "Startup"

/**
 * Logs startup milestones as `milestone=<name> jvmUptimeMs=<n> epochMs=<n>` so that cold-start
 * time can be derived from the process log (`tools/perf`). The epoch value lets an external
 * launcher subtract its own spawn timestamp, covering the JVM boot that precedes `main`.
 */
internal object StartupTiming {
    fun milestone(name: String) {
        val jvmUptimeMs = ManagementFactory.getRuntimeMXBean().uptime
        Logger.i(STARTUP_TAG) {
            "milestone=$name jvmUptimeMs=$jvmUptimeMs epochMs=${System.currentTimeMillis()}"
        }
    }
}
