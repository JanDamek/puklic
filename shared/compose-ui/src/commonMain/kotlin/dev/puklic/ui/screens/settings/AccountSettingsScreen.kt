package dev.puklic.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.puklic.ui.components.PuklicAvatar
import dev.puklic.ui.screens.main.MainViewModel
import dev.puklic.ui.theme.LocalPuklicSpacing
import kotlinx.coroutines.launch

/**
 * ACCOUNT category content per architect report v2 §6: avatar header + user ID row + logout.
 * Email row deliberately omitted (UserSummary has no email field).
 */
@Composable
public fun AccountSettingsScreen(viewModel: MainViewModel) {
    val spacing = LocalPuklicSpacing.current
    val self by viewModel.selfUser.collectAsState()
    val presences by viewModel.presences.collectAsState()
    val scope = rememberCoroutineScope()
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(spacing.space5),
        verticalArrangement = Arrangement.spacedBy(spacing.space4),
    ) {
        if (self == null) {
            Text(
                "Loading account…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val user = self!!
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space4),
        ) {
            PuklicAvatar(
                user = user,
                size = 80.dp,
                showPresence = true,
                presence = presences[user.id],
                ringColor = MaterialTheme.colorScheme.surface,
            )
            Column {
                Text(
                    text = user.globalName ?: user.username,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "@${user.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LabeledRow(label = "User ID", value = user.id.value.toString())
        Spacer(Modifier.height(spacing.space5))
        Button(
            onClick = { showLogoutConfirm = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) { Text("Log out") }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Log out of Puklic?") },
            text = {
                Text(
                    "Your Discord token will be removed from this device. " +
                        "Local message cache is kept on disk to speed up future sign-in.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    scope.launch { viewModel.logout() }
                }) { Text("Log out") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    val spacing = LocalPuklicSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.space4),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
