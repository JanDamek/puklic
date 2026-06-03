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
 * Desktop placeholder. Compose for Desktop ships without a built-in web view in 1.8;
 * wiring the captcha widget here will land with the desktop captcha slice. Until then we
 * show a fallback that points the user at the Token tab — desktop users hit the captcha
 * far less often than iOS users (no NSURLSession fingerprint flag) so this remains a
 * minority path.
 */
@Composable
public actual fun CaptchaWebView(
    sitekey: String,
    service: String,
    rqdata: String?,
    onSolved: (token: String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().height(120.dp).padding(16.dp)) {
        Text(
            "Discord asked for a security check. Solve it in a browser and switch to the Token tab.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "site: $sitekey ($service)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
