package dev.puklic.desktop.logging

import java.io.File
import java.io.OutputStream
import java.util.logging.FileHandler
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.Logger
import java.util.logging.StreamHandler

private const val MAX_LOG_FILE_BYTES = 10 * 1024 * 1024
private const val LOG_FILE_COUNT = 14
private const val LOG_FILE_PATTERN = "puklic.%g.log"

private val LOGGER_LEVELS: Map<String, Level> = mapOf(
    "" to Level.INFO,
    "dev.puklic" to Level.FINE,
    "io.ktor" to Level.WARNING,
)

/**
 * Installs the `java.util.logging` backend that SLF4J (`slf4j-jdk14`) writes through: a console
 * handler and a size-rotated file handler, both rendering through [RedactingFormatter].
 * `java.util.logging` ships with the JDK, so unlike a separate logging framework it adds almost
 * no loaded classes at idle.
 */
internal object JulLogging {

    private val configuredLoggers = mutableListOf<Logger>()

    fun install(logDir: File) {
        LogManager.getLogManager().reset()
        configuredLoggers += LOGGER_LEVELS.map { (name, level) -> Logger.getLogger(name).apply { this.level = level } }
        val root = Logger.getLogger("")
        root.addHandler(ConsoleHandler(System.out))
        root.addHandler(fileHandler(logDir))
    }

    private fun fileHandler(logDir: File): Handler =
        FileHandler(File(logDir, LOG_FILE_PATTERN).absolutePath, MAX_LOG_FILE_BYTES, LOG_FILE_COUNT, true).apply {
            formatter = RedactingFormatter(LogLayout.File)
            encoding = Charsets.UTF_8.name()
        }
}

private class ConsoleHandler(output: OutputStream) : StreamHandler(output, RedactingFormatter(LogLayout.Console)) {
    init {
        level = Level.ALL
    }

    @Synchronized
    override fun publish(record: java.util.logging.LogRecord) {
        super.publish(record)
        flush()
    }

    override fun close() = flush()
}
