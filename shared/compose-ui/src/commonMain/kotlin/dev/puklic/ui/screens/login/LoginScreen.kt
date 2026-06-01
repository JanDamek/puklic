package dev.puklic.ui.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.puklic.ui.theme.LocalPuklicSpacing

/**
 * LoginScreen per `docs/04_ui/screens.md`. Centered 480 dp card with a TabRow switching between
 * **Token** (paste a Discord token) and **Email / Password** (Discord credentials with optional
 * MFA) sign-in modes.
 *
 * The screen is a dumb renderer over [LoginViewModel] state — events forward to the VM.
 */
@Composable
public fun LoginScreen(viewModel: LoginViewModel) {
    val state by viewModel.state.collectAsState()
    val spacing = LocalPuklicSpacing.current

    // `imePadding()` shifts the entire login surface above the system keyboard so
    // the Sign-In button remains tappable on small screens; `verticalScroll` lets
    // the user scroll the card content if the keyboard still overlaps once the
    // form grows. Belt-and-suspenders with `OnFocusBehavior.FocusableAboveKeyboard`
    // configured on the iOS view controller.
    //
    // BOTH modifiers are intentionally disabled while the captcha widget is on
    // screen: a Compose-side `verticalScroll` intercepts the drag gestures the
    // hCaptcha puzzle relies on, and the keyboard is not used during the
    // challenge so `imePadding` adds no value. Without this branch the captcha
    // renders but is uninteractive.
    val scrollEnabled = !state.captchaPending
    val outerModifier = Modifier
        .fillMaxSize()
        .let { base -> if (scrollEnabled) base.imePadding() else base }
        .let { base -> if (scrollEnabled) base.verticalScroll(rememberScrollState()) else base }
        .padding(spacing.space5)
    Box(
        modifier = outerModifier,
        contentAlignment = Alignment.Center,
    ) {
        Card(modifier = Modifier.widthIn(max = 480.dp).padding(spacing.space5)) {
            Column(
                modifier = Modifier.padding(spacing.space6),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.space4),
            ) {
                Text("Puklic", style = MaterialTheme.typography.displaySmall)
                Text(
                    "Sign in to Discord",
                    style = MaterialTheme.typography.bodyMedium,
                )

                TabRow(selectedTabIndex = state.mode.ordinal, modifier = Modifier.fillMaxWidth()) {
                    Tab(
                        selected = state.mode == LoginMode.TOKEN,
                        onClick = { viewModel.selectMode(LoginMode.TOKEN) },
                        enabled = !state.submitting,
                        text = { Text("Token") },
                    )
                    Tab(
                        selected = state.mode == LoginMode.CREDENTIALS,
                        onClick = { viewModel.selectMode(LoginMode.CREDENTIALS) },
                        enabled = !state.submitting,
                        text = { Text("Email / Password") },
                    )
                }

                Spacer(Modifier.height(spacing.space2))

                if (state.captchaPending) {
                    CaptchaSection(state = state, viewModel = viewModel)
                } else when (state.mode) {
                    LoginMode.TOKEN -> TokenForm(state = state, viewModel = viewModel)
                    LoginMode.CREDENTIALS -> CredentialsForm(state = state, viewModel = viewModel)
                }

                Text(
                    "Discord may challenge new sign-ins with captcha. If that happens, switch " +
                        "to the Token tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TokenForm(state: LoginState, viewModel: LoginViewModel) {
    // Explicit Paste affordance — Compose Multiplatform 1.8 iOS does not surface
    // the system long-press paste menu on text fields with PasswordVisualTransformation,
    // so we render a trailing IconButton that reads the system clipboard via
    // LocalClipboardManager and forwards the value to the view model.
    val clipboard = LocalClipboardManager.current
    OutlinedTextField(
        value = state.token,
        onValueChange = viewModel::onTokenChange,
        label = { Text("Paste your token") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        // Keyboard hints: password-class field; disable autocorrect + capitalization
        // so the OS suggests stored passwords (via iCloud Keychain) instead of
        // dictionary words (the localized "čau / já / jsem" Czech suggestions
        // confuse users pasting a base64 token).
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
        ),
        enabled = !state.submitting,
        isError = state.error != null,
        supportingText = state.error?.let { msg ->
            { Text(msg, color = MaterialTheme.colorScheme.error) }
        },
        trailingIcon = {
            IconButton(
                enabled = !state.submitting,
                onClick = {
                    clipboard.getText()?.text?.takeIf { it.isNotBlank() }?.let(viewModel::onTokenChange)
                },
            ) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste token")
            }
        },
        modifier = Modifier.widthIn(min = 320.dp),
    )
    Text(
        "Your token grants full access to your account. Never share it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SubmitButton(
        label = "Sign in",
        submitting = state.submitting,
        enabled = state.canSubmitToken,
        onClick = viewModel::submit,
    )
}

@Composable
private fun CredentialsForm(state: LoginState, viewModel: LoginViewModel) {
    if (state.mfaTicket == null) {
        OutlinedTextField(
            value = state.loginField,
            onValueChange = viewModel::onLoginFieldChange,
            label = { Text("Email or username") },
            singleLine = true,
            enabled = !state.submitting,
            isError = state.error != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
            ),
            modifier = Modifier.widthIn(min = 320.dp),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !state.submitting,
            isError = state.error != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
            ),
            supportingText = state.error?.let { msg ->
                { Text(msg, color = MaterialTheme.colorScheme.error) }
            },
            modifier = Modifier.widthIn(min = 320.dp),
        )
        SubmitButton(
            label = "Sign in",
            submitting = state.submitting,
            enabled = state.canSubmitCredentials,
            onClick = viewModel::submit,
        )
    } else {
        Text(
            "Enter the 6-digit code from your authenticator app.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = state.mfaCode,
            onValueChange = viewModel::onMfaCodeChange,
            label = { Text("6-digit code") },
            singleLine = true,
            enabled = !state.submitting,
            isError = state.error != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                autoCorrectEnabled = false,
            ),
            supportingText = state.error?.let { msg ->
                { Text(msg, color = MaterialTheme.colorScheme.error) }
            },
            modifier = Modifier.widthIn(min = 320.dp),
        )
        SubmitButton(
            label = "Verify",
            submitting = state.submitting,
            enabled = state.canSubmitMfa,
            onClick = viewModel::submitMfa,
        )
    }
}

@Composable
private fun CaptchaSection(state: LoginState, viewModel: LoginViewModel) {
    val sitekey = state.captchaSitekey ?: return
    val service = state.captchaService.orEmpty()
    Text(
        "Discord asked for a security check. Solve the puzzle below — " +
            "Puklic will retry sign-in automatically.",
        style = MaterialTheme.typography.bodyMedium,
    )
    // fillMaxWidth (not widthIn) so the WebView gets the full Card width — hCaptcha
    // drag puzzles render unusably small inside the default `widthIn(min = 320 dp)`
    // when the device is wide.
    CaptchaWebView(
        sitekey = sitekey,
        service = service,
        onSolved = viewModel::onCaptchaSolved,
        modifier = Modifier.fillMaxWidth(),
    )
    state.error?.let { msg ->
        Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Button(
        onClick = viewModel::cancelCaptcha,
        enabled = !state.submitting,
    ) {
        Text("Cancel")
    }
}

@Composable
private fun SubmitButton(label: String, submitting: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) {
        if (submitting) {
            CircularProgressIndicator(
                modifier = Modifier.height(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(label)
        }
    }
}
