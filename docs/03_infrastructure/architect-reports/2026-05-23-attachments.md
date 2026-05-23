# 2026-05-23 — Attachments (rendering only)

## Summary

Domain `Attachment`, DTO mapper, persistence (JSON-embedded), and basic `AttachmentCard` in `MessageRow.kt` already exist. What is missing is content-type-aware rendering for video (poster + duration), bounded sizing with aspect preservation for images, a full-size image viewer `Dialog`, and wiring `onAttachmentClick` through `PlatformOpen.openUrl`. Scope is a Compose-only slice in `:shared:compose-ui` plus a tiny consumer change in `desktop/app` (Android/iOS no-op) — no domain, DTO, mapper, or schema changes.

## 1. Current state

Done:
- `Attachment` (filename, size, url, proxyUrl, contentType, width, height, durationSecs, description).
- `DiscordAttachmentDto.toDomain()` in `MessageMapper.kt`.
- Persistence: attachments serialised as JSON in `messages.attachments_json`. Round-trip tested. No schema change.
- `AttachmentList` / `AttachmentCard` in `MessageRow.kt`:
  - images via `AsyncImage(model = proxyUrl)`, `ContentScale.Fit`, `fillMaxWidth(0.7f)`
  - non-image as `Card` with "IMG/AUDIO/VIDEO/FILE" string + filename + size
  - `onAttachmentClick: (Attachment) -> Unit` callback exists
- Coil `SingletonImageLoader` with Ktor fetcher configured.
- `PlatformOpen.openUrl(url)` exists in `:shared:platform-api`.

Gaps:
1. Image preview unbounded in height — bounded max-dims preserving aspect ratio.
2. No full-size viewer (Dialog) on image click.
3. Video tile is text placeholder — needs poster + duration.
4. Generic-file click not wired to `PlatformOpen.openUrl`.
5. ASCII glyphs → Material icons.

## 2. Module touch map

**Modified**
- `shared/compose-ui/.../components/MessageRow.kt` — refactor `AttachmentCard` to dispatch by `AttachmentKind`; integrate viewer state.
- Screen consumer (`MainScreen.kt` MessagePane) — wire `onAttachmentClick = { scope.launch { platformOpen.openUrl(it.url) } }`.

**New**
- `shared/compose-ui/.../components/AttachmentRenderer.kt` — `ImageAttachment`, `VideoAttachment`, `FileAttachment` composables + `kind()` classifier.
- `shared/compose-ui/.../components/AttachmentViewerDialog.kt` — full-size modal.
- `shared/compose-ui/src/commonTest/.../AttachmentRendererTest.kt` — `kind()` classifier unit tests.

No changes to `:shared:domain`, `:shared:protocol-discord`, `:shared:persistence-*`, `:shared:repositories`, SQLDelight schema.

## 3. Classifier

```kotlin
internal enum class AttachmentKind { Image, Video, File }

internal fun Attachment.kind(): AttachmentKind = when {
    contentType?.startsWith("image/") == true -> Image
    contentType?.startsWith("video/") == true -> Video
    contentType == null -> when (filename.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "gif", "webp" -> Image
        "mp4", "webm", "mov", "mkv" -> Video
        else -> File
    }
    else -> File
}
```

Audio rendered as File v1.

## 4. Rendering

| Kind  | Render |
|-------|--------|
| Image | `AsyncImage(model = proxyUrl)` rounded `Box`, clickable → `AttachmentViewerDialog`. Dims via §5. |
| Video | `Box` of computed dims with poster `AsyncImage(model = proxyUrl)` (Discord may serve frame poster); centered play icon overlay; duration chip `m:ss`; filename below. Click → `PlatformOpen.openUrl(url)`. |
| File  | Existing `Card`, Material file icon, filename + `formatFileSize(size)`. Click → `PlatformOpen.openUrl(url)`. |

Animated GIFs (`image/gif`): rendered as image; Coil decodes animated frames.

## 5. Sizing

```kotlin
private val MaxW = 400.dp
private val MaxH = 300.dp
```
Aspect-preserve in `BoxWithConstraints`:
```kotlin
if (w0 != null && h0 != null && w0 > 0 && h0 > 0) {
    val scale = min(maxW / w0, maxH / h0).coerceAtMost(1f) // never upscale
    Modifier.size((w0 * scale).dp, (h0 * scale).dp)
} else {
    Modifier.widthIn(max = MaxW).heightIn(max = MaxH)
}
```
Replaces current `fillMaxWidth(0.7f)`.

## 6. Full-size viewer

```kotlin
@Composable
fun AttachmentViewerDialog(attachment: Attachment, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable(onClick = onDismiss)) {
            AsyncImage(
                model = attachment.url,
                contentDescription = attachment.description ?: attachment.filename,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(32.dp),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}
```
`Modifier.onPreviewKeyEvent { Key.Escape → onDismiss(); true }` for safety on Desktop.

State in image attachment composable:
```kotlin
var viewing by remember { mutableStateOf<Attachment?>(null) }
if (viewing != null) AttachmentViewerDialog(viewing!!) { viewing = null }
```
Image clicks open viewer locally; video/file clicks bubble through `onAttachmentClick`.

## 7. Platform open

`PlatformOpen.openUrl` already present. Verify Desktop actual uses `Desktop.getDesktop().browse(URI(url))`. Android/iOS no-op v1.

Wiring in `MainScreen.kt`:
```kotlin
val scope = rememberCoroutineScope()
MessageRow(
    message = msg,
    onAttachmentClick = { att -> scope.launch { platformOpen.openUrl(att.url) } },
)
```

## 8. Risks

1. **Video poster reliability** — Discord's `proxy_url` for video is the video, not a frame. v1 fallback: solid background + file icon + duration; no thumbnail fetch attempt unless `proxy_url?format=jpeg` reliably works (test in implementation).
2. **GIF memory** — large GIFs decoded by Coil may breach 300 MB target. Verify Coil cache config; consider `bitmapConfig = RGB_565` for thumbnails.
3. **proxy_url vs url** — viewer uses `url` (original res); list preview uses `proxy_url` (CDN-cached, faster).
