# Puklic — Product Vision

## Tagline

> Puklic is a lightweight Kotlin Multiplatform desktop chat client focused on native UI, low memory usage, coroutine-first architecture, and first-class Linux Wayland support.

## Problém

Oficiální Discord klient je Electron aplikace s 400–800 MB RAM idle, opakovanými UI freezes, problematickou podporou Wayland a dlouhým cold startem. Na Linuxu navíc roky neřešené chyby (screenshare na Waylandu, notifikace, tray, audio routing).

Existující alternativy (Ripcord — proprietary a opuštěný, Abaddon — alpha, Webcord — pořád Electron) buď stagnují nebo neřeší jádro problému.

## Cílový uživatel

- **Linux power user** používající Wayland (GNOME/KDE/Sway/Hyprland), který chce stabilní klient s low RAM footprintem
- **Cross-platform uživatel**, který chce stejný klient na Linuxu, Androidu a iOS
- **Privacy-conscious uživatel**, který nechce mít Electron běžící celý den

Sekundárně: macOS, Windows uživatelé (Compose Desktop podporuje, ale není to primární cíl).

## Hodnotová propozice

| | Electron Discord | Puklic |
|---|---|---|
| RAM idle | 400–800 MB | < 150 MB |
| Cold start | 5–10 s | < 2 s |
| Wayland | XWayland fallback s chybami | XWayland → native plán |
| Screenshare na Wayland | broken / partial | xdg-desktop-portal + PipeWire |
| Codebase | Electron + React | Kotlin + Compose Multiplatform |
| iOS/Android | iOS oficiální, Android oficiální | sdílený codebase |

## Scope MVP (fáze 1)

Použitelný read+write klient pro běžnou komunikaci:
- Login (token paste)
- Guild + channel browser
- Text chat: read, send, edit, delete
- Basic markdown + Unicode emoji
- Lazy message loading + SQLite cache
- Settings (account, appearance, cache limity)
- Notifikace (desktop)

**Mimo MVP:** voice, video, screenshare, custom emoji, mentions resolution, link previews, reactions UI, threads, stickers, Nitro features. Tyto přicházejí v dalších fázích.

## Out of scope (permanentně)

- Automatizace uživatelského účtu
- Bot framework
- AI integrace (auto-translate, auto-summarize)
- Modifikace nebo plugin do oficiálního Discord klienta
- Self-bot funkce (bulk actions, scheduled messages)

## Success metrics

Fáze 1 ship:
- 1 týden denního používání autorem bez crash / OOM
- RAM idle < 150 MB ověřeno
- Cold start < 2 s ověřeno
- Memory leak test: 24 h běh, RAM growth < 50 MB

Fáze 2 ship:
- Plně použitelný pro text komunikaci s rich obsahem (attachmenty, reactions, mentions)

Fáze 3+ jsou nice-to-have, ne success blocker pro „daily driver" status.

## Anti-goals

- Feature parity s oficiálním klientem **není cíl**. Některé Discord featury (Activity, Stage, Boosts UI, Shop) nebudou implementovány vůbec.
- Customization framework / pluginy nejsou v plánu.
- Server-side mirror / cache proxy / bridge na jiný protokol není v plánu.
