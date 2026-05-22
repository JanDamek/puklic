package dev.puklic.ui.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.puklic.ui.theme.LocalPuklicSpacing

/**
 * LoginScreen per `docs/04_ui/screens.md`. Centered 480 dp card with token input + submit.
 *
 * The screen is a dumb renderer over [LoginViewModel] state — events forward to the VM.
 */
@Composable
public fun LoginScreen(viewModel: LoginViewModel) {
    val state by viewModel.state.collectAsState()
    val spacing = LocalPuklicSpacing.current

    Box(
        modifier = Modifier.fillMaxSize().padding(spacing.space5),
        contentAlignment = Alignment.Center,
    ) {
        Card(modifier = Modifier.widthIn(max = 480.dp).padding(spacing.space5)) {
            Column(
                modifier = Modifier.padding(spacing.space6),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.space4),
            ) {
                Text("Puklic", style = MaterialTheme.typography.displaySmall)
                Text("Sign in with a Discord token", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(spacing.space2))
                OutlinedTextField(
                    value = state.token,
                    onValueChange = viewModel::onTokenChange,
                    label = { Text("Paste your token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !state.submitting,
                    isError = state.error != null,
                    supportingText = state.error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    modifier = Modifier.widthIn(min = 320.dp),
                )
                Text(
                    "Your token grants full access to your account. Never share it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = viewModel::submit, enabled = state.canSubmit) {
                    if (state.submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Sign in")
                    }
                }
            }
        }
    }
}
