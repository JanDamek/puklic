package dev.puklic.ui.screens.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Android placeholder — the Android KMP target is scaffold-only today (no shipping app
 * surface). Once Android is enabled, swap this for an `AndroidView { WebView(it) }` host
 * mirroring the iOS WKWebView flow.
 */
@Composable
public actual fun CaptchaWebView(
    sitekey: String,
    service: String,
    onSolved: (token: String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().height(120.dp).padding(16.dp)) {
        Text(
            "Captcha widget pending — Android target is not shipping yet.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "site: $sitekey ($service)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
