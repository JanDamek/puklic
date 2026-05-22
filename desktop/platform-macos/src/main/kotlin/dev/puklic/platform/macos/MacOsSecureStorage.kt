package dev.puklic.platform.macos

import dev.puklic.platform.PlatformFailed
import dev.puklic.platform.PlatformUnavailable
import dev.puklic.platform.SecureStorage

/**
 * macOS Keychain via `/usr/bin/security` CLI.
 *
 * Service name (`-s`) is fixed per instance; account (`-a`) is the secret key.
 * Values are passed with `-w <value>`; this DOES place the password in argv, briefly visible to
 * `ps` for the local user. Acceptable for MVP; Phase 2 switches to JNA + SecItemAdd/Copy to avoid.
 */
class MacOsSecureStorage(
    private val serviceName: String = DEFAULT_SERVICE,
    private val runner: CommandRunner = CommandRunner(),
) : SecureStorage {

    private fun requireSecurity() {
        if (!runner.isOnPath(EXEC)) {
            throw PlatformUnavailable("macOS `security` CLI not found at $EXEC")
        }
    }

    override suspend fun put(key: String, value: String) {
        requireSecurity()
        runner.run(
            args = listOf(EXEC, "add-generic-password", "-U", "-s", serviceName, "-a", key, "-w", value),
        ).successOrThrow("security add-generic-password")
    }

    override suspend fun get(key: String): String? {
        requireSecurity()
        val result = runner.run(
            args = listOf(EXEC, "find-generic-password", "-s", serviceName, "-a", key, "-w"),
        )
        return when (result.exitCode) {
            0 -> result.stdout.trimEnd('\n').ifEmpty { null }
            // 44 = errSecItemNotFound (security CLI maps it to exit 44)
            44 -> null
            else -> throw PlatformFailed("security find-generic-password failed (exit=${result.exitCode}): ${result.stderr.trim()}")
        }
    }

    override suspend fun remove(key: String) {
        requireSecurity()
        val result = runner.run(
            args = listOf(EXEC, "delete-generic-password", "-s", serviceName, "-a", key),
        )
        if (result.exitCode != 0 && result.exitCode != 44) {
            throw PlatformFailed("security delete-generic-password failed (exit=${result.exitCode}): ${result.stderr.trim()}")
        }
    }

    override suspend fun list(): List<String> {
        requireSecurity()
        // `security dump-keychain` lists all entries. Filter by our service, extract accounts.
        val result = runner.run(args = listOf(EXEC, "dump-keychain"))
        if (!result.succeeded) {
            // Some keychains require -d login; treat failure as empty rather than throwing.
            return emptyList()
        }
        return parseAccountsFromDump(result.stdout, serviceName)
    }

    companion object {
        internal const val EXEC = "/usr/bin/security"
        internal const val DEFAULT_SERVICE = "puklic-client"

        /**
         * `security dump-keychain` emits per-entry blocks containing lines like:
         *   "svce"<blob>="puklic-client"
         *   "acct"<blob>="alpha"
         * We pair entries by tracking the current block.
         */
        internal fun parseAccountsFromDump(dump: String, service: String): List<String> {
            val accounts = mutableListOf<String>()
            var currentService: String? = null
            var currentAccount: String? = null
            fun flush() {
                if (currentService == service && currentAccount != null) {
                    accounts += currentAccount!!
                }
                currentService = null
                currentAccount = null
            }
            for (rawLine in dump.lineSequence()) {
                val line = rawLine.trim()
                if (line.startsWith("keychain:")) {
                    flush()
                    continue
                }
                val svc = extractAttribute(line, "svce")
                if (svc != null) currentService = svc
                val acct = extractAttribute(line, "acct")
                if (acct != null) currentAccount = acct
            }
            flush()
            return accounts.distinct()
        }

        private fun extractAttribute(line: String, name: String): String? {
            // Format examples:
            //   "svce"<blob>="puklic-client"
            //   "acct"<blob>=0x6162  "ab"
            val needle = "\"$name\""
            if (!line.startsWith(needle)) return null
            val eq = line.indexOf('=', needle.length)
            if (eq < 0) return null
            val rest = line.substring(eq + 1).trim()
            // Take last quoted segment if hex+ascii dual form.
            val lastQuote = rest.lastIndexOf('"')
            val firstQuoteOfLast = rest.lastIndexOf('"', lastQuote - 1).takeIf { it >= 0 } ?: return null
            return rest.substring(firstQuoteOfLast + 1, lastQuote)
        }
    }
}
