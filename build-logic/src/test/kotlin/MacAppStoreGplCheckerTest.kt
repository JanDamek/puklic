import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MacAppStoreGplCheckerTest {
    @Test
    fun `ffmpeg-platform-gpl is forbidden`() {
        assertTrue(isForbiddenMacAppStoreArtifact("org.bytedeco", "ffmpeg-platform-gpl"))
    }

    @Test
    fun `ffmpeg macosx-arm64 GPL variant is forbidden`() {
        assertTrue(isForbiddenMacAppStoreArtifact("org.bytedeco", "ffmpeg-macosx-arm64-gpl"))
    }

    @Test
    fun `wire core-crypto jvm flavour is forbidden`() {
        assertTrue(isForbiddenMacAppStoreArtifact("com.wire", "core-crypto-jvm"))
    }

    @Test
    fun `javacpp bridge is forbidden`() {
        assertTrue(isForbiddenMacAppStoreArtifact("org.bytedeco", "javacpp"))
        assertTrue(isForbiddenMacAppStoreArtifact("org.bytedeco", "javacpp-platform"))
    }

    @Test
    fun `libdave artifact name match is case-insensitive`() {
        assertTrue(isForbiddenMacAppStoreArtifact("com.discord", "libdave-jvm"))
        assertTrue(isForbiddenMacAppStoreArtifact("com.example", "LibDave"))
    }

    @Test
    fun `x264 native bindings are forbidden`() {
        assertTrue(isForbiddenMacAppStoreArtifact("org.libx264", "x264"))
    }

    @Test
    fun `libvpx is forbidden`() {
        assertTrue(isForbiddenMacAppStoreArtifact("org.bytedeco", "libvpx"))
    }

    @Test
    fun `libpipewire is forbidden`() {
        assertTrue(isForbiddenMacAppStoreArtifact("dev.puklic", "libpipewire-jna"))
    }

    // The crucial difference vs IosGplChecker: JNA is ALLOWED here because
    // FP-14c uses it to bind libopus.dylib / VideoToolbox / Network.framework.
    @Test
    fun `jna is allowed in Mac App Store build`() {
        assertFalse(isForbiddenMacAppStoreArtifact("net.java.dev.jna", "jna"))
        assertFalse(isForbiddenMacAppStoreArtifact("net.java.dev.jna", "jna-platform"))
    }

    @Test
    fun `ktor client is allowed`() {
        assertFalse(isForbiddenMacAppStoreArtifact("io.ktor", "ktor-client-core"))
    }

    @Test
    fun `compose runtime is allowed`() {
        assertFalse(isForbiddenMacAppStoreArtifact("org.jetbrains.compose.runtime", "runtime"))
    }

    @Test
    fun `koin-core is allowed`() {
        assertFalse(isForbiddenMacAppStoreArtifact("io.insert-koin", "koin-core"))
    }

    @Test
    fun `kotlinx-coroutines is allowed`() {
        assertFalse(isForbiddenMacAppStoreArtifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core"))
    }

    @Test
    fun `voice-codec common interfaces are allowed`() {
        assertFalse(isForbiddenMacAppStoreArtifact("dev.puklic", "voice-codec"))
        assertFalse(isForbiddenMacAppStoreArtifact("dev.puklic", "voice-api"))
    }
}
