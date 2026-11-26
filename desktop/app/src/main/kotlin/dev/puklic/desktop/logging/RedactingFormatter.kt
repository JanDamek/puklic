package dev.puklic.desktop.logging

import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord

/**
 * `java.util.logging` formatter that renders a log line in the given [layout] and masks Discord
 * credentials before the line reaches any handler.
 *
 * Three patterns are redacted:
 *  - `Authorization: <token>` HTTP headers
 *  - bare `Bearer <token>` occurrences
 *  - Discord MFA tokens (prefix `mfa.`)
 *
 * Threshold for the generic Bearer/Authorization match is 20 chars so we do not redact
 * unrelated short identifiers. MFA tokens have a fixed prefix and are always redacted.
 */
public class RedactingFormatter(private val layout: LogLayout) : Formatter() {

    override fun format(record: LogRecord): String =
        redact(layout.render(LogLine(record, Thread.currentThread().name, messageWithTrace(record))))

    private fun messageWithTrace(record: LogRecord): String {
        val message = formatMessage(record).orEmpty()
        val thrown = record.thrown ?: return message
        val trace = StringWriter().also { thrown.printStackTrace(PrintWriter(it)) }
        return "$message\n$trace".trimEnd()
    }

    public companion object {
        private val AUTH_HEADER_REGEX = Regex("""(?i)(Authorization\s*[:=]\s*(?:Bearer\s+)?)([A-Za-z0-9._\-]{20,})""")
        private val BEARER_REGEX = Regex("""(Bearer\s+)([A-Za-z0-9._\-]{20,})""")
        private val MFA_REGEX = Regex("""mfa\.[A-Za-z0-9._\-]{20,}""")

        /** Public for unit testing; applies the same redaction rules as [format]. */
        public fun redact(input: String): String =
            input
                .replace(AUTH_HEADER_REGEX) { m -> "${m.groupValues[1]}<REDACTED>" }
                .replace(BEARER_REGEX) { m -> "${m.groupValues[1]}<REDACTED>" }
                .replace(MFA_REGEX, "<REDACTED-MFA>")
    }
}

/** A log record together with the name of the thread that logged it. */
public class LogLine(public val record: LogRecord, public val threadName: String, public val message: String)

/** Line layouts: a short console line and a verbose file line. */
public enum class LogLayout {
    Console {
        override fun render(line: LogLine): String =
            "${CONSOLE_TIME.format(line.instant())} ${line.levelName().padEnd(LEVEL_WIDTH)} " +
                "[${abbreviate(line.record.loggerName.orEmpty())}] ${line.message}\n"
    },
    File {
        override fun render(line: LogLine): String =
            "${FILE_TIME.format(line.instant())} [${line.threadName}] ${line.levelName().padEnd(LEVEL_WIDTH)} " +
                "[${line.record.loggerName.orEmpty()}] - ${line.message}\n"
    };

    internal abstract fun render(line: LogLine): String
}

private const val LEVEL_WIDTH = 5
private const val MAX_LOGGER_WIDTH = 20
private val CONSOLE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
private val FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS").withZone(ZoneId.systemDefault())

private val LEVEL_NAMES: Map<Level, String> = mapOf(
    Level.FINEST to "TRACE",
    Level.FINER to "TRACE",
    Level.FINE to "DEBUG",
    Level.CONFIG to "DEBUG",
    Level.INFO to "INFO",
    Level.WARNING to "WARN",
    Level.SEVERE to "ERROR",
)

private fun LogLine.instant(): Instant = record.instant

private fun LogLine.levelName(): String = LEVEL_NAMES[record.level] ?: record.level.name

private fun abbreviate(loggerName: String): String {
    val segments = loggerName.split('.').toMutableList()
    var index = 0
    while (segments.joinToString(".").length > MAX_LOGGER_WIDTH && index < segments.lastIndex) {
        segments[index] = segments[index].take(1)
        index++
    }
    return segments.joinToString(".")
}
