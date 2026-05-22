package dev.puklic.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.puklic.ui.PuklicApp
import java.awt.Dimension
import java.awt.Taskbar
import javax.imageio.ImageIO

private const val WINDOW_ICON_RESOURCE: String = "icons/puklic-256.png"
private const val DOCK_ICON_RESOURCE: String = "icons/puklic-512.png"

/**
 * Desktop entry point. Opens a 1280×800 Compose Desktop window titled "Puklic" and renders
 * [PuklicApp]. The window respects a minimum size that keeps the Compact layout usable
 * (480×600 per `docs/04_ui/adaptive-layouts.md`).
 */
public fun main(): Unit = application {
    configureDockIcon()
    val graph = remember { DependencyGraph.create() }
    val windowState: WindowState = rememberWindowState(width = 1280.dp, height = 800.dp)
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Puklic",
        icon = painterResource(WINDOW_ICON_RESOURCE),
    ) {
        window.minimumSize = Dimension(480, 600)
        PuklicApp(graph.rootComponent)
    }
}

/**
 * Sets the platform taskbar/Dock icon when supported. Silently no-ops on platforms that do
 * not expose taskbar icon control (e.g. some Linux session managers).
 */
private fun configureDockIcon() {
    if (!Taskbar.isTaskbarSupported()) return
    runCatching {
        val classLoader = ::configureDockIcon.javaClass.classLoader
        val stream = classLoader.getResourceAsStream(DOCK_ICON_RESOURCE) ?: return
        val image = stream.use(ImageIO::read) ?: return
        Taskbar.getTaskbar().iconImage = image
    }
}
