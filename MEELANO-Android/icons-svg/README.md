# Meelano SVG icons

All icons in the app come from these SVG files (Material Symbols **Rounded**, weight 400,
from the npm package `@material-symbols/svg-400` v0.47.5, licence **Apache-2.0**).

* `*.svg` — source of truth (960×960 grid, `viewBox="0 -960 960 960"`).
* `../tools/icons.txt` — the list of icons in use.
* `../tools/svg_to_vector.py` — converts every SVG here into an Android vector drawable
  `app/src/main/res/drawable/mi_<name>.xml` (the generated files must not be edited by hand).

Icons are monochrome: the app tints them at runtime with the current theme colour, so a single
file works for all 9 themes, light and dark.

## Adding an icon

```bash
curl -sSL -o /tmp/ms.tgz https://registry.npmjs.org/@material-symbols/svg-400/-/svg-400-0.47.5.tgz
mkdir -p /tmp/ms && tar xzf /tmp/ms.tgz -C /tmp/ms
echo "new_icon_name" >> tools/icons.txt
python3 tools/svg_to_vector.py --fetch /tmp/ms/package
```

## How labels get icons

UI code still writes short glyphs in labels (for example `"⟳ بروزرسانی"`). `MeelanoIcons.java`
draws each known glyph as the matching SVG icon (same colour and size as the text) without
changing the text itself, so code that reads labels back keeps working. The glyph → icon table
lives in `MeelanoIcons.java`.
