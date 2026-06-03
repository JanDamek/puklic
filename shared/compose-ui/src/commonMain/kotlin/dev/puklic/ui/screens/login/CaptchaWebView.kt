package dev.puklic.ui.screens.login

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-provided captcha widget host. Renders the captcha challenge for the given
 * [sitekey] / [service] and invokes [onSolved] with the resolved token (typically the
 * `h-captcha-response` value) so [LoginViewModel.onCaptchaSolved] can re-submit
 * `/auth/login` with `captcha_key` filled in.
 *
 * Platform actuals:
 *  - iOS: WKWebView via `UIKitView`, custom HTML containing the hCaptcha JS widget plus a
 *    `window.webkit.messageHandlers.captcha` bridge that posts the token back to Kotlin.
 *  - JVM / Android: placeholder until a desktop / Android in-app web view is wired (see
 *    the platform-specific stub for the current behaviour).
 *
 * The host is responsible for full sizing; the caller wraps it in a fixed-height container
 * (~400 dp) inside [LoginScreen].
 */
@Composable
public expect fun CaptchaWebView(
    sitekey: String,
    service: String,
    rqdata: String?,
    onSolved: (token: String) -> Unit,
    modifier: Modifier = Modifier,
)
