package dev.puklic.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.arkivanov.decompose.defaultComponentContext
import dev.puklic.platform.android.notificationTarget
import dev.puklic.ui.navigation.ChannelTarget
import dev.puklic.ui.navigation.RootComponent
import dev.puklic.ui.PuklicApp as PuklicRoot

/**
 * Hosts the shared Compose UI. The Decompose root follows the activity lifecycle; the graph, sessions
 * and permissions live in [PuklicApp], so a recreated activity reattaches to the running session.
 */
class MainActivity : ComponentActivity() {
    private lateinit var root: RootComponent
    private val graph: AndroidDependencyGraph get() = (application as PuklicApp).graph

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            graph.runtimePermissions.onResult(results)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        root = RootComponent(
            componentContext = defaultComponentContext(),
            sessionManager = graph.sessionManager,
            preferences = graph.userPreferences,
            secureStorage = graph.secureStorage,
        )
        intent.openRequestedChannel()
        setContent {
            PuklicRoot(
                root = root,
                mentionResolver = graph.mentionResolver,
                emojiResolver = graph.emojiResolver,
                platformOpen = graph.platformOpen,
                platformClipboard = graph.platformClipboard,
                backgroundConnection = graph.backgroundConnection,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.openRequestedChannel()
    }

    private fun Intent.openRequestedChannel() {
        notificationTarget()?.let { root.openChannel(ChannelTarget(it.guildId, it.channelId)) }
    }

    override fun onStart() {
        super.onStart()
        graph.runtimePermissions.attach { permissions -> permissionLauncher.launch(permissions) }
        graph.backgroundConnection.ensureRunning()
    }

    override fun onStop() {
        graph.runtimePermissions.detach()
        super.onStop()
    }
}
