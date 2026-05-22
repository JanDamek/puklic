package dev.puklic.desktop

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.puklic.platform.PlatformPaths
import dev.puklic.platform.SecureStorage
import dev.puklic.platform.linux.LinuxPlatformPaths
import dev.puklic.platform.linux.LinuxSecureStorage
import dev.puklic.platform.macos.MacOsPlatformPaths
import dev.puklic.platform.macos.MacOsSecureStorage
import dev.puklic.session.DiscordSession
import dev.puklic.session.SessionManager
import dev.puklic.ui.navigation.RootComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Minimal manual DI graph for the desktop app. Phase 1 wires:
 *  - OS-appropriate `PlatformPaths` + `SecureStorage` (macOS/Linux)
 *  - `SessionManager` with a [PlaceholderTransport] (real transport lands step 15-16)
 *  - `RootComponent` for navigation
 *
 * Notifications, the SQLite database, the Ktor HttpClient, and the orchestrators are
 * intentionally NOT wired yet — they are not needed to render the LoginScreen, and the
 * real wiring depends on protocol/persistence adapters that don't expose public APIs
 * (`internal` DTOs) in Phase 1. Step 15-16 lands those adapters and completes this graph.
 *
 * Once Koin's KMP setup stabilizes for our module graph, this hand-rolled graph migrates
 * to a Koin Module. Doing it manually now sidesteps the Koin 4.1 / Compose plugin order
 * pitfall and keeps the smoke test independent of DI framework configuration.
 */
public class DependencyGraph private constructor(
    public val applicationScope: CoroutineScope,
    public val platformPaths: PlatformPaths,
    public val secureStorage: SecureStorage,
    public val sessionManager: SessionManager,
    public val rootComponent: RootComponent,
) {
    public companion object {
        public fun create(): DependencyGraph {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val isMac = detectMac()
            val paths: PlatformPaths = if (isMac) MacOsPlatformPaths() else LinuxPlatformPaths()
            val storage: SecureStorage = if (isMac) MacOsSecureStorage() else LinuxSecureStorage()
            val transport = PlaceholderTransport()
            val manager = SessionManager(
                applicationScope = scope,
                secureStorage = storage,
                sessionFactory = { token -> DiscordSession(scope, token, transport) },
            )
            val lifecycle = LifecycleRegistry()
            val ctx = DefaultComponentContext(lifecycle = lifecycle)
            lifecycle.resume()
            val root = RootComponent(ctx, manager)
            return DependencyGraph(scope, paths, storage, manager, root)
        }

        private fun detectMac(): Boolean {
            val osName = System.getProperty("os.name")?.lowercase().orEmpty()
            return "mac" in osName || "darwin" in osName
        }
    }
}
