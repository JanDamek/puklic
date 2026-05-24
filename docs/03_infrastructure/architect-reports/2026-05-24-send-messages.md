# Send Messages — Architect Report (2026-05-24, issue #12)

## Summary

Issue #12's pipeline is 95% built: `MessageListViewModel.sendMessage` → `MessageOrchestrator.send` → `OutboundQueue` → `OutboundMessageWorker` → REST gateway is fully implemented, and MainScreen.kt:497-508 supplies a correct trim-send-clear `onSubmit` lambda. The single bug is in `Composer.kt:37` — `onSubmit` is captured then thrown away under `@Suppress("UNUSED_EXPRESSION")`. Fix: (a) `Modifier.onPreviewKeyEvent` translating Enter/Ctrl+Enter/Cmd+Enter → send, Shift+Enter → fall-through newline, Escape → focus clear; (b) trailing `IconButton(Icons.AutoMirrored.Outlined.Send)` enabled iff `draft.isNotBlank()`. Decision logic extracted to pure `composerKeyAction(...)` for unit testing without Compose UI runtime. Composer signature unchanged. Out of v1: format toolbar, attachments wiring, optimistic rendering of OutboundQueue pending messages (follow-up).

## 1. Goal + non-goals

**In:** Wire existing `Composer.onSubmit`. Bare Enter / Ctrl+Enter / Cmd+Enter send. Shift+Enter newline. Escape blur. Trailing Send IconButton (visible, disabled when blank).

**Out (follow-ups):**
- Format toolbar (B/I/S̲/code/link/emoji) — separate scope
- Attachment picker — separate scope
- Optimistic UI rendering of `OutboundQueue.observePending()` pending messages
- IME composition (CJK) detection for bare Enter

## 2. Module touch map

| Action | File | Notes |
|---|---|---|
| Modify | `shared/compose-ui/src/commonMain/kotlin/dev/puklic/ui/components/Composer.kt` | Remove `@Suppress("UNUSED_EXPRESSION") onSubmit`; wrap OutlinedTextField in Row with Send IconButton; attach onPreviewKeyEvent |
| Add | `shared/compose-ui/src/commonTest/kotlin/dev/puklic/ui/components/ComposerKeyHandlerTest.kt` | Pure-function tests of decision logic |
| Update doc | `docs/04_ui/screens.md` §Composer | Mention trailing send button |
| **Don't touch** | MainScreen.kt, MessageListViewModel, MessageOrchestrator, OutboundMessageWorker, OutboundQueue | Pipeline already complete end-to-end |

## 3. Key handler logic (extracted for testability)

```kotlin
internal enum class ComposerAction { Send, InsertNewline, Blur, Ignored }

internal fun composerKeyAction(
    keyDown: Boolean,
    isEnter: Boolean,
    isEscape: Boolean,
    isShift: Boolean,
    isCtrl: Boolean,
    isMeta: Boolean,
): ComposerAction = when {
    !keyDown -> ComposerAction.Ignored
    isEscape -> ComposerAction.Blur
    isEnter && isShift -> ComposerAction.InsertNewline
    isEnter -> ComposerAction.Send  // bare Enter, Ctrl+Enter, Cmd+Enter all send
    else -> ComposerAction.Ignored
}

private fun Modifier.onComposerKeyEvent(
    onSend: () -> Unit,
    onBlur: () -> Unit,
): Modifier = onPreviewKeyEvent { e ->
    val action = composerKeyAction(
        keyDown = e.type == KeyEventType.KeyDown,
        isEnter = e.key == Key.Enter || e.key == Key.NumPadEnter,
        isEscape = e.key == Key.Escape,
        isShift = e.isShiftPressed,
        isCtrl = e.isCtrlPressed,
        isMeta = e.isMetaPressed,
    )
    when (action) {
        ComposerAction.Send -> { onSend(); true }
        ComposerAction.Blur -> { onBlur(); true }
        ComposerAction.InsertNewline, ComposerAction.Ignored -> false
    }
}
```

## 4. Composer signature (UNCHANGED — backward compatible)

```kotlin
@Composable
public fun Composer(
    draft: String,
    placeholder: String,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onFormat: (FormatCommand) -> Unit = {},
    onAttachClick: () -> Unit = {},
    onEmojiClick: () -> Unit = {},
    isEnabled: Boolean = true,
    modifier: Modifier = Modifier,
)
```

Internal body:
```kotlin
val canSend = draft.isNotBlank() && isEnabled
val attemptSend = { if (canSend) onSubmit() }
val focus = LocalFocusManager.current
Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().padding(spacing.space4)) {
    OutlinedTextField(
        value = draft,
        onValueChange = onDraftChange,
        placeholder = { Text(placeholder) },
        enabled = isEnabled,
        modifier = Modifier.weight(1f).onComposerKeyEvent(
            onSend = attemptSend,
            onBlur = { focus.clearFocus() },
        ),
    )
    IconButton(onClick = attemptSend, enabled = canSend) {
        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send")
    }
}
```

## 5. Send button decision

Add it. Issue #12 acceptance explicitly requires it + Discord/Slack pattern is visible+disabled. Update docs/04_ui/screens.md §Composer.

## 6. Optimistic UI — DEFERRED

`MessageOrchestrator.observePending`-style observation NOT wired into MessageListViewModel. v1: "fire and forget from UI POV" — inbound WS echo renders the message on success. Follow-up: "Render OutboundQueue pending messages with opacity + retry indicator."

## 7. Test plan (Step 5)

`ComposerKeyHandlerTest.kt` — commonTest, pure logic (no Compose UI runtime):

| Case | keyDown | enter | esc | shift | ctrl | meta | expected |
|---|---|---|---|---|---|---|---|
| bare Enter | t | t | f | f | f | f | Send |
| Shift+Enter | t | t | f | t | f | f | InsertNewline |
| Ctrl+Enter | t | t | f | f | t | f | Send |
| Cmd+Enter (Mac) | t | t | f | f | f | t | Send |
| Ctrl+Shift+Enter | t | t | f | t | t | f | InsertNewline (shift wins) |
| Escape | t | f | t | f | f | f | Blur |
| Enter KeyUp | f | t | f | f | f | f | Ignored |
| Other key | t | f | f | f | f | f | Ignored |

Manual smoke (Step 6 verify): type in #general, press Enter → sent, draft cleared. Shift+Enter → newline. Click Send when blank → no-op (disabled).

## 8. Risks (revised per Step 3 critic)

1. **IME composition + bare Enter (CJK)** — Pinyin/kana commit on Enter; sends mid-composition. Defer; document.
2. **Czech/Slovak diacritics dead-key** — commit via dead-key char, NOT Enter. **Unaffected.**
3. **macOS Cmd modifier** — Compose Multiplatform maps Cmd → `isMetaPressed` on Darwin; verify on Mac Step 6.
4. **`onPreviewKeyEvent` intercept order** — Required (not `onKeyEvent`) to intercept before TextField's Enter.
5. **NumpadEnter** — Explicitly included.

## 8.1 Send button vertical alignment fix (critic #6)

Use `Alignment.Bottom` on the Row + IconButton aligns to last line of auto-grow textarea — matches Discord/Slack visual:
```kotlin
Row(verticalAlignment = Alignment.Bottom, ...) { ... }
```

## 8.2 Material icon availability (critic #7)

Use `Icons.AutoMirrored.Outlined.Send` (in `material-icons-extended` ≥1.6, project uses 1.8). Fallback `Icons.Outlined.Send` (direction-neutral paper-plane) if extended package not on classpath.

## 8.3 Drop dead Ctrl/Meta params (critic dead params)

`composerKeyAction` keeps `isCtrl`, `isMeta` only IF needed for future modifier shortcuts. For v1, signature simplified:
```kotlin
internal fun composerKeyAction(
    keyDown: Boolean, isEnter: Boolean, isEscape: Boolean, isShift: Boolean,
): ComposerAction
```
(Removed unused isCtrl, isMeta — bare Enter sends regardless of those modifiers.)

## 9. Open questions

- Send button trailing in-row (proposed) vs floating?
- `isNotBlank` vs `trim().isNotEmpty()` for enable? Equivalent — pick `isNotBlank`.
- Disconnected-WS UX in input — out of scope (gateway status indicator).
