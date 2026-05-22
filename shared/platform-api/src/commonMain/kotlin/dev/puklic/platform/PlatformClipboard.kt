package dev.puklic.platform

/**
 * Clipboard with image support — Compose's built-in clipboard is text-only,
 * so we need an explicit abstraction for pasting images into chat.
 */
interface PlatformClipboard {
    suspend fun setText(text: String)
    suspend fun getText(): String?
    suspend fun setImage(bytes: ByteArray, mimeType: String)
}
