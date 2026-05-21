# Component library

Reusable Compose komponenty Puklic. Bydlí v `:shared:compose-ui`. Tato sekce je **draft inventory** — finální API se uzavře při implementaci.

Žádný kód neexistuje. Tahle sekce je kontrakt mezi UX a budoucí implementací.

## Princip

- Komponenty jsou **dumb renderers** — vstup `state`, výstup events (`on*` lambdas). Žádný state inside, žádný Repository access.
- `state` typy bydlí v `:shared:repositories` (nebo blízko). Komponenty jsou na nich nezávislé jen v signature, ne v hodnotách.
- Composables jsou **adaptive-aware** přes `LocalWindowSizeClass`. Komponenta ví, jak se chovat na Compact / Medium / Expanded.
- **Žádný IO**, **žádný parsing**, **žádný launch coroutine** uvnitř komponenty mimo `LaunchedEffect`/`rememberCoroutineScope` přesně-vázané na lifecycle.

## Theme primitives

```kotlin
@Composable
fun PuklicTheme(content: @Composable () -> Unit)

// Access:
LocalPuklicColors.current.mention
LocalPuklicSpacing.current.compactPadding
MaterialTheme.colorScheme.primary
```

## Avatar

```kotlin
@Composable
fun PuklicAvatar(
    user: UserSummary,
    size: Dp = 32.dp,
    showPresence: Boolean = false,
    presence: PresenceState? = null,
    modifier: Modifier = Modifier,
)
```

- Circle shape (always)
- Load via Coil with `user.avatarHash` → Discord CDN URL
- Fallback: first char of `globalName ?: username` on `surfaceVariant`
- Presence dot bottom-right if `showPresence` and `presence != null`

## RichTextView

```kotlin
@Composable
fun RichTextView(
    document: RichTextDocument,
    onLinkClick: (String) -> Unit,
    onMentionClick: (MentionTarget) -> Unit,
    modifier: Modifier = Modifier,
)
```

Spec viz [`02_domain/richtext-ast.md`](../02_domain/richtext-ast.md).

Konzumuje `MentionResolver` + `EmojiResolver` přes `CompositionLocal` (`LocalMentionResolver`, `LocalEmojiResolver`).

## MessageRow

```kotlin
@Composable
fun MessageRow(
    message: ChatMessage,
    groupedWithPrevious: Boolean,    // hide avatar/header if true
    deliveryState: MessageDeliveryState,
    isMentionedUser: Boolean,
    onReact: (EmojiRef) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopyLink: () -> Unit,
    onAuthorClick: (UserSummary) -> Unit,
    modifier: Modifier = Modifier,
)
```

States v jednom Composable:
- Normal
- Grouped (suppressed header)
- Sending / Failed (delivery state)
- Mention highlight (left border + tint)
- Hover (revealed action bar)

## MessageList

```kotlin
@Composable
fun MessageList(
    state: MessageListState,
    onLoadOlder: () -> Unit,
    onMessageAction: (MessageAction) -> Unit,
    modifier: Modifier = Modifier,
)

sealed interface MessageListState {
    data object Loading : MessageListState
    data class Loaded(
        val messages: List<ChatMessage>,
        val isLoadingOlder: Boolean,
        val hasMoreOlder: Boolean,
        val loadOlderError: String?,
    ) : MessageListState
    data object Empty : MessageListState
    data class Error(val message: String) : MessageListState
}
```

- `LazyColumn` reverse layout (newest at bottom)
- Auto-scroll to bottom on new message if user is near bottom
- Pull-to-load-older near top edge (scroll up triggers `onLoadOlder`)
- Skeleton rows při `Loading`

## Composer

```kotlin
@Composable
fun Composer(
    draft: String,
    placeholder: String,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onFormat: (FormatCommand) -> Unit,    // bold, italic, ...
    onAttachClick: () -> Unit,
    onEmojiClick: () -> Unit,
    isEnabled: Boolean = true,
    modifier: Modifier = Modifier,
)

enum class FormatCommand { BOLD, ITALIC, STRIKETHROUGH, INLINE_CODE, CODE_BLOCK, LINK, SPOILER }
```

- Auto-grow textfield 1–10 lines
- Toolbar above input
- Format commands aplikují markdown wrap kolem aktuální selection (`**text**`, `*text*`, ...)
- Enter submit / Shift+Enter newline / Ctrl+Enter submit (multi-line variant)
- Draft persistence handled by ViewModel, not component

## ChannelListItem

```kotlin
@Composable
fun ChannelListItem(
    channel: Channel,
    isSelected: Boolean,
    isMuted: Boolean,
    unreadCount: Int,
    mentionCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

## CategoryHeader

```kotlin
@Composable
fun CategoryHeader(
    label: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
)
```

## GuildRailItem

```kotlin
@Composable
fun GuildRailItem(
    guild: Guild,
    isSelected: Boolean,
    hasUnread: Boolean,
    mentionCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

## Scaffolds

```kotlin
@Composable fun ExpandedScaffold(navState: NavigationState, ...)
@Composable fun MediumScaffold(navState: NavigationState, ...)
@Composable fun CompactScaffold(navState: NavigationState, ...)
```

Compose by se nemělo větvit `when (windowSize)` v každé komponentě — větvení žije v root scaffoldu.

## SettingsOverlay

```kotlin
@Composable
fun SettingsOverlay(
    isOpen: Boolean,
    selectedCategory: SettingsCategory,
    onCategorySelect: (SettingsCategory) -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
)

enum class SettingsCategory { ACCOUNT, APPEARANCE, NOTIFICATIONS, STORAGE, KEYBINDINGS, ABOUT }
```

## CommandPalette

```kotlin
@Composable
fun CommandPalette(
    isOpen: Boolean,
    state: CommandPaletteState,
    onQueryChange: (String) -> Unit,
    onSelect: (CommandPaletteResult) -> Unit,
    onDismiss: () -> Unit,
)

data class CommandPaletteState(
    val query: String,
    val results: List<CommandPaletteResult>,
    val selectedIndex: Int,
)

sealed interface CommandPaletteResult {
    data class Channel(val channel: dev.puklic.domain.Channel) : CommandPaletteResult
    data class User(val user: UserSummary) : CommandPaletteResult
    data class Guild(val guild: dev.puklic.domain.Guild) : CommandPaletteResult
    data class Command(val id: String, val label: String, val shortcut: KeyShortcut?) : CommandPaletteResult
}
```

## ConnectionStatusBanner

```kotlin
@Composable
fun ConnectionStatusBanner(
    state: ConnectionState,
    onRetry: () -> Unit,
    onSignInAgain: () -> Unit,
)

sealed interface ConnectionState {
    data object Connected : ConnectionState
    data object Connecting : ConnectionState
    data class Reconnecting(val secondsUntilRetry: Int) : ConnectionState
    data object Offline : ConnectionState
    data object TokenExpired : ConnectionState
}
```

Renders above MessagePane. Hidden when `Connected`.

## EmptyState

```kotlin
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    action: EmptyStateAction? = null,
)

data class EmptyStateAction(val label: String, val onClick: () -> Unit)
```

Centered in pane, used per [`screens.md`](screens.md) empty states list.

## LoadingSkeleton

```kotlin
@Composable
fun MessageRowSkeleton(modifier: Modifier = Modifier)
@Composable
fun ChannelListSkeleton(modifier: Modifier = Modifier)
```

Shimmer animation, base color `surfaceVariant`, highlight `surfaceContainer`.

## Buttons (M3 wrappers)

Žádné vlastní button komponenty pro MVP — Material 3 `Button`, `OutlinedButton`, `TextButton`, `FilledTonalButton`, `IconButton` jsou dostatečné. Custom button vznikne až bude konkrétní use case, který M3 nepokrývá.

## Icons

`Icon` z Material 3. Material Symbols Outlined assets.

```kotlin
Icon(
    imageVector = Icons.Outlined.Settings,
    contentDescription = "Settings",
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
)
```

## Modifier extensions

```kotlin
fun Modifier.puklicHoverable(): Modifier  // unified hover/press feedback
fun Modifier.puklicFocusable(): Modifier  // keyboard focus ring
```

## Pravidla pro nové komponenty

1. Komponenta = pure function ze state → UI
2. Žádná závislost na konkrétní Repository / ViewModel
3. State typy v `:shared:repositories` nebo `:shared:domain`
4. Per komponentu jeden Composable v souboru (ne mega-files)
5. Preview Composable v `:shared:compose-ui` `androidMain` / `desktopMain` source set (preview infra fáze 5)
6. Unit / UI testy s mock state — Compose UI test runner
