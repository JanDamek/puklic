package dev.puklic.ui.components

import androidx.compose.ui.Modifier
import dev.puklic.platform.PickedFile

/** Android: file drag-drop into the composer is not a user pattern; return the modifier unchanged. */
public actual fun Modifier.fileDropTarget(onFilesDropped: (List<PickedFile>) -> Unit): Modifier = this
