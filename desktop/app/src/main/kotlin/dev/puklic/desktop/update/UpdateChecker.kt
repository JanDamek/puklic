package dev.puklic.desktop.update

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val LOG_TAG = "UpdateChecker"
private const val GITHUB_RELEASES_URL =
    "https://api.github.com/repos/JanDamek/puklic/releases/latest"

/**
 * Best-effort, opt-in check for newer Puklic releases on GitHub. The check is intentionally
 * stateless and side-effect free except for the HTTP call: any failure (network, parse, rate
 * limit) returns `null` and is logged at debug level. The caller decides whether to surface the
 * result to the user.
 *
 * No in-app installation is performed — the [UpdateInfo.url] points at the GitHub release page
 * which the user opens manually via [dev.puklic.platform.PlatformOpen]. See
 * `docs/07_roadmap/phases.md` -> "Update mechanism".
 */
public class UpdateChecker(
    private val httpClient: HttpClient,
    private val currentVersion: String = Version.CURRENT,
    private val releasesUrl: String = GITHUB_RELEASES_URL,
) {

    public data class UpdateInfo(
        val version: String,
        val name: String,
        val url: String,
        val body: String,
    )

    public suspend fun check(): UpdateInfo? {
        return try {
            val response: HttpResponse = httpClient.get(releasesUrl) {
                headers {
                    append("Accept", "application/vnd.github+json")
                    append("X-GitHub-Api-Version", "2022-11-28")
                }
            }
            if (!response.status.isSuccess()) {
                Logger.d(LOG_TAG) { "Update check non-success: ${response.status}" }
                return null
            }
            val obj = JSON.parseToJsonElement(response.bodyAsText()) as? JsonObject ?: return null
            val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: tag
            val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull ?: return null
            val body = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val latest = tag.removePrefix("v")
            if (isNewer(latest, currentVersion.removePrefix("v"))) {
                UpdateInfo(version = latest, name = name, url = htmlUrl, body = body)
            } else {
                null
            }
        } catch (t: Throwable) {
            Logger.d(LOG_TAG, t) { "Update check failed (swallowed)" }
            null
        }
    }

    internal fun isNewer(latest: String, current: String): Boolean = compareVersions(latest, current) > 0

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }

        fun compareVersions(a: String, b: String): Int {
            val ap = a.split(".", "-").mapNotNull { it.toIntOrNull() }
            val bp = b.split(".", "-").mapNotNull { it.toIntOrNull() }
            val size = maxOf(ap.size, bp.size)
            for (i in 0 until size) {
                val ai = ap.getOrElse(i) { 0 }
                val bi = bp.getOrElse(i) { 0 }
                if (ai != bi) return ai.compareTo(bi)
            }
            return 0
        }
    }
}
