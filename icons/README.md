# Puklic — Icon Asset Pack

Complete icon set for the Puklic desktop chat client.
Master design: chat bubble with tilted "Pk" monogram referencing the
"Public → Puklic" typo origin.

## Folder structure

```
svg/                   Source SVG masters (vector, edit these)
  puklic-icon.svg        Full-color (1024×1024 viewBox)
  puklic-icon-mono.svg   Monochrome (transparent bg, for tray)

png/                   Raster exports, color, square format
  puklic-16.png  →  puklic-1024.png  (11 sizes)

tray/                  Monochrome tray icons (transparent bg)
  puklic-mono-16.png  →  puklic-mono-64.png

linux/                 hicolor-style Linux icon theme structure
  16x16/puklic.png  ...  512x512/puklic.png
  scalable.svg

windows/               Windows multi-size ICO
  puklic.ico

macos/                 macOS icon assets
  puklic.icns            Pre-built ICNS (works on Linux conversions)
  puklic.iconset/        Source iconset for `iconutil` on actual Mac
```

## Design tokens

| Property        | Value     |
|-----------------|-----------|
| Brand color     | #0EA5E9   |
| Bubble fill     | #FFFFFF   |
| Letter color    | #0EA5E9   |
| Corner radius   | 200 / 1024 (~19.5%) |
| "k" tilt        | -22°      |
| Letter font     | DejaVu Sans Bold (700) — replace with Inter/Manrope for final |

## Quick usage

### Linux desktop entry
Copy `linux/*` to `~/.local/share/icons/hicolor/` (or `/usr/share/icons/hicolor/`)
then run `gtk-update-icon-cache ~/.local/share/icons/hicolor`.

In `.desktop` file:
```
Icon=puklic
```

### Windows
Use `windows/puklic.ico` directly in your installer or executable resources.
Works in File Explorer, Start menu, taskbar, system tray.

### macOS
Two options:

1. Use the pre-built `macos/puklic.icns` directly.
2. On a real Mac, rebuild from the iconset for best Apple-spec compliance:
   ```bash
   iconutil -c icns macos/puklic.iconset -o puklic.icns
   ```
   The `iconset` from this pack is iconutil-ready.

### App bundle (macOS)
Place `puklic.icns` at `Puklic.app/Contents/Resources/puklic.icns`
and reference in `Info.plist`:
```xml
<key>CFBundleIconFile</key>
<string>puklic</string>
```

### Tray icon (cross-platform)
Use the **monochrome** version (`tray/puklic-mono-*.png` or `svg/puklic-icon-mono.svg`).
macOS and most Linux DEs recolor monochrome tray icons based on system theme.

## Regenerating from SVG

If you edit `svg/puklic-icon.svg` and want to rebuild all rasters:

```bash
# Requires librsvg2-bin (rsvg-convert), icoutils (icotool), icnsutils (png2icns)
for s in 16 24 32 48 64 96 128 192 256 512 1024; do
  rsvg-convert -w $s -h $s svg/puklic-icon.svg -o png/puklic-${s}.png
done

# Windows .ico
icotool -c -o windows/puklic.ico png/puklic-{16,24,32,48,64,128,256}.png

# macOS .icns (Linux/cross-platform tool)
png2icns macos/puklic.icns png/puklic-{16,32,48,128,256,512,1024}.png
```

## Improvements to consider before launch

1. **Outline text to paths.** The SVG uses `DejaVu Sans` because it's
   widely available. For pixel-perfect cross-platform consistency, open
   the SVG in Inkscape or Figma and convert text to vector outlines.
2. **Consider Inter or Manrope** for the final letterforms — they have
   more refined geometry than DejaVu Sans.
3. **Dark mode variant** — the current background fixed at #0EA5E9 works
   on light and dark system themes, but a desaturated dark-mode variant
   for adaptive icon themes could be added.
4. **Adaptive icons for Android** — split into foreground (Pk bubble) +
   background (#0EA5E9 layer) when you add Android target.

## License

Project asset — license matches the Puklic repo (Apache 2.0 per KB).
