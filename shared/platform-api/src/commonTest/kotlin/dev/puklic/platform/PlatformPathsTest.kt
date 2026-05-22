package dev.puklic.platform

import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

class PlatformPathsTest {

    @Test
    fun `paths are non-null and distinct directories`() {
        val paths = FakePlatformPaths()
        paths.dataDir shouldNotBe ""
        paths.cacheDir shouldNotBe ""
        paths.configDir shouldNotBe ""
        paths.crashDir shouldNotBe ""
    }

    @Test
    fun `databaseFile lives under dataDir`() {
        val paths = FakePlatformPaths()
        paths.databaseFile() shouldStartWith paths.dataDir
    }

    @Test
    fun `crashDir lives under dataDir`() {
        val paths = FakePlatformPaths()
        paths.crashDir shouldStartWith paths.dataDir
    }
}
