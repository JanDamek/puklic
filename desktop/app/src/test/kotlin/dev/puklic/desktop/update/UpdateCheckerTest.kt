package dev.puklic.desktop.update

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class UpdateCheckerTest {

    private val checker = UpdateChecker(HttpClient(), currentVersion = "0.1.0")

    @Test
    fun `isNewer returns true for higher minor`() {
        checker.isNewer("0.2.0", "0.1.0") shouldBe true
    }

    @Test
    fun `isNewer returns true for higher patch`() {
        checker.isNewer("0.1.1", "0.1.0") shouldBe true
    }

    @Test
    fun `isNewer returns false for equal`() {
        checker.isNewer("0.1.0", "0.1.0") shouldBe false
    }

    @Test
    fun `isNewer returns false for older`() {
        checker.isNewer("0.1.0", "0.2.0") shouldBe false
    }

    @Test
    fun `isNewer handles different segment counts`() {
        checker.isNewer("1.0", "0.9.9") shouldBe true
        checker.isNewer("0.1.0.1", "0.1.0") shouldBe true
    }

    @Test
    fun `check parses GitHub release JSON and detects newer version`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(
                    """
                    {
                      "tag_name": "v0.2.0",
                      "name": "Puklic 0.2.0",
                      "html_url": "https://github.com/JanDamek/puklic/releases/tag/v0.2.0",
                      "body": "Notes"
                    }
                    """.trimIndent(),
                ),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = HttpClient(mockEngine)
        val c = UpdateChecker(client, currentVersion = "0.1.0")
        val info = c.check()
        info shouldBe UpdateChecker.UpdateInfo(
            version = "0.2.0",
            name = "Puklic 0.2.0",
            url = "https://github.com/JanDamek/puklic/releases/tag/v0.2.0",
            body = "Notes",
        )
    }

    @Test
    fun `check returns null when latest equals current`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(
                    """{"tag_name":"v0.1.0","name":"0.1.0","html_url":"https://x","body":""}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val c = UpdateChecker(HttpClient(mockEngine), currentVersion = "0.1.0")
        c.check() shouldBe null
    }

    @Test
    fun `check swallows HTTP errors and returns null`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(content = "", status = HttpStatusCode.ServiceUnavailable)
        }
        val c = UpdateChecker(HttpClient(mockEngine), currentVersion = "0.1.0")
        c.check() shouldBe null
    }
}
