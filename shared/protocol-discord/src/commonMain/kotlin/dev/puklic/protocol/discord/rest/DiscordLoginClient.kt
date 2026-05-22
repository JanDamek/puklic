package dev.puklic.protocol.discord.rest

import dev.puklic.protocol.discord.DiscordError
import dev.puklic.protocol.discord.DiscordJson
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val DEFAULT_BASE_URL = "https://discord.com/api/v10"
private const val DEFAULT_UA = "Puklic/0.1.0 (Linux; Wayland)"

/**
 * Outcome of an email/password (or username/password) login attempt against
 * `POST /api/v10/auth/login` (or its MFA completion endpoint).
 *
 * Sealed so callers can `when`-exhaustively handle every documented response shape without
 * leaking raw DTOs.
 */
public sealed interface LoginResponse {
    public data class Success(val token: String, val userId: String) : LoginResponse
    public data class CaptchaRequired(val sitekey: String, val service: String) : LoginResponse
    public data class MfaRequired(
        val ticket: String,
        val totp: Boolean,
        val sms: Boolean,
        val backup: Boolean,
    ) : LoginResponse
    public data class Error(val code: Int, val message: String) : LoginResponse
}

@Serializable
internal data class LoginRequest(
    val login: String,
    val password: String,
    val undelete: Boolean = false,
    val captcha_key: String? = null,
    val login_source: String? = null,
    val gift_code_sku_id: String? = null,
)

@Serializable
internal data class MfaRequest(
    val ticket: String,
    val code: String,
    val login_source: String? = null,
    val gift_code_sku_id: String? = null,
)

/**
 * Stateless client for Discord's login endpoint family. Does NOT require a token — it
 * is the endpoint that produces one. Password values are never logged or surfaced in
 * error strings.
 */
public class DiscordLoginClient(
    private val httpClient: HttpClient,
    private val userAgent: String = DEFAULT_UA,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    /**
     * POST /auth/login with email-or-username + password. Returns [LoginResponse].
     *
     * The HTTP layer's exceptions (network / serialization) are surfaced as
     * `Result.failure(DiscordError.*)` so the call site can distinguish protocol-level
     * errors from "bad credentials" (which is a 200 OR 400 with a structured body).
     */
    public suspend fun loginWithCredentials(
        login: String,
        password: String,
        captchaKey: String? = null,
    ): Result<LoginResponse> = perform("$baseUrl/auth/login") {
        DiscordJson.encodeToString(
            LoginRequest.serializer(),
            LoginRequest(login = login, password = password, captcha_key = captchaKey),
        )
    }

    /**
     * POST /auth/mfa/totp with the ticket from a prior [LoginResponse.MfaRequired] and the
     * user-entered 6-digit code.
     */
    public suspend fun loginWithMfa(
        ticket: String,
        code: String,
    ): Result<LoginResponse> = perform("$baseUrl/auth/mfa/totp") {
        DiscordJson.encodeToString(
            MfaRequest.serializer(),
            MfaRequest(ticket = ticket, code = code),
        )
    }

    private suspend fun perform(url: String, bodyBuilder: () -> String): Result<LoginResponse> {
        val response: HttpResponse = try {
            httpClient.post(url) {
                headers {
                    append(HttpHeaders.UserAgent, userAgent)
                    append(HttpHeaders.Accept, ContentType.Application.Json.toString())
                }
                contentType(ContentType.Application.Json)
                setBody(bodyBuilder())
            }
        } catch (cause: kotlinx.coroutines.CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            return Result.failure(DiscordError.Network(cause))
        }
        val body = response.bodyAsText()
        return try {
            Result.success(parseBody(response.status.value, body))
        } catch (cause: SerializationException) {
            Result.failure(DiscordError.Deserialization(cause, body))
        }
    }

    private fun parseBody(status: Int, body: String): LoginResponse {
        val root: JsonObject = lenient.parseToJsonElement(body).jsonObject

        // Captcha (Discord returns this on HTTP 200; presence of `captcha_key` signals required).
        if (root["captcha_key"] != null) {
            return LoginResponse.CaptchaRequired(
                sitekey = root["captcha_sitekey"]?.jsonPrimitive?.contentOrEmpty().orEmpty(),
                service = root["captcha_service"]?.jsonPrimitive?.contentOrEmpty().orEmpty(),
            )
        }

        // MFA challenge (HTTP 200, mfa=true)
        val mfaFlag = root["mfa"]?.jsonPrimitive?.booleanOrNull
        if (mfaFlag == true) {
            return LoginResponse.MfaRequired(
                ticket = root["ticket"]?.jsonPrimitive?.contentOrEmpty().orEmpty(),
                totp = root["totp"]?.jsonPrimitive?.booleanOrNull ?: false,
                sms = root["sms"]?.jsonPrimitive?.booleanOrNull ?: false,
                backup = root["backup"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }

        // Success (HTTP 200, token present)
        val token = root["token"]?.jsonPrimitive?.contentOrEmpty()
        if (status in SUCCESS_RANGE && !token.isNullOrEmpty()) {
            val userId = root["user_id"]?.jsonPrimitive?.contentOrEmpty().orEmpty()
            return LoginResponse.Success(token = token, userId = userId)
        }

        // Error: prefer structured `errors` field, fall back to `message` / `code`.
        val message = extractErrorMessage(root) ?: "Login failed"
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: status
        return LoginResponse.Error(code = code, message = message)
    }

    private fun extractErrorMessage(root: JsonObject): String? {
        val errors = root["errors"]?.jsonObject ?: return root["message"]?.jsonPrimitive?.contentOrEmpty()
        // Walk first leaf message under `errors`.
        errors.values.forEach { node ->
            val obj = node as? JsonObject ?: return@forEach
            val arr = obj["_errors"]?.jsonArray ?: return@forEach
            arr.firstOrNull()?.jsonObject?.get("message")?.jsonPrimitive?.contentOrEmpty()?.let { return it }
        }
        return root["message"]?.jsonPrimitive?.contentOrEmpty()
    }

    private fun JsonPrimitive.contentOrEmpty(): String = content

    private companion object {
        val SUCCESS_RANGE = 200..299
        val lenient: Json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
