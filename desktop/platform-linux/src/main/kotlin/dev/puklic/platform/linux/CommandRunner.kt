package dev.puklic.platform.linux

import dev.puklic.platform.PlatformFailed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val succeeded: Boolean get() = exitCode == 0

    fun successOrThrow(label: String): CommandResult {
        if (!succeeded) {
            throw PlatformFailed("$label failed (exit=$exitCode): ${stderr.trim().ifEmpty { stdout.trim() }}")
        }
        return this
    }
}

/**
 * Thin wrapper around [ProcessBuilder]. Uses exec form (arg array) — no shell — to avoid injection.
 * Never logs values passed via stdin (caller responsibility re: secrets).
 */
open class CommandRunner {
    open suspend fun run(
        args: List<String>,
        stdin: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): CommandResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(args)
            .redirectErrorStream(false)
            .start()
        try {
            if (stdin != null) {
                process.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
            } else {
                process.outputStream.close()
            }
            val stdoutBytes = process.inputStream.readBytes()
            val stderrBytes = process.errorStream.readBytes()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw PlatformFailed("Command timed out after ${timeoutMs}ms: ${args.first()}")
            }
            CommandResult(
                exitCode = process.exitValue(),
                stdout = stdoutBytes.toString(Charsets.UTF_8),
                stderr = stderrBytes.toString(Charsets.UTF_8),
            )
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    open fun isOnPath(executable: String): Boolean {
        val pathEnv = System.getenv("PATH") ?: return false
        val sep = System.getProperty("path.separator") ?: ":"
        return pathEnv.split(sep)
            .asSequence()
            .filter { it.isNotEmpty() }
            .map { java.io.File(it, executable) }
            .any { it.canExecute() }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 5_000L
    }
}
