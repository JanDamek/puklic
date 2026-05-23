package dev.puklic.desktop.update

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Non-blocking banner shown above the main app content when a newer Puklic release is detected
 * on GitHub. Click "Download" opens the release page in the system browser via
 * [dev.puklic.platform.PlatformOpen]; click the close icon dismisses for the current process.
 *
 * Lives in `:desktop:app` (not `:shared:compose-ui`) because the update mechanism itself is
 * desktop-only — mobile platforms have their own store-managed update paths.
 */
@Composable
public fun UpdateBanner(
    update: UpdateChecker.UpdateInfo?,
    onOpenRelease: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (update == null) return
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Puklic ${update.version} is available",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onOpenRelease(update.url) }) {
                Text("Download")
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = "Dismiss update notification")
            }
        }
    }
}
