# Desktop Notifications Dispatcher — Architect Report (2026-05-24, issue #13)

## Summary

`MessageOrchestrator` consumes gateway events but has no outbound notification dispatch — that's the root cause of #13. Solution: new `NotificationDispatcher` service (session-scoped) observes `GatewayEventSource.events`, filters `MessageCreated` by self-user exclusion + DM-or-mention rule + READY `notification_settings` mute gate, calls `NotificationService.show()`. **Linux upgrades** from `notify-send` CLI to D-Bus `org.freedesktop.Notifications` via dbus-java 5.1.1 (already on classpath from voice module). macOS keeps osascript Phase 1 impl. Click-to-focus deferred to Phase 2 demand signal.

## 1. Goal & non-goals

**In (Phase 1.1):**
- Notify on incoming DM messages
- Notify on @mentions in guild channels (user, role, @everyone, @here)
- Self-user exclusion
- Respect READY `notification_settings` mute flags

**Out (Phase 2):**
- Action buttons (click-to-reply)
- Click-to-focus + channel deep-link navigation
- Avatar image preview in notification
- Custom sounds, vibration

## 2. Module touch map

| Module | Change |
|---|---|
| `:shared:repositories` | NEW NotificationDispatcher (session-scoped) |
| `:shared:platform-api` | No change — NotificationService expect already exists |
| `:desktop:platform-linux` | Upgrade from notify-send CLI → dbus-java org.freedesktop.Notifications |
| `:desktop:platform-macos` | No change — osascript Phase 1 already works |
| `:desktop:app` | Wire NotificationDispatcher into DependencyGraph session factory |

## 3. Dispatcher architecture

```
GatewayEventSource.events (SharedFlow)
  ↓
NotificationDispatcher
  ├─ filterIsInstance<MessageCreated>()
  ├─ filterNot { it.message.author.id == selfUserId() }       // no self-notify
  ├─ filter { isDmOrMentioned(it) && !isMuted(it) }
  └─ NotificationService.show(...)
       ├─ Linux: D-Bus org.freedesktop.Notifications
       └─ macOS: osascript
```

```kotlin
class NotificationDispatcher(
    private val sessionScope: CoroutineScope,
    private val gatewaySource: GatewayEventSource,
    private val selfUserIdProvider: () -> UserId?,
    private val notificationService: NotificationService,
    private val notificationSettingsProvider: () -> NotificationSettings,
) {
    init {
        gatewaySource.events
            .filterIsInstance<GatewayDomainEvent.MessageCreated>()
            .filter { shouldNotify(it.message) }
            .onEach { dispatch(it.message) }
            .launchIn(sessionScope)
    }
    
    private fun shouldNotify(msg: ChatMessage): Boolean {
        val self = selfUserIdProvider() ?: return false
        if (msg.author.id == self) return false  // own message
        if (!isDmOrMentioned(msg, self)) return false
        if (isMuted(msg)) return false
        return true
    }
    
    private fun isDmOrMentioned(msg: ChatMessage, self: UserId): Boolean =
        msg.channelKind == ChannelKind.DM
            || msg.mentions.any { it.userId == self }
            || msg.mentionEveryone
    
    private fun isMuted(msg: ChatMessage): Boolean {
        val settings = notificationSettingsProvider()
        val level = settings.levelFor(msg.guildId, msg.channelId)
        return when (level) {
            NotifyLevel.ALL -> false
            NotifyLevel.MENTIONS -> !msg.mentions.any { it.userId == selfUserIdProvider() }
            NotifyLevel.NOTHING, NotifyLevel.MUTED -> true
        }
    }
}
```

## 4. Per-platform implementation

### Linux (D-Bus via dbus-java 5.1.1)

Replace current `LinuxNotificationService` (notify-send CLI) with:
```kotlin
class LinuxNotificationService(
    private val connection: DBusConnection = DBusConnectionBuilder.forSessionBus().build(),
) : NotificationService {
    private val notifications = connection.getRemoteObject(
        "org.freedesktop.Notifications",
        "/org/freedesktop/Notifications",
        Notifications::class.java,
    )
    
    override suspend fun show(notification: Notification) {
        notifications.Notify(
            "Puklic",                                    // app_name
            0u,                                          // replaces_id
            "puklic",                                    // icon
            notification.title,                          // summary
            notification.body,                           // body
            emptyList(),                                 // actions
            mapOf("urgency" to Variant(if (notification.urgent) 2.toByte() else 1.toByte())),
            -1,                                          // timeout (default)
        )
    }
}
```

Pros: no libnotify-bin install needed; future action callbacks possible; dbus-java already on classpath.

### macOS (osascript Phase 1, unchanged)

Keep existing impl. `osascript display notification` works fine for Phase 1.1.

## 5. Click → focus + navigate (Phase 2 deferred)

Not implemented Phase 1. URL scheme `puklic://channel/{id}` would require:
- AppleScript URL handler registration (Info.plist `CFBundleURLTypes`)
- Linux .desktop file `MimeType=x-scheme-handler/puklic`
- App-side URL parser + navigation

Defer until user reports demand.

## 6. Mute respect — NotificationSettings model

Discord READY payload includes `notification_settings` and per-guild `default_message_notifications` + per-channel overrides.

```kotlin
data class NotificationSettings(
    private val perGuild: Map<GuildId?, NotifyLevel>,
    private val perChannel: Map<Pair<GuildId?, ChannelId>, NotifyLevel>,
) {
    fun levelFor(guildId: GuildId?, channelId: ChannelId): NotifyLevel =
        perChannel[guildId to channelId]
            ?: perGuild[guildId]
            ?: NotifyLevel.ALL
}

enum class NotifyLevel { ALL, MENTIONS, NOTHING, MUTED }
```

Parse on READY → store in NotificationSettingsRepository → expose via `notificationSettings: StateFlow<NotificationSettings>`.

## 7. Tests

| Test | Module |
|---|---|
| NotificationDispatcher DM filter | `:shared:repositories:commonTest` |
| NotificationDispatcher mention filter (user, @everyone, role) | `:shared:repositories:commonTest` |
| NotificationDispatcher self-user exclusion | `:shared:repositories:commonTest` |
| NotificationDispatcher mute gate (ALL/MENTIONS/NOTHING/MUTED) | `:shared:repositories:commonTest` |
| NotificationSettings.levelFor channel-override-wins | `:shared:repositories:commonTest` |
| LinuxNotificationService D-Bus binding smoke | `:desktop:platform-linux:jvmTest` (gated on Linux) |

Use FakeNotificationService + Fake GatewayEventSource.

## 8. Risks

1. **D-Bus unavailable on non-Linux** — LinuxNotificationService is only on `:desktop:platform-linux` classpath; not loaded on macOS. NotificationService factory picks per-OS. No cross-platform issue.
2. **notification_settings not yet parsed** — must extend READY DTO + GuildMapper to extract. Add domain `NotificationSettings` repository.
3. **Race: dispatch before READY** — selfUserIdProvider returns null → shouldNotify returns false. Safe default.
4. **Notification spam** — `replaces_id=0` doesn't replace; v2 could pass per-channel id for replace-in-place. Phase 1 accepts.
5. **Mentions data model** — `ChatMessage.mentions` carries user IDs. Verify also has `mentionEveryone` boolean (or parse from raw_content). If not present, extend domain.

## 9. Implementation slices

1. **NotificationSettings model + READY parse** — domain class, DTO mapper, settings repository (~3h)
2. **NotificationDispatcher** — service + tests (~4h)
3. **Linux D-Bus upgrade** — replace notify-send CLI with org.freedesktop.Notifications (~2h)
4. **Wire into DependencyGraph** — session-scoped instantiation (~1h)

Total: ~10h
