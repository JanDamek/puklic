package dev.puklic.platform.macos

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
 * Same pattern as the Linux module's CommandRunner (duplicated intentionally — two small modules,
 * no shared :desktop:platform-common needed yet).
 * Uses exec form (arg array) — no shell — to avoid injection.
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
        // For absolute paths (typical on macOS: /usr/bin/security, /usr/bin/open) just check existence.
        if (executable.startsWith("/")) return java.io.File(executable).canExecute()
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
