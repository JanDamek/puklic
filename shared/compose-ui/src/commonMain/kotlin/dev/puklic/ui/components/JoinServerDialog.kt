package dev.puklic.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.puklic.ui.resources.Res
import dev.puklic.ui.resources.common_close
import dev.puklic.ui.resources.join_server_join
import dev.puklic.ui.resources.join_server_joining
import dev.puklic.ui.resources.join_server_placeholder
import dev.puklic.ui.resources.join_server_title
import org.jetbrains.compose.resources.stringResource
import dev.puklic.ui.text.asString
import dev.puklic.ui.text.UiText

/**
 * Modal Join-Server dialog (issue #80). Accepts an invite URL (`https://discord.gg/<code>`,
 * `https://discord.com/invite/<code>`) or a bare code. The ViewModel parses the code and fires
 * `POST /invites/{code}`; on success the joined guild surfaces in the rail asynchronously via
 * the gateway `GUILD_CREATE` dispatch.
 */
@Composable
public fun JoinServerDialog(
    query: String,
    isSubmitting: Boolean,
    errorMessage: UiText?,
    successMessage: UiText?,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.join_server_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(Res.string.join_server_placeholder)) },
                    singleLine = true,
                    enabled = !isSubmitting,
                )
                if (errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorMessage.asString(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (successMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = successMessage.asString(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !isSubmitting && query.isNotBlank()) {
                Text(stringResource(if (isSubmitting) Res.string.join_server_joining else Res.string.join_server_join))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_close)) }
        },
    )
}
