package dev.puklic.ui.screens.login

/** Name of the script bridge object the platform web view exposes to the captcha page. */
internal const val CAPTCHA_BRIDGE_NAME = "captcha"

/** Prefix of bridge messages that carry a widget error code instead of a solved token. */
internal const val CAPTCHA_ERROR_PREFIX = "__error:"

private const val HCAPTCHA_API_URL = "https://js.hcaptcha.com/1/api.js?render=explicit&onload=onHcaptchaLoad"

/**
 * Builds the HTML document hosted by the platform web view. Defaults to hCaptcha (Discord's
 * primary provider); [service] is only shown as a caption, Discord serves arkose extremely rarely
 * and the hCaptcha path covers effectively every challenge a normal sign-in encounters.
 *
 * hCaptcha Enterprise: Discord's rqdata is an enterprise-only parameter that is NOT supported via
 * the implicit `data-rqdata` attribute, it must be supplied to `hcaptcha.render(node, { rqdata })`
 * in an explicit render. Without it the user solves a non-enterprise challenge that Discord's
 * enterprise endpoint rejects and hCaptcha keeps re-issuing the puzzle.
 *
 * The page reports back through whichever bridge the host provides: the WKWebView message handler
 * on iOS or the `addJavascriptInterface` object on Android, both named [CAPTCHA_BRIDGE_NAME].
 */
internal fun captchaHtml(service: String, sitekey: String, rqdata: String?): String {
    val rqdataLine = rqdata?.takeIf { it.isNotBlank() }?.let { "\n        rqdata: ${it.asJsString()}," }.orEmpty()
    return """
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  <style>
    body { margin: 0; padding: 8px; background: #1e1f22; color: #dbdee1;
           font-family: -apple-system, BlinkMacSystemFont, Roboto, sans-serif;
           display: flex; flex-direction: column; align-items: center; }
    .service { font-size: 11px; opacity: 0.6; margin-top: 8px; }
    #h-captcha { margin-top: 4px; }
  </style>
  <script>
    function postToHost(message) {
      var webkit = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.$CAPTCHA_BRIDGE_NAME;
      if (webkit) { webkit.postMessage(message); }
      else if (window.$CAPTCHA_BRIDGE_NAME) { window.$CAPTCHA_BRIDGE_NAME.postMessage(message); }
    }
    function onCaptcha(token) { postToHost(token); }
    function onCaptchaError(err) { postToHost('$CAPTCHA_ERROR_PREFIX' + err); }
    function onHcaptchaLoad() {
      hcaptcha.render("h-captcha", {
        sitekey: ${sitekey.asJsString()},$rqdataLine
        theme: "dark",
        callback: onCaptcha,
        "error-callback": onCaptchaError,
        "chalexpired-callback": onCaptchaError,
        "expired-callback": onCaptchaError
      });
    }
  </script>
  <script src="$HCAPTCHA_API_URL" async defer></script>
</head>
<body>
  <div id="h-captcha"></div>
  <div class="service">via ${service.ifBlank { "hcaptcha" }.escapeHtml()}</div>
</body>
</html>
    """.trimIndent()
}

private fun String.asJsString(): String = buildString {
    append('"')
    for (char in this@asJsString) {
        when (char) {
            '"', '\\' -> append('\\').append(char)
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '<' -> append("\\u003c")
            else -> append(char)
        }
    }
    append('"')
}

private fun String.escapeHtml(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
