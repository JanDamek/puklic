# Glossary

Doménové pojmy Puklic + Discord. Aktualizovat při zavedení nového typu / abstrakce.

## Discord doména

| Term | Definice |
|---|---|
| **Guild** | Discord „server" — kontejner kanálů, rolí, členů. ID = snowflake. |
| **Channel** | Text / voice / category / thread / forum kanál uvnitř guildu nebo DM. |
| **DM / Group DM** | Direct message mezi 2 / více uživateli, bez guildu. |
| **User** | Discord účet. Self-user = přihlášený uživatel (`@me`). |
| **Member** | User v kontextu guildu (nickname, role, joined_at). |
| **Message** | Jedna textová zpráva s přílohami, embeds, reactions. |
| **Embed** | Strukturovaný blok pod zprávou (link preview, bot output). |
| **Attachment** | Soubor přiložený ke zprávě (image, video, file). |
| **Reaction** | Emoji reakce pod zprávou, agregovaná count + per-user list. |
| **Gateway** | Discord websocket endpoint pro real-time eventy. |
| **REST API** | Discord HTTP API v10 pro request/response operace. |
| **Snowflake** | 64-bit ID s timestamp encoded. |
| **Presence** | Online status uživatele (online / idle / dnd / offline). |
| **Typing indicator** | Ephemeral event „X píše v kanálu Y". |
| **Voice state** | Uživatel připojený do voice channelu, mute/deafen flags. |
| **DAVE** | Discord Audio/Video Encryption — E2EE voice protokol. |

## Puklic doména

| Term | Definice |
|---|---|
| **Session** | Lifecycle připojení jednoho účtu (token + gateway + state). |
| **RichTextDocument** | Parsed AST zprávy, struktura `List<RichTextBlock>`. |
| **RichTextBlock** | Block-level element: paragraph / code block / quote. |
| **RichTextNode** | Inline element: text / emoji / mention / link / inline code. |
| **MessageRepository** | Vrstva nad SQLite + RAM cache + API; vystavuje Flow streams. |
| **DiscordSession** | Náš abstraction nad gateway + REST pro jeden account. |
| **PlatformXxxService** | `expect` rozhraní v `:shared:platform-api`, `actual` per platforma. |
| **Hot/warm/cold cache** | Vrstvy message cache, viz ADR-0003. |

## Anti-glossary (pojmy, které v Puklic NEPOUŽÍVÁME)

| Term | Proč ne |
|---|---|
| **Bot** | Puklic není bot, viz CLAUDE.md scope. |
| **Self-bot** | Out of scope, zakázáno. |
| **Plugin** | Bez plugin systému (fáze 1–5). |
| **Activity / Stage / Boost** | Mimo MVP scope, viz product-vision.md anti-goals. |
