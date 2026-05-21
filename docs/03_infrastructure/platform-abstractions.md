# Platform abstractions (`:shared:platform-api`)

`:shared:*` moduly nesmí znát platformu. Veškerý platform-specific kód jde přes `expect`/`actual` rozhraní v `:shared:platform-api`. Per platforma jeden `actual` modul.

## Rozhraní

### `SecureStorage`

Bezpečné úložiště pro Discord token, případně další tajemství.

```kotlin
interface SecureStorage {
    suspend fun put(key: String, value: String)
    suspend fun get(key: String): String?
    suspend fun remove(key: String)
    suspend fun list(): List<String>   // jen klíče, ne hodnoty
}
```

| Platforma | Implementace |
|---|---|
| Linux | libsecret přes JNA (`org.freedesktop.secrets`) |
| macOS | Keychain Services |
| Windows | Credential Manager (DPAPI) |
| Android | EncryptedSharedPreferences (Jetpack Security) + Keystore |
| iOS | Keychain |

Fallback při chybě (libsecret neexistuje): explicit error → UI nabídne file-based encrypted store s user-supplied passphrase.

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
    val tag: String?,           // dedup klíč
    val urgent: Boolean,
)

data class NotificationAction(val id: String, val label: String)
data class NotificationCapabilities(val actions: Boolean, val images: Boolean, val markup: Boolean)
```

| Platforma | Implementace |
|---|---|
| Linux | D-Bus `org.freedesktop.Notifications` |
| macOS | UserNotifications framework |
| Windows | Toast notifications (WinRT) |
| Android | NotificationManagerCompat |
| iOS | UNUserNotificationCenter |

Akce na notifikaci (Reply, Mark as read) propagované zpět přes `Channel<NotificationActionEvent>`.

### `TrayService` (desktop only)

```kotlin
interface TrayService {
    fun setIcon(iconPath: String)
    fun setTooltip(text: String)
    fun setMenu(items: List<TrayMenuItem>)
    val clicks: SharedFlow<TrayClickEvent>
}
```

| Platforma | Implementace |
|---|---|
| Linux | StatusNotifierItem (KDE/GNOME via libayatana-appindicator nebo D-Bus přímo) |
| macOS | NSStatusItem |
| Windows | Shell_NotifyIcon |

### `AudioCaptureService` (fáze 3)

```kotlin
interface AudioCaptureService {
    suspend fun listDevices(): List<AudioDevice>
    suspend fun selectDevice(id: String)
    fun startCapture(): Flow<AudioFrame>     // PCM, configurable sample rate
    fun stopCapture()
}
```

| Platforma | Implementace |
|---|---|
| Linux | PipeWire (přes libpipewire JNA) |
| macOS | AVAudioEngine |
| Windows | WASAPI |
| Android | Oboe / AAudio |
| iOS | AVAudioEngine |

### `AudioPlaybackService` (fáze 3)

Symetrická k `AudioCaptureService`. Detail v `voice-protocol.md` při startu fáze 3.

### `MediaCaptureService` (fáze 4, desktop only)

Screenshare / window capture.

```kotlin
interface MediaCaptureService {
    suspend fun availableSources(): List<MediaSource>   // window / monitor / region
    suspend fun startCapture(source: MediaSource): Flow<VideoFrame>
    fun stopCapture()
}
```

| Platforma | Implementace |
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

Use case: auto-set Discord status na „idle" když OS hlásí idle > N min.

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

Implementace per platforma respektuje XDG / native conventions.

### `PlatformOpen`

Otevření URL / souboru externím handlerem.

```kotlin
interface PlatformOpen {
    suspend fun openUrl(url: String)
    suspend fun openFile(path: Path)
    suspend fun openInFolder(path: Path)
}
```

| Platforma | Implementace |
|---|---|
| Linux | `xdg-open` přes ProcessBuilder |
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

Compose má built-in clipboard, ale jen text. Pro paste obrázku do chatu potřebujeme platform abstraction.

### `PlatformAutoStart`

```kotlin
interface PlatformAutoStart {
    val supported: Boolean
    suspend fun isEnabled(): Boolean
    suspend fun setEnabled(enabled: Boolean)
}
```

| Platforma | Implementace |
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

Application module (`:desktop:app`, `:android:app`, `:ios:app`) wiruje přes DI (Koin / manual) konkrétní `actual` implementace.

## Pravidla

- `expect` interface **nikdy** nezahrnuje platform-specific typy (žádný `NSString`, `Bundle`, `java.io.File`). Jen Kotlin stdlib + kotlinx libs.
- `actual` může používat cokoli platform-native, ale **musí** stejnou public signature.
- Žádný leak platform exception type přes interface — wrappuj do `Platform*Exception` (sealed hierarchy v `:shared:platform-api`).
- Capabilities (`supported: Boolean`, `NotificationCapabilities`) explicit — UI musí gracefully degradovat na platformách bez featury.

## Test strategie

- **Unit testy:** každý `actual` implementace má vlastní integraci testy proti reálnému API (Linux: libsecret test instance, Android: AndroidX instrumented test, atd.)
- **Shared kód:** používá fake `actual` v `commonTest` (`FakeSecureStorage`, `FakeNotificationService`)
- **Capability matrix:** dokumentace + CI matrix který platforma má kterou capability — slouží jako kontrakt
