package dev.puklic.desktop.logging

import co.touchlab.kermit.Logger
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale

/**
 * Initializes platform-aware logging:
 *  1. Resolves a per-OS log directory and exposes it as the `puklic.log.dir` system property
 *     so that `logback.xml` can pick it up via `${puklic.log.dir}`.
 *  2. Bridges Kermit (used in commonMain via `co.touchlab.kermit.Logger`) into SLF4J so the
 *     same logs land in the rotating file + redacting layout configured by Logback.
 *
 * Must be called before any logger is touched (i.e. as the very first statement in `main`).
 */
public object LoggingBootstrap {

    /** Resolved log directory; populated after [install]. */
    public lateinit var logDir: File
        private set

    public fun install() {
        logDir = resolveLogDir().also { it.mkdirs() }
        // logback.xml reads ${puklic.log.dir} when initializing appenders.
        System.setProperty("puklic.log.dir", logDir.absolutePath)

        // Bridge Kermit into SLF4J. Replaces default platform writer; keeps existing
        // commonMain `Logger.i { ... }` call sites unchanged across all targets.
        Logger.setLogWriters(listOf(Slf4jKermitWriter()))

        val log = LoggerFactory.getLogger("dev.puklic.LoggingBootstrap")
        log.info("Logging initialized; log directory: {}", logDir.absolutePath)
    }

    private fun resolveLogDir(): File {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val home = System.getProperty("user.home")
        return when {
            os.contains("mac") -> File(home, "Library/Logs/Puklic")
            os.contains("windows") -> {
                val localAppData = System.getenv("LOCALAPPDATA") ?: File(home, "AppData/Local").absolutePath
                File(localAppData, "Puklic/logs")
            }
            else -> {
                val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                val base = xdg?.let { File(it) } ?: File(home, ".local/share")
                File(base, "puklic/logs")
            }
        }
    }
}

/** Forwards Kermit log records to SLF4J using the Kermit tag as the SLF4J logger name. */
private class Slf4jKermitWriter : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val logger = LoggerFactory.getLogger(if (tag.isBlank()) "kermit" else tag)
        when (severity) {
            Severity.Verbose -> if (logger.isTraceEnabled) logger.trace(message, throwable)
            Severity.Debug -> if (logger.isDebugEnabled) logger.debug(message, throwable)
            Severity.Info -> if (logger.isInfoEnabled) logger.info(message, throwable)
            Severity.Warn -> if (logger.isWarnEnabled) logger.warn(message, throwable)
            Severity.Error, Severity.Assert -> if (logger.isErrorEnabled) logger.error(message, throwable)
        }
    }
}
