# Component library

Reusable Compose components for Puklic. Lives in `:shared:compose-ui`. This section is a **draft inventory** — the final API will be settled during implementation.

No code exists yet. This section is a contract between UX and the future implementation.

## Principle

- Components are **dumb renderers** — input `state`, output events (`on*` lambdas). No internal state, no Repository access.
- `state` types live in `:shared:repositories` (or nearby). Components depend on them only in the signature, not in values.
- Composables are **adaptive-aware** via `LocalWindowSizeClass`. A component knows how to behave on Compact / Medium / Expanded.
- **No IO**, **no parsing**, **no coroutine launch** inside a component outside `LaunchedEffect`/`rememberCoroutineScope` tightly bound to the lifecycle.

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

Spec see [`02_domain/richtext-ast.md`](../02_domain/richtext-ast.md).

Consumes `MentionResolver` + `EmojiResolver` via `CompositionLocal` (`LocalMentionResolver`, `LocalEmojiResolver`).

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

States in one Composable:
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
- Skeleton rows during `Loading`

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
- Format commands apply markdown wrap around the current selection (`**text**`, `*text*`, ...)
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

Compose should not branch `when (windowSize)` in every component — the branching lives in the root scaffold.

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

No custom button components for MVP — Material 3 `Button`, `OutlinedButton`, `TextButton`, `FilledTonalButton`, `IconButton` are sufficient. A custom button will be created only when a concrete use case arises that M3 does not cover.

## Icons

`Icon` from Material 3. Material Symbols Outlined assets.

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

## Rules for new components

1. Component = pure function from state → UI
2. No dependency on a specific Repository / ViewModel
3. State types in `:shared:repositories` or `:shared:domain`
4. One Composable per file (no mega-files)
5. Preview Composable in `:shared:compose-ui` `androidMain` / `desktopMain` source set (preview infra Phase 5)
6. Unit / UI tests with mock state — Compose UI test runner
