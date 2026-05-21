# Platform abstractions (`:shared:platform-api`)

`:shared:*` modules must not know about the platform. All platform-specific code goes through `expect`/`actual` interfaces in `:shared:platform-api`. One `actual` module per platform.

## Interfaces

### `SecureStorage`

Secure storage for the Discord token and other secrets.

```kotlin
interface SecureStorage {
    suspend fun put(key: String, value: String)
    suspend fun get(key: String): String?
    suspend fun remove(key: String)
    suspend fun list(): List<String>   // keys only, not values
}
```

| Platform | Implementation |
|---|---|
| Linux | libsecret via JNA (`org.freedesktop.secrets`) |
| macOS | Keychain Services |
| Windows | Credential Manager (DPAPI) |
| Android | EncryptedSharedPreferences (Jetpack Security) + Keystore |
| iOS | Keychain |

Fallback on error (libsecret not available): explicit error → UI offers a file-based encrypted store with a user-supplied passphrase.

### `NotificationService`

```kotlin
interface NotificationService {
    suspend fun show(notification: Notification): NotificationHandle
    suspend fun cancel(handle: NotificationHandle)
    val supported: NotificationCapabilities
}

data class Notification(
    val title: String,
    val body: String,
    val iconPath: String?,
    val actions: List<NotificationAction>,
    val tag: String?,           // dedup key
    val urgent: Boolean,
)

data class NotificationAction(val id: String, val label: String)
data class NotificationCapabilities(val actions: Boolean, val images: Boolean, val markup: Boolean)
```

| Platform | Implementation |
|---|---|
| Linux | D-Bus `org.freedesktop.Notifications` |
| macOS | UserNotifications framework |
| Windows | Toast notifications (WinRT) |
| Android | NotificationManagerCompat |
| iOS | UNUserNotificationCenter |

Notification actions (Reply, Mark as read) propagated back via `Channel<NotificationActionEvent>`.

### `TrayService` (desktop only)

```kotlin
interface TrayService {
    fun setIcon(iconPath: String)
    fun setTooltip(text: String)
    fun setMenu(items: List<TrayMenuItem>)
    val clicks: SharedFlow<TrayClickEvent>
}
```

| Platform | Implementation |
|---|---|
| Linux | StatusNotifierItem (KDE/GNOME via libayatana-appindicator or D-Bus directly) |
| macOS | NSStatusItem |
| Windows | Shell_NotifyIcon |

### `AudioCaptureService` (Phase 3)

```kotlin
interface AudioCaptureService {
    suspend fun listDevices(): List<AudioDevice>
    suspend fun selectDevice(id: String)
    fun startCapture(): Flow<AudioFrame>     // PCM, configurable sample rate
    fun stopCapture()
}
```

| Platform | Implementation |
|---|---|
| Linux | PipeWire (via libpipewire JNA) |
| macOS | AVAudioEngine |
| Windows | WASAPI |
| Android | Oboe / AAudio |
| iOS | AVAudioEngine |

### `AudioPlaybackService` (Phase 3)

Symmetric to `AudioCaptureService`. Details in `voice-protocol.md` at the start of Phase 3.

### `MediaCaptureService` (Phase 4, desktop only)

Screenshare / window capture.

```kotlin
interface MediaCaptureService {
    suspend fun availableSources(): List<MediaSource>   // window / monitor / region
    suspend fun startCapture(source: MediaSource): Flow<VideoFrame>
    fun stopCapture()
}
```

| Platform | Implementation |
|---|---|
| Linux Wayland | xdg-desktop-portal RequestScreenCast → PipeWire stream |
| Linux X11 | (skip — Wayland-first) |
| macOS | ScreenCaptureKit (10.15+) |
| Windows | Windows.Graphics.Capture |

### `Notifier` (urgency / DND)

```kotlin
interface PlatformPresence {
    val systemAway: StateFlow<Boolean>     // OS idle detection
    val dndActive: StateFlow<Boolean>      // Do not disturb
}
```

Use case: automatically set Discord status to "idle" when the OS reports idle > N min.

### `PlatformPaths`

```kotlin
interface PlatformPaths {
    val dataDir: Path        // $XDG_DATA_HOME/puklic
    val cacheDir: Path       // $XDG_CACHE_HOME/puklic
    val configDir: Path      // $XDG_CONFIG_HOME/puklic
    val crashDir: Path       // dataDir/crashes
    fun databaseFile(): Path
}
```

Implementation per platform respects XDG / native conventions.

### `PlatformOpen`

Open a URL / file with an external handler.

```kotlin
interface PlatformOpen {
    suspend fun openUrl(url: String)
    suspend fun openFile(path: Path)
    suspend fun openInFolder(path: Path)
}
```

| Platform | Implementation |
|---|---|
| Linux | `xdg-open` via ProcessBuilder |
| macOS | `open` |
| Windows | `ShellExecute` |
| Android | Intent.ACTION_VIEW |
| iOS | UIApplication.openURL |

### `PlatformClipboard`

```kotlin
interface PlatformClipboard {
    suspend fun setText(text: String)
    suspend fun getText(): String?
    suspend fun setImage(bytes: ByteArray, mimeType: String)
}
```

Compose has a built-in clipboard, but text only. For pasting an image into the chat we need a platform abstraction.

### `PlatformAutoStart`

```kotlin
interface PlatformAutoStart {
    val supported: Boolean
    suspend fun isEnabled(): Boolean
    suspend fun setEnabled(enabled: Boolean)
}
```

| Platform | Implementation |
|---|---|
| Linux | `~/.config/autostart/puklic.desktop` |
| macOS | LaunchAgent plist |
| Windows | Registry `Run` key |
| Android / iOS | unsupported |

## Module wiring

```
:shared:platform-api               (expect interfaces, no impl)
   ▲
   ├── :desktop:platform-linux     (actual: libsecret, D-Bus, PipeWire, libayatana, xdg-open)
   ├── :desktop:platform-macos     (actual: Keychain, NSStatusItem, AVAudioEngine, ...)
   ├── :desktop:platform-windows   (actual: DPAPI, WinRT, WASAPI, ...)
   ├── :android:platform-android   (actual: EncryptedSharedPreferences, NotificationManagerCompat, ...)
   └── :ios:platform-ios           (actual: Keychain, UNUserNotificationCenter, AVAudioEngine, ...)
```

The application module (`:desktop:app`, `:android:app`, `:ios:app`) wires the concrete `actual` implementations via DI (Koin / manual).

## Rules

- `expect` interfaces **never** include platform-specific types (no `NSString`, `Bundle`, `java.io.File`). Only Kotlin stdlib + kotlinx libs.
- `actual` implementations may use anything platform-native, but **must** keep the same public signature.
- No platform exception types leaked through the interface — wrap them in `Platform*Exception` (sealed hierarchy in `:shared:platform-api`).
- Capabilities (`supported: Boolean`, `NotificationCapabilities`) are explicit — the UI must degrade gracefully on platforms that lack a feature.

## Test strategy

- **Unit tests:** each `actual` implementation has its own integration tests against the real API (Linux: libsecret test instance, Android: AndroidX instrumented test, etc.)
- **Shared code:** uses fake `actual` in `commonTest` (`FakeSecureStorage`, `FakeNotificationService`)
- **Capability matrix:** documentation + CI matrix showing which platform has which capability — serves as a contract
