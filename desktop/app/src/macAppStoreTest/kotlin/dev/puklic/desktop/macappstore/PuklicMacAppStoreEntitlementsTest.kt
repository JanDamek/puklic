package dev.puklic.desktop.macappstore

import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Asserts the presence and shape of the Mac App Store entitlements plist
 * `dist/apple/macappstore/Puklic.entitlements`.
 *
 * FP-14b RED phase: the file does not exist yet — FP-14d creates it.
 * Until then, the existence assertion fails.
 *
 * Surface locked: every entitlement key documented in
 * `docs/03_infrastructure/architect-reports/2026-05-29-fp14a-mac-app-store-architect.md` §5
 * must be present with the documented boolean value.
 */
class PuklicMacAppStoreEntitlementsTest {

    private val entitlementsFile: File by lazy {
        // The test is run from the :desktop:app project dir; the entitlements
        // file lives at the repo root under dist/apple/macappstore.
        val projectDir = File(System.getProperty("user.dir"))
        // Walk up until we find the repo root (contains settings.gradle.kts).
        var dir: File? = projectDir
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        val repoRoot = dir ?: error("Could not locate repo root from $projectDir")
        File(repoRoot, "dist/apple/macappstore/Puklic.entitlements")
    }

    @Test
    fun `entitlements plist file exists at the locked path`() {
        assertTrue(
            entitlementsFile.exists(),
            "Expected $entitlementsFile to exist (FP-14d creates it).",
        )
    }

    @Test
    fun `entitlements plist declares all required keys with correct boolean values`() {
        val content = entitlementsFile.readText()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // Don't fetch the Apple DTD over the network.
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
        }
        val doc = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }.parse(InputSource(StringReader(content)))

        val dict = doc.getElementsByTagName("dict").item(0)
            ?: error("plist has no <dict> root")
        val children = (0 until dict.childNodes.length).map { dict.childNodes.item(it) }
        val keyValues = mutableMapOf<String, String>()
        var pendingKey: String? = null
        for (node in children) {
            when (node.nodeName) {
                "key" -> pendingKey = node.textContent.trim()
                "true", "false" -> {
                    val k = pendingKey ?: continue
                    keyValues[k] = node.nodeName
                    pendingKey = null
                }
            }
        }

        // Locked per FP-14a §5.
        val required = mapOf(
            "com.apple.security.app-sandbox" to "true",
            "com.apple.security.network.client" to "true",
            "com.apple.security.network.server" to "true",
            "com.apple.security.device.audio-input" to "true",
            "com.apple.security.files.user-selected.read-write" to "true",
            "com.apple.security.files.downloads.read-write" to "true",
            "com.apple.security.cs.allow-jit" to "true",
            "com.apple.security.cs.allow-unsigned-executable-memory" to "true",
        )

        for ((k, expected) in required) {
            val actual = keyValues[k]
            assertNotNull(actual, "Missing entitlement key: $k")
            assertEquals(expected, actual, "Entitlement $k has wrong value")
        }
    }
}
