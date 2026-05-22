package dev.puklic.platform.android

import dev.puklic.platform.PlatformClipboard

/** Phase 1 stub. Phase 2 will use android.content.ClipboardManager. */
class AndroidPlatformClipboard : PlatformClipboard {
    override suspend fun setText(text: String): Unit = throw NotImplementedError(PHASE_2)
    override suspend fun getText(): String? = throw NotImplementedError(PHASE_2)
    override suspend fun setImage(bytes: ByteArray, mimeType: String): Unit = throw NotImplementedError(PHASE_2)

    private companion object {
        const val PHASE_2 = "AndroidPlatformClipboard: Phase 2"
    }
}
