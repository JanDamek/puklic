package dev.puklic.ui.components

import androidx.compose.ui.Modifier
import dev.puklic.platform.PickedFile

/**
 * Platform-specific `Modifier` that turns the receiver into a file drop target. The JVM/Compose
 * Desktop actual reads the dropped files into memory and invokes [onFilesDropped]. Other
 * platforms supply no-op actuals — drag-drop is a desktop-only affordance per the issue #23
 * architect comment.
 */
public expect fun Modifier.fileDropTarget(onFilesDropped: (List<PickedFile>) -> Unit): Modifier
