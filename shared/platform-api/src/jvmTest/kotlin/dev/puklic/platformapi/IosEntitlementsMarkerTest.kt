package dev.puklic.platformapi

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * Integrity guard for issue #75 (iOS Associated Domains entitlement).
 *
 * The `webcredentials:discord.com` entitlement is queued in a commented-out
 * block inside `iosApp/iosApp/iosApp.entitlements` because activation requires
 * an Apple Developer Portal capability flip + a regenerated provisioning
 * profile (see docs/03_infrastructure/architect-reports/2026-06-01-ios-associated-domains.md).
 *
 * This test ensures the `TODO(#75)` marker plus the commented payload survive
 * future edits / renames so the queued work isn't accidentally lost. The test
 * skips itself on a CI checkout that does not contain the iosApp tree (e.g.
 * a Linux runner that ignores the iOS app target).
 */
class IosEntitlementsMarkerTest {

    @Test
    fun `iosApp entitlements file keeps the queued issue #75 marker`() {
        val entitlements = resolveEntitlementsPath()
        Assumptions.assumeTrue(
            Files.exists(entitlements),
            "iosApp/iosApp/iosApp.entitlements not present in this checkout — skipping",
        )

        val content = Files.readString(entitlements)
        content shouldContain "TODO(#75)"
        content shouldContain "com.apple.developer.associated-domains"
        content shouldContain "webcredentials:discord.com"
    }

    @Test
    fun `entitlement block is still commented out until portal capability is enabled`() {
        val entitlements = resolveEntitlementsPath()
        Assumptions.assumeTrue(
            Files.exists(entitlements),
            "iosApp/iosApp/iosApp.entitlements not present in this checkout — skipping",
        )

        val content = Files.readString(entitlements)
        // Block must live inside an XML comment so codesign does not require the
        // entitlement until the Developer Portal capability has been enabled and
        // the provisioning profile regenerated.
        val key = "com.apple.developer.associated-domains"
        val keyOffset = content.indexOf(key)
        (keyOffset >= 0) shouldBe true
        val openCommentBefore = content.lastIndexOf("<!--", keyOffset)
        val closeCommentBefore = content.lastIndexOf("-->", keyOffset)
        (openCommentBefore > closeCommentBefore) shouldBe true
    }

    private fun resolveEntitlementsPath(): Path {
        val repoRoot = locateRepoRoot()
        return repoRoot.resolve("iosApp/iosApp/iosApp.entitlements")
    }

    /**
     * `user.dir` points at the Gradle module dir when run via `:shared:platform-api:jvmTest`
     * and at the repo root when run from CLI. Walk upward until we find a sentinel
     * (`settings.gradle.kts`) so both work.
     */
    private fun locateRepoRoot(): Path {
        var cursor: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (cursor != null) {
            if (Files.exists(cursor.resolve("settings.gradle.kts"))) return cursor
            cursor = cursor.parent
        }
        error("Could not locate repo root from user.dir=${System.getProperty("user.dir")}")
    }
}
