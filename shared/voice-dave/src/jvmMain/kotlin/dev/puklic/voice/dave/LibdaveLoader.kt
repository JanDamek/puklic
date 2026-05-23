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
 *   `/libdave/<os>-<arch>/<libBase><ext>`
 * where:
 *   - `<os>-<arch>` is one of `linux-x86_64`, `linux-arm64`,
 *     `macos-x86_64`, `macos-arm64`, `windows-x86_64`
 *   - `<libBase>` is `libdave` on POSIX, `dave` on Windows
 *   - `<ext>` is `.so` on Linux, `.dylib` on macOS, `.dll` on Windows
 *
 * On first use we extract it to a temp file (because [System.load] requires
 * a filesystem path) and pin it for the JVM lifetime.
 *
 * Native binaries for non-default platforms are produced by the
 * `.github/workflows/build-libdave.yml` CI matrix.
 */
internal object LibdaveLoader {

    private val logger = Logger.withTag("dave.LibdaveLoader")
    private val loaded = AtomicBoolean(false)

    /** Throws [UnsatisfiedLinkError] if the current OS/arch has no bundled native lib. */
    fun load() {
        if (!loaded.compareAndSet(false, true)) return
        val osArch = detectOsArch()
        val ext = libExtension()
        val base = libBaseName()
        val resource = "/libdave/$osArch/$base$ext"
        val stream = javaClass.getResourceAsStream(resource)
            ?: throw UnsatisfiedLinkError(
                "libdave native lib for $osArch not bundled in this build " +
                    "(looked for resource $resource). DAVE is only available on " +
                    "platforms with a bundled binary; run the build-libdave CI " +
                    "workflow to produce one."
            )
        val tmp = File.createTempFile(base + "-", ext).apply { deleteOnExit() }
        stream.use { Files.copy(it, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        System.load(tmp.absolutePath)
        // Hint JNA to also look in the temp dir if INSTANCE resolution falls back to
        // its own loader. We rely on the System.load above to make symbols available.
        System.setProperty("jna.library.path", tmp.parentFile.absolutePath)
        logger.i { "libdave loaded from resource=$resource extracted=${tmp.absolutePath}" }
    }

    internal fun detectOsArch(): String {
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
            archRaw == "x86_64" || archRaw == "amd64" -> "x86_64"
            else -> error("Unsupported arch: $archRaw")
        }
        return "$os-$arch"
    }

    internal fun libExtension(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            "mac" in os || "darwin" in os -> ".dylib"
            "win" in os -> ".dll"
            else -> ".so"
        }
    }

    /** Base file name without extension: `dave` on Windows, `libdave` elsewhere. */
    internal fun libBaseName(): String {
        val os = System.getProperty("os.name").lowercase()
        return if ("win" in os) "dave" else "libdave"
    }

    /** True if a libdave resource for the current OS/arch is on the classpath. */
    fun isBundledForCurrentPlatform(): Boolean {
        val resource = "/libdave/${detectOsArch()}/${libBaseName()}${libExtension()}"
        return javaClass.getResource(resource) != null
    }
}
