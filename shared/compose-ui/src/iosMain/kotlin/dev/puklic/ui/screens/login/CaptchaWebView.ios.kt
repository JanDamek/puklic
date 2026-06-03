@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.puklic.ui.screens.login

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWindowFeatures
import platform.darwin.NSObject

// hCaptcha drag-puzzle challenges (Discord's most common type) need ~520-560 dp for the
// challenge image plus the controls / Skip button row. 600 dp covers every variant.
private const val CAPTCHA_HEIGHT_DP = 600
private const val BRIDGE_NAME = "captcha"

@Composable
public actual fun CaptchaWebView(
    sitekey: String,
    service: String,
    rqdata: String?,
    onSolved: (token: String) -> Unit,
    modifier: Modifier,
) {
    // `rememberUpdatedState` so the bridge always calls the *latest* onSolved lambda
    // (Compose may swap it on recomposition while the WKWebView + handler stay alive).
    val latestOnSolved by rememberUpdatedState(onSolved)
    val handler = remember { CaptchaMessageHandler { token -> latestOnSolved(token) } }
    val uiDelegate = remember { CaptchaUiDelegate() }
    UIKitView(
        factory = {
            val controller = WKUserContentController().apply {
                addScriptMessageHandler(handler, name = BRIDGE_NAME)
            }
            val configuration = WKWebViewConfiguration().apply {
                userContentController = controller
                // hCaptcha checkbox flow opens a popup window to render the actual
                // challenge; without these the popup is silently blocked and the
                // checkbox appears to flash + reset.
                preferences.javaScriptCanOpenWindowsAutomatically = true
                allowsInlineMediaPlayback = true
            }
            val web = WKWebView(
                frame = platform.CoreGraphics.CGRectMake(0.0, 0.0, 0.0, 0.0),
                configuration = configuration,
            ).apply {
                this.UIDelegate = uiDelegate
                // hCaptcha gates desktop / mobile by UA; claim mobile Safari so the
                // widget renders the touch-optimised challenge (and accepts the
                // tap that solves the "I am human" checkbox in the first place).
                this.customUserAgent =
                    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
                        "AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                        "Version/17.0 Mobile/15E148 Safari/604.1"
            }
            web.loadHTMLString(
                captchaHtml(service = service, sitekey = sitekey, rqdata = rqdata),
                baseURL = NSURL.URLWithString("https://discord.com/"),
            )
            web
        },
        modifier = modifier
            .fillMaxWidth()
            .height(CAPTCHA_HEIGHT_DP.dp),
        update = { /* no-op — sitekey changes recreate the host via remember key */ },
    )
}

/**
 * Lets hCaptcha open its challenge popup inside the same WKWebView instead of having
 * the system block it. Without this the checkbox flow appears to flash and reset.
 */
private class CaptchaUiDelegate : NSObject(), WKUIDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures,
    ): WKWebView? {
        val target = forNavigationAction.targetFrame
        if (target == null || !target.mainFrame) {
            forNavigationAction.request.URL?.let { webView.loadRequest(NSURLRequest(uRL = it)) }
        }
        return null
    }
}


/**
 * WKScriptMessageHandler that forwards `window.webkit.messageHandlers.captcha.postMessage(token)`
 * calls from the page JS into the Kotlin callback. Messages prefixed with `__error:` are
 * hCaptcha error codes and are dropped (logged-only) so the user can retry without the
 * widget visually clearing.
 */
private class CaptchaMessageHandler(
    private val onSolved: (String) -> Unit,
) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val body = didReceiveScriptMessage.body as? String ?: return
        if (body.startsWith("__error:")) {
            // hCaptcha widget errored (network, expired, …); leave the widget on screen
            // so the user can retry without our LoginViewModel resetting captcha state.
            platform.Foundation.NSLog("puklic-captcha widget error: %@", body.removePrefix("__error:"))
            return
        }
        if (body.isNotBlank()) onSolved(body)
    }
}

/**
 * Build the HTML document loaded into the WKWebView. Defaults to hCaptcha (Discord's
 * primary provider); [service] is currently accepted for future arkose_labs support but
 * the present implementation always renders the hCaptcha widget — Discord serves arkose
 * extremely rarely (and only on already-flagged accounts), so the hCaptcha path covers
 * effectively every challenge a normal sign-in encounters.
 */
private fun captchaHtml(service: String, sitekey: String, rqdata: String?): String {
    // hCaptcha Enterprise challenge: when Discord supplies rqdata it MUST be rendered on the
    // widget (`data-rqdata`), otherwise the solved token is rejected and the login loops.
    val rqdataAttr = rqdata?.takeIf { it.isNotBlank() }?.let { "\n       data-rqdata=\"$it\"" }.orEmpty()
    return """
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  <style>
    body { margin: 0; padding: 8px; background: #1e1f22; color: #dbdee1;
           font-family: -apple-system, BlinkMacSystemFont, sans-serif;
           display: flex; flex-direction: column; align-items: center; }
    .service { font-size: 11px; opacity: 0.6; margin-top: 8px; }
    .h-captcha { margin-top: 4px; }
  </style>
  <script src="https://js.hcaptcha.com/1/api.js" async defer></script>
  <script>
    function onCaptcha(token) {
      if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.$BRIDGE_NAME) {
        window.webkit.messageHandlers.$BRIDGE_NAME.postMessage(token);
      }
    }
    function onCaptchaError(err) {
      if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.$BRIDGE_NAME) {
        window.webkit.messageHandlers.$BRIDGE_NAME.postMessage('__error:' + err);
      }
    }
  </script>
</head>
<body>
  <div class="h-captcha"
       data-sitekey="$sitekey"
       data-callback="onCaptcha"
       data-error-callback="onCaptchaError"$rqdataAttr
       data-theme="dark"></div>
  <div class="service">via ${service.ifBlank { "hcaptcha" }}</div>
</body>
</html>
    """.trimIndent()
}
