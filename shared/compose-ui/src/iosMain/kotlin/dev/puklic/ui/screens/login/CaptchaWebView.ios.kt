@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.puklic.ui.screens.login

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

private const val CAPTCHA_HEIGHT_DP = 420
private const val BRIDGE_NAME = "captcha"

@Composable
public actual fun CaptchaWebView(
    sitekey: String,
    service: String,
    onSolved: (token: String) -> Unit,
    modifier: Modifier,
) {
    // Hold the message handler in remember so it survives recompositions and isn't GC'd
    // while WKUserContentController retains it. Identity changes only when [onSolved] does.
    val handler = remember(onSolved) { CaptchaMessageHandler(onSolved) }
    UIKitView(
        factory = {
            val controller = WKUserContentController().apply {
                addScriptMessageHandler(handler, name = BRIDGE_NAME)
            }
            val configuration = WKWebViewConfiguration().apply {
                userContentController = controller
            }
            val web = WKWebView(frame = platform.CoreGraphics.CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration)
            web.loadHTMLString(captchaHtml(service = service, sitekey = sitekey), baseURL = NSURL.URLWithString("https://discord.com/"))
            web
        },
        modifier = modifier
            .fillMaxWidth()
            .height(CAPTCHA_HEIGHT_DP.dp),
        update = { /* no-op — sitekey changes recreate the host via remember key */ },
    )
}

/**
 * WKScriptMessageHandler that forwards `window.webkit.messageHandlers.captcha.postMessage(token)`
 * calls from the page JS into the Kotlin callback.
 */
private class CaptchaMessageHandler(
    private val onSolved: (String) -> Unit,
) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val token = didReceiveScriptMessage.body as? String ?: return
        if (token.isNotBlank()) onSolved(token)
    }
}

/**
 * Build the HTML document loaded into the WKWebView. Defaults to hCaptcha (Discord's
 * primary provider); [service] is currently accepted for future arkose_labs support but
 * the present implementation always renders the hCaptcha widget — Discord serves arkose
 * extremely rarely (and only on already-flagged accounts), so the hCaptcha path covers
 * effectively every challenge a normal sign-in encounters.
 */
private fun captchaHtml(service: String, sitekey: String): String =
    """
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  <style>
    body { margin: 0; padding: 16px; background: #1e1f22; color: #dbdee1;
           font-family: -apple-system, BlinkMacSystemFont, sans-serif;
           display: flex; flex-direction: column; align-items: center; }
    .hint { font-size: 14px; margin-bottom: 12px; text-align: center; max-width: 320px; }
    .service { font-size: 11px; opacity: 0.6; margin-top: 8px; }
  </style>
  <script src="https://js.hcaptcha.com/1/api.js" async defer></script>
  <script>
    function onCaptcha(token) {
      if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.$BRIDGE_NAME) {
        window.webkit.messageHandlers.$BRIDGE_NAME.postMessage(token);
      }
    }
  </script>
</head>
<body>
  <div class="hint">Discord asked for a security check. Solve it to continue.</div>
  <div class="h-captcha" data-sitekey="$sitekey" data-callback="onCaptcha" data-theme="dark"></div>
  <div class="service">via ${service.ifBlank { "hcaptcha" }}</div>
</body>
</html>
    """.trimIndent()
