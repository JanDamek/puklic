package dev.puklic.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.puklic.ui.PuklicApp
import java.awt.Dimension

/**
 * Desktop entry point. Opens a 1280×800 Compose Desktop window titled "Puklic" and renders
 * [PuklicApp]. The window respects a minimum size that keeps the Compact layout usable
 * (480×600 per `docs/04_ui/adaptive-layouts.md`).
 */
public fun main(): Unit = application {
    val graph = remember { DependencyGraph.create() }
    val windowState: WindowState = rememberWindowState(width = 1280.dp, height = 800.dp)
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Puklic",
    ) {
        window.minimumSize = Dimension(480, 600)
        PuklicApp(graph.rootComponent)
    }
}
