package dev.puklic.session.adapter

import dev.puklic.protocol.discord.rest.DiscordLoginClient
import dev.puklic.protocol.discord.rest.LoginResponse
import dev.puklic.session.CredentialsLogin
import dev.puklic.session.CredentialsLoginResult

/**
 * Adapter that maps [DiscordLoginClient] outcomes onto the [CredentialsLogin] contract used by
 * [dev.puklic.session.SessionManager]. The session layer never sees protocol-discord types
 * directly (per ADR-0006 / module boundary rules).
 */
public class DiscordCredentialsLoginAdapter(
    private val client: DiscordLoginClient,
) : CredentialsLogin {

    override suspend fun login(
        loginIdentifier: String,
        password: String,
        captchaKey: String?,
    ): CredentialsLoginResult =
        client.loginWithCredentials(loginIdentifier, password, captchaKey).toResult()

    override suspend fun completeMfa(ticket: String, code: String): CredentialsLoginResult =
        client.loginWithMfa(ticket, code).toResult()

    private fun Result<LoginResponse>.toResult(): CredentialsLoginResult = fold(
        onSuccess = { response ->
            when (response) {
                is LoginResponse.Success -> CredentialsLoginResult.Success(response.token)
                is LoginResponse.MfaRequired -> CredentialsLoginResult.MfaRequired(response.ticket)
                is LoginResponse.CaptchaRequired ->
                    CredentialsLoginResult.CaptchaRequired(sitekey = response.sitekey, service = response.service)
                is LoginResponse.Error -> CredentialsLoginResult.Error(response.message)
            }
        },
        onFailure = { cause ->
            CredentialsLoginResult.Transport(cause.message.orEmpty().ifBlank { cause::class.simpleName.orEmpty() })
        },
    )
}
