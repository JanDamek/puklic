package dev.puklic.ui.screens.login

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import co.touchlab.kermit.Logger

private const val CAPTCHA_HEIGHT_DP = 600
private const val CAPTCHA_BASE_URL = "https://discord.com/"
private const val HTML_MIME_TYPE = "text/html"
private const val HTML_ENCODING = "UTF-8"
private const val LOG_TAG = "CaptchaWebView"

@Composable
public actual fun CaptchaWebView(
    sitekey: String,
    service: String,
    rqdata: String?,
    onSolved: (token: String) -> Unit,
    modifier: Modifier,
) {
    val latestOnSolved by rememberUpdatedState(onSolved)
    val html = remember(sitekey, service, rqdata) { captchaHtml(service = service, sitekey = sitekey, rqdata = rqdata) }
    key(html) {
        AndroidView(
            factory = { context -> createCaptchaWebView(context, html) { token -> latestOnSolved(token) } },
            modifier = modifier
                .fillMaxWidth()
                .height(CAPTCHA_HEIGHT_DP.dp),
            onRelease = { web ->
                web.removeJavascriptInterface(CAPTCHA_BRIDGE_NAME)
                web.destroy()
            },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createCaptchaWebView(
    context: Context,
    html: String,
    onSolved: (String) -> Unit,
): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    addJavascriptInterface(CaptchaBridge(this, onSolved), CAPTCHA_BRIDGE_NAME)
    loadDataWithBaseURL(CAPTCHA_BASE_URL, html, HTML_MIME_TYPE, HTML_ENCODING, null)
}

/**
 * Receives `window.captcha.postMessage(...)` calls from the captcha page. Messages prefixed with
 * [CAPTCHA_ERROR_PREFIX] are hCaptcha error codes and are only logged so the user can retry without
 * the widget visually clearing. Calls arrive on a WebView binder thread, the token is handed to the
 * UI thread before it reaches the view model.
 */
private class CaptchaBridge(
    private val host: WebView,
    private val onSolved: (String) -> Unit,
) {
    @JavascriptInterface
    fun postMessage(body: String) {
        if (body.startsWith(CAPTCHA_ERROR_PREFIX)) {
            Logger.w(LOG_TAG) { "hCaptcha widget error: ${body.removePrefix(CAPTCHA_ERROR_PREFIX)}" }
            return
        }
        if (body.isNotBlank()) host.post { onSolved(body) }
    }
}
