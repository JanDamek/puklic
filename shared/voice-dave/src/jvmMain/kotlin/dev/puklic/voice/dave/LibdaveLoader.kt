package dev.puklic.voice.dave

import co.touchlab.kermit.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads the bundled libdave native library into the running JVM.
 *
 * The library file is shipped as a JAR resource under
 *   `/libdave/<os>-<arch>/libdave.{dylib,so,dll}`
 *
 * On first use we extract it to a temp file (because [System.load] requires
 * a filesystem path) and pin it for the JVM lifetime.
 *
 * Currently bundled: `macos-arm64`. Other platforms are blocked on a CI
 * build matrix that compiles libdave + its mlspp + OpenSSL deps via vcpkg;
 * see architect report 2026-05-23-dave-e2ee.md §11 / Phase 3.2 follow-ups.
 *
 * Loading via JNA happens implicitly when [LibdaveBindings.INSTANCE] is
 * first dereferenced; we call [load] eagerly to surface a clear
 * `UnsatisfiedLinkError` early rather than mid-call.
 */
internal object LibdaveLoader {

    private val logger = Logger.withTag("dave.LibdaveLoader")
    private val loaded = AtomicBoolean(false)

    /** Throws [UnsatisfiedLinkError] if the current OS/arch has no bundled native lib. */
    fun load() {
        if (!loaded.compareAndSet(false, true)) return
        val osArch = detectOsArch()
        val ext = libExtension()
        val resource = "/libdave/$osArch/libdave$ext"
        val stream = javaClass.getResourceAsStream(resource)
            ?: throw UnsatisfiedLinkError(
                "libdave native lib for $osArch not bundled in this build " +
                    "(looked for resource $resource). DAVE is only available on " +
                    "platforms with a bundled binary; see Phase 3.2 follow-ups."
            )
        val tmp = File.createTempFile("libdave-", ext).apply { deleteOnExit() }
        stream.use { Files.copy(it, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        System.load(tmp.absolutePath)
        // Hint JNA to also look in the temp dir if INSTANCE resolution falls back to
        // its own loader. We rely on the System.load above to make symbols available.
        System.setProperty("jna.library.path", tmp.parentFile.absolutePath)
        logger.i { "libdave loaded from resource=$resource extracted=${tmp.absolutePath}" }
    }

    private fun detectOsArch(): String {
        val osRaw = System.getProperty("os.name").lowercase()
        val archRaw = System.getProperty("os.arch").lowercase()
        val os = when {
            "mac" in osRaw || "darwin" in osRaw -> "macos"
            "linux" in osRaw -> "linux"
            "win" in osRaw -> "windows"
            else -> error("Unsupported OS: $osRaw")
        }
        val arch = when {
            archRaw == "aarch64" || archRaw == "arm64" -> "arm64"
            archRaw == "x86_64" || archRaw == "amd64" -> "x64"
            else -> error("Unsupported arch: $archRaw")
        }
        return "$os-$arch"
    }

    private fun libExtension(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            "mac" in os || "darwin" in os -> ".dylib"
            "win" in os -> ".dll"
            else -> ".so"
        }
    }
}
