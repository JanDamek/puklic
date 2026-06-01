package dev.puklic.session

/**
 * Outcome of a credentials-based sign-in (email-or-username + password), plus the optional
 * MFA-completion step that follows when Discord requires it.
 */
public sealed interface LoginOutcome {
    public data object Success : LoginOutcome
    public data class MfaRequired(val ticket: String) : LoginOutcome
    /**
     * Discord challenged the sign-in with a captcha. [sitekey] is the hCaptcha (or other
     * provider) site key required to render the widget; [service] identifies the provider
     * ("hcaptcha" / "arkose_labs" / …) so the UI can pick the right embed.
     */
    public data class CaptchaRequired(val sitekey: String, val service: String) : LoginOutcome
}

/**
 * Result of contacting Discord's `/auth/login` endpoint, exposed to [SessionManager] without
 * leaking protocol-discord DTOs. Implementations live in `:desktop:app` / wiring layer.
 */
public sealed interface CredentialsLoginResult {
    public data class Success(val token: String) : CredentialsLoginResult
    public data class MfaRequired(val ticket: String) : CredentialsLoginResult
    public data class CaptchaRequired(val sitekey: String, val service: String) : CredentialsLoginResult
    public data class Error(val message: String) : CredentialsLoginResult
    public data class Transport(val message: String) : CredentialsLoginResult
}

/**
 * Collaborator that performs the actual HTTP calls to Discord's auth endpoints.
 *
 * Stages:
 *  - [login] — email/username + password. Pass [captchaKey] when re-submitting after the
 *    user has solved a captcha challenge (Discord returns [CredentialsLoginResult.CaptchaRequired]
 *    on the first attempt with `captchaKey == null`).
 *  - [completeMfa] — second-factor (TOTP) code with the ticket from a prior `MfaRequired`.
 */
public interface CredentialsLogin {
    public suspend fun login(
        loginIdentifier: String,
        password: String,
        captchaKey: String? = null,
    ): CredentialsLoginResult

    public suspend fun completeMfa(ticket: String, code: String): CredentialsLoginResult
}
