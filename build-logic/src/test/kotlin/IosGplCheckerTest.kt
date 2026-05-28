import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IosGplCheckerTest {
    @Test
    fun `ffmpeg-platform-gpl is forbidden`() {
        assertTrue(isForbiddenGplArtifact("org.bytedeco", "ffmpeg-platform-gpl"))
    }

    @Test
    fun `ffmpeg native variant with -gpl classifier is forbidden`() {
        assertTrue(isForbiddenGplArtifact("org.bytedeco", "ffmpeg-linux-x86_64-gpl"))
        assertTrue(isForbiddenGplArtifact("org.bytedeco", "ffmpeg-macosx-arm64-gpl"))
    }

    @Test
    fun `wire core-crypto jvm flavour is forbidden`() {
        assertTrue(isForbiddenGplArtifact("com.wire", "core-crypto-jvm"))
    }

    @Test
    fun `javacpp bridge is forbidden`() {
        assertTrue(isForbiddenGplArtifact("org.bytedeco", "javacpp"))
        assertTrue(isForbiddenGplArtifact("org.bytedeco", "javacpp-platform"))
    }

    @Test
    fun `jna is forbidden in iOS build`() {
        assertTrue(isForbiddenGplArtifact("net.java.dev.jna", "jna"))
        assertTrue(isForbiddenGplArtifact("net.java.dev.jna", "jna-platform"))
    }

    @Test
    fun `libdave artifact name match is case-insensitive`() {
        assertTrue(isForbiddenGplArtifact("com.discord", "libdave-jvm"))
        assertTrue(isForbiddenGplArtifact("com.example", "LibDave"))
    }

    @Test
    fun `x264 native bindings are forbidden`() {
        assertTrue(isForbiddenGplArtifact("org.libx264", "x264"))
    }

    @Test
    fun `ktor client is allowed`() {
        assertFalse(isForbiddenGplArtifact("io.ktor", "ktor-client-core"))
    }

    @Test
    fun `compose runtime is allowed`() {
        assertFalse(isForbiddenGplArtifact("org.jetbrains.compose.runtime", "runtime"))
    }

    @Test
    fun `koin-core is allowed`() {
        assertFalse(isForbiddenGplArtifact("io.insert-koin", "koin-core"))
    }

    @Test
    fun `kotlinx-coroutines is allowed`() {
        assertFalse(isForbiddenGplArtifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core"))
    }
}
