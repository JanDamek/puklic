package dev.puklic.desktop.crash

import dev.puklic.desktop.logging.LoggingBootstrap
import dev.puklic.desktop.logging.RedactingPatternLayout
import org.slf4j.LoggerFactory
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Installs a default uncaught exception handler that writes a crash report under
 * `<logDir>/crashes/crash-YYYYMMDD-HHmmss.txt` and forwards the failure to SLF4J.
 *
 * Reports include the failing thread, JVM/OS metadata, and the full stack trace. Output
 * passes through [RedactingPatternLayout.redact] so accidental tokens in exception
 * messages are masked on disk.
 *
 * Upload to a remote endpoint is intentionally out of scope (v1 = local-only).
 */
public object CrashReporter {
    private val log = LoggerFactory.getLogger("dev.puklic.CrashReporter")
    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    public fun install() {
        val dir = File(LoggingBootstrap.logDir, "crashes")
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log.error("Uncaught exception in thread {}", thread.name, throwable)
            runCatching { writeReport(dir, thread, throwable) }
                .onFailure { System.err.println("Failed to write crash report: $it") }
        }
        log.info("CrashReporter installed; crashes will be saved to {}", dir.absolutePath)
    }

    private fun writeReport(dir: File, thread: Thread, throwable: Throwable) {
        dir.mkdirs()
        val ts = LocalDateTime.now().format(timestamp)
        val file = File(dir, "crash-$ts.txt")
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val body = buildString {
            appendLine("Puklic crash report")
            appendLine("Time: ${LocalDateTime.now()}")
            appendLine("Thread: ${thread.name}")
            appendLine("JVM: ${System.getProperty("java.version")}")
            appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
            appendLine()
            appendLine("=== Stack trace ===")
            append(sw.toString())
        }
        file.writeText(RedactingPatternLayout.redact(body))
    }
}
