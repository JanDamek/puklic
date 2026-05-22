package dev.puklic.platform

/**
 * In-memory fakes used by `commonTest` for any module that depends on `:shared:platform-api`.
 * Kept inside `commonTest` so they ship with the test classpath only.
 */

class FakeSecureStorage : SecureStorage {
    private val store = mutableMapOf<String, String>()

    override suspend fun put(key: String, value: String) {
        store[key] = value
    }

    override suspend fun get(key: String): String? = store[key]

    override suspend fun remove(key: String) {
        store.remove(key)
    }

    override suspend fun list(): kotlin.collections.List<String> = store.keys.toList()
}

class FakeNotificationService(
    override val supported: NotificationCapabilities = NotificationCapabilities(
        actions = true,
        images = true,
        markup = false,
    ),
) : NotificationService {

    val shown = mutableListOf<Pair<NotificationHandle, Notification>>()
    val cancelled = mutableListOf<NotificationHandle>()
    private var nextId = 0

    override suspend fun show(notification: Notification): NotificationHandle {
        val handle = NotificationHandle("fake-${nextId++}")
        shown += handle to notification
        return handle
    }

    override suspend fun cancel(handle: NotificationHandle) {
        cancelled += handle
    }
}

class FakePlatformPaths(
    private val root: String = "/tmp/puklic",
) : PlatformPaths {
    override val dataDir: Path = "$root/data"
    override val cacheDir: Path = "$root/cache"
    override val configDir: Path = "$root/config"
    override val crashDir: Path = "$dataDir/crashes"
    override fun databaseFile(): Path = "$dataDir/puklic.db"
}

class FakeClipboard : PlatformClipboard {
    private var text: String? = null
    private var image: Pair<ByteArray, String>? = null

    override suspend fun setText(text: String) {
        this.text = text
    }

    override suspend fun getText(): String? = text

    override suspend fun setImage(bytes: ByteArray, mimeType: String) {
        image = bytes to mimeType
    }

    fun lastImage(): Pair<ByteArray, String>? = image
}
