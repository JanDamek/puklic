package dev.puklic.platform.linux

import dev.puklic.platform.PlatformFailed
import dev.puklic.platform.PlatformUnavailable
import dev.puklic.platform.SecureStorage

/**
 * libsecret-backed implementation using the `secret-tool` CLI.
 *
 * Install: `sudo apt install libsecret-tools` / `sudo dnf install libsecret`.
 * Throws [PlatformUnavailable] if the CLI is missing.
 *
 * NEVER logs values. Secrets are passed via stdin (`--label ... store ...` with `secret-tool`
 * reads the password from stdin, avoiding argv leakage to `/proc/<pid>/cmdline`).
 */
class LinuxSecureStorage(
    private val serviceName: String = DEFAULT_SERVICE,
    private val runner: CommandRunner = CommandRunner(),
) : SecureStorage {

    private fun requireSecretTool() {
        if (!runner.isOnPath(EXEC)) {
            throw PlatformUnavailable("libsecret-tools not installed (missing `$EXEC`)")
        }
    }

    override suspend fun put(key: String, value: String) {
        requireSecretTool()
        val result = runner.run(
            args = listOf(EXEC, "store", "--label=Puklic — $key", "service", serviceName, "account", key),
            stdin = value,
        )
        result.successOrThrow("secret-tool store")
    }

    override suspend fun get(key: String): String? {
        requireSecretTool()
        val result = runner.run(
            args = listOf(EXEC, "lookup", "service", serviceName, "account", key),
        )
        return when (result.exitCode) {
            0 -> result.stdout.trimEnd('\n').ifEmpty { null }
            // secret-tool exits 1 on "not found"; treat as null
            1 -> null
            else -> throw PlatformFailed("secret-tool lookup failed (exit=${result.exitCode}): ${result.stderr.trim()}")
        }
    }

    override suspend fun remove(key: String) {
        requireSecretTool()
        val result = runner.run(
            args = listOf(EXEC, "clear", "service", serviceName, "account", key),
        )
        // exit 1 = not found is fine (idempotent remove)
        if (result.exitCode != 0 && result.exitCode != 1) {
            throw PlatformFailed("secret-tool clear failed (exit=${result.exitCode}): ${result.stderr.trim()}")
        }
    }

    override suspend fun list(): List<String> {
        requireSecretTool()
        val result = runner.run(
            args = listOf(EXEC, "search", "--all", "service", serviceName),
        )
        // exit 1 = no matches → empty list
        if (result.exitCode == 1) return emptyList()
        result.successOrThrow("secret-tool search")
        return parseAccounts(result.stderr + "\n" + result.stdout)
    }

    companion object {
        internal const val EXEC = "secret-tool"
        internal const val DEFAULT_SERVICE = "puklic-client"

        /**
         * `secret-tool search` emits `attribute.<name> = <value>` lines (to stderr on some versions,
         * stdout on others). We grep account lines from both streams.
         */
        internal fun parseAccounts(output: String): List<String> =
            output.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("attribute.account = ") }
                .map { it.removePrefix("attribute.account = ").trim() }
                .toList()
    }
}
