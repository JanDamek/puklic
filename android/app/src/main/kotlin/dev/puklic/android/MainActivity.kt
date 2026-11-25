package dev.puklic.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.arkivanov.decompose.defaultComponentContext
import dev.puklic.ui.navigation.RootComponent
import dev.puklic.ui.PuklicApp as PuklicRoot

/**
 * Hosts the shared Compose UI. The Decompose root follows the activity lifecycle; the graph, sessions
 * and permissions live in [PuklicApp], so a recreated activity reattaches to the running session.
 */
class MainActivity : ComponentActivity() {
    private val graph: AndroidDependencyGraph get() = (application as PuklicApp).graph

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            graph.runtimePermissions.onResult(results)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = RootComponent(
            componentContext = defaultComponentContext(),
            sessionManager = graph.sessionManager,
            preferences = graph.userPreferences,
            secureStorage = graph.secureStorage,
        )
        setContent {
            PuklicRoot(
                root = root,
                mentionResolver = graph.mentionResolver,
                emojiResolver = graph.emojiResolver,
                platformOpen = graph.platformOpen,
                platformClipboard = graph.platformClipboard,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        graph.runtimePermissions.attach { permissions -> permissionLauncher.launch(permissions) }
    }

    override fun onStop() {
        graph.runtimePermissions.detach()
        super.onStop()
    }
}
