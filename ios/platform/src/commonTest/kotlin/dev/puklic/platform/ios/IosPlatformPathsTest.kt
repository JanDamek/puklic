package dev.puklic.platform.ios

import platform.Foundation.NSFileManager
import kotlin.test.Test
import kotlin.test.assertTrue

class IosPlatformPathsTest {

    private val paths = IosPlatformPaths()
    private val fm = NSFileManager.defaultManager

    @Test
    fun dataDirExists() {
        assertTrue(paths.dataDir.isNotEmpty(), "dataDir must not be empty")
        assertTrue(fm.fileExistsAtPath(paths.dataDir), "dataDir must exist on disk: ${paths.dataDir}")
    }

    @Test
    fun cacheDirExists() {
        assertTrue(paths.cacheDir.isNotEmpty())
        assertTrue(fm.fileExistsAtPath(paths.cacheDir))
    }

    @Test
    fun configDirExists() {
        assertTrue(paths.configDir.isNotEmpty())
        assertTrue(fm.fileExistsAtPath(paths.configDir))
    }

    @Test
    fun crashDirIsUnderDataDir() {
        assertTrue(paths.crashDir.startsWith(paths.dataDir), "crashDir ${paths.crashDir} must be under dataDir ${paths.dataDir}")
        assertTrue(fm.fileExistsAtPath(paths.crashDir))
    }

    @Test
    fun databaseFileIsUnderDataDir() {
        val db = paths.databaseFile()
        assertTrue(db.startsWith(paths.dataDir), "databaseFile $db must be under dataDir ${paths.dataDir}")
        assertTrue(db.endsWith("puklic.db"))
    }
}
