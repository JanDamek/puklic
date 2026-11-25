package dev.puklic.android

import android.content.Context
import co.touchlab.kermit.Logger
import dev.puklic.db.PuklicDatabase
import dev.puklic.persistence.repository.UserPreferencesRepository
import dev.puklic.persistence.sqldelight.AndroidDriverFactory
import dev.puklic.platform.PlatformClipboard
import dev.puklic.platform.PlatformOpen
import dev.puklic.platform.PlatformPaths
import dev.puklic.platform.SecureStorage
import dev.puklic.platform.android.AndroidNotificationService
import dev.puklic.platform.android.AndroidPlatformClipboard
import dev.puklic.platform.android.AndroidPlatformOpen
import dev.puklic.platform.android.AndroidPlatformPaths
import dev.puklic.platform.android.AndroidSecureStorage
import dev.puklic.platform.android.RuntimePermissions
import dev.puklic.protocol.discord.discordJson
import dev.puklic.protocol.discord.rest.DiscordLoginClient
import dev.puklic.repositories.RoleStore
import dev.puklic.session.SessionManager
import dev.puklic.session.adapter.DiscordCredentialsLoginAdapter
import dev.puklic.ui.resolvers.CdnEmojiResolver
import dev.puklic.ui.resolvers.EmojiResolver
import dev.puklic.ui.resolvers.MentionResolver
import dev.puklic.ui.resolvers.RepositoryMentionResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val LOG_TAG = "AndroidDependencyGraph"

/**
 * Manual DI graph of the Android app, the analogue of the desktop `DependencyGraph` and the iOS
 * `IosDependencyGraph`: Android platform actuals, SQLDelight persistence, an OkHttp-backed Ktor
 * client and a [SessionManager] that builds one [dev.puklic.session.DiscordSession] per token.
 *
 * Created once by the `Application`; the activity builds its Decompose root from these parts.
 */
class AndroidDependencyGraph private constructor(
    val applicationScope: CoroutineScope,
    val platformPaths: PlatformPaths,
    val platformOpen: PlatformOpen,
    val platformClipboard: PlatformClipboard,
    val secureStorage: SecureStorage,
    val runtimePermissions: RuntimePermissions,
    val sessionManager: SessionManager,
    val httpClient: HttpClient,
    val userPreferences: UserPreferencesRepository,
    val mentionResolver: MentionResolver,
    val emojiResolver: EmojiResolver,
) {
    /** Ends the active voice session, if any. Invoked from the foreground notification action. */
    fun disconnectVoice() {
        applicationScope.launch { sessionManager.activeSession.value?.voiceClient?.disconnect() }
    }

    companion object {
        fun create(context: Context): AndroidDependencyGraph {
            val appContext = context.applicationContext
            val applicationScope = newApplicationScope()
            val permissions = RuntimePermissions(appContext)
            val storage = AndroidSecureStorage(appContext)
            val stores = AndroidStores(PuklicDatabase(AndroidDriverFactory(appContext).createDriver()))
            val httpClient = newHttpClient()
            val roleStore = RoleStore()
            val dependencies = SessionDependencies(
                context = appContext,
                applicationScope = applicationScope,
                httpClient = httpClient,
                stores = stores,
                notifications = AndroidNotificationService(appContext, permissions),
                roleStore = roleStore,
                permissions = permissions,
            )
            val sessionManager = SessionManager(
                applicationScope = applicationScope,
                secureStorage = storage,
                sessionFactory = { token -> buildAndroidSession(token, dependencies) },
                credentialsLogin = DiscordCredentialsLoginAdapter(DiscordLoginClient(httpClient)),
            )
            restoreStoredSession(applicationScope, sessionManager)
            return AndroidDependencyGraph(
                applicationScope = applicationScope,
                platformPaths = AndroidPlatformPaths(appContext),
                platformOpen = AndroidPlatformOpen(appContext),
                platformClipboard = AndroidPlatformClipboard(appContext),
                secureStorage = storage,
                runtimePermissions = permissions,
                sessionManager = sessionManager,
                httpClient = httpClient,
                userPreferences = stores.preferences,
                mentionResolver = RepositoryMentionResolver(stores.users, stores.channels, roleStore),
                emojiResolver = CdnEmojiResolver,
            )
        }

        private fun newHttpClient(): HttpClient = HttpClient(OkHttp) {
            install(ContentNegotiation) { json(discordJson()) }
            install(WebSockets) {}
            expectSuccess = false
        }

        private fun restoreStoredSession(scope: CoroutineScope, sessionManager: SessionManager) {
            scope.launch {
                runCatching { sessionManager.loadStoredSession() }
                    .onFailure { ex -> Logger.w(LOG_TAG, ex) { "Auto-restore failed" } }
            }
        }
    }
}
