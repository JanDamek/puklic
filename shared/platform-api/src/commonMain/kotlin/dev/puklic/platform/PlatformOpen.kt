package dev.puklic.platform

/** Hands off a URL / file / folder to the platform's default handler. */
interface PlatformOpen {
    suspend fun openUrl(url: String)
    suspend fun openFile(path: Path)
    suspend fun openInFolder(path: Path)
}
