# Med Rx — logo working set

The launcher icon is an Android **adaptive icon**. Its real source of truth is
*vector XML*, not an image file — edit the vectors, then copy them back.

## What's here

| File | What it is |
|---|---|
| `ic_launcher_foreground.xml` | **The logo artwork** — the piece you want to modify. 512×512 viewport drawn inside a 108×108dp canvas. |
| `ic_launcher_monochrome.xml` | Same art as a single solid-black path — used by Android 13+ themed (Material You) icons. Update it to match if you change the shape. |
| `background.txt` | Adaptive-icon background color (currently `#FFFFFF` white). |
| `preview.svg` | Browser-viewable SVG of the current art, for quick looking. |
| `playstore-512.png` | The 512px Play Store raster (exported from upstream's Wear module). Reference only. |
| `README.md` | This file. |

Phone and Wear modules share identical foreground + monochrome art, so one edit
covers both once copied back to each module.

## How to modify

1. Edit `ic_launcher_foreground.xml` — a single solid-black `<path>` (the Material
   Symbols *health metrics* glyph on a 960×960 grid). The art group is
   `scale(0.384) translate(71.68,71.68)`, i.e. a 368-unit square centered in the
   512 viewport; the safe drawing area is the middle **66/108** of the canvas —
   avoid the outer ~18dp on each side (mask/crop zone).
2. If the *shape* changed, mirror the same path (with `android:fillColor="#000000"`)
   into `ic_launcher_monochrome.xml`. If only colors changed, leave it alone.
3. Optionally tweak `background.txt` / the SVG preview.

## Installing the result back into the app

Copy the two XMLs into **both** modules (same filenames):

```bash
# Run from Med/design/logo/
cp ic_launcher_foreground.xml ic_launcher_monochrome.xml ../../app/src/main/res/drawable/
cp ic_launcher_foreground.xml ic_launcher_monochrome.xml ../../wear/src/main/res/drawable/
```

That's genuinely all the app reads — minSdk is 26, so Android always uses the
adaptive icon (vector foreground), never the webp rasters. Optionally delete
the now-unused webps in `app/src/main/res/mipmap-{m..xxx}dpi/` and
`wear/src/main/res/mipmap-{m..xxx}dpi/`, plus regenerate `wear/src/main/ic_launcher-playstore.png`
if you want the store listing to match.

## Sizing math (if you import from SVG/Figma)

- Canvas: 108×108dp, viewport 512×512.
- Art group: `scale(0.384) translate(71.68,71.68)` → art space = 368×368 units
  (~197dp) centered on the canvas.
- Artwork: Material Symbols *health metrics* glyph, solid black on a white
  background (no gradient).

## Rebranding checklist

When replacing the logo, treat it as a product identity change rather than only
an icon edit. Use this checklist as a reference when the new design is ready.

### Visual design

- [ ] Define the primary mark, wordmark, colors, typography, and any clear-space rules.
- [ ] Keep the important artwork inside the adaptive-icon safe zone; Android may mask the icon as a circle, squircle, or rounded square.
- [ ] Check the design at launcher size, notification size, settings size, and store-listing size.
- [ ] Provide a full-color version, a monochrome version, and a light/dark-background version if needed.
- [ ] Verify contrast and legibility for users with low vision and for Android themed icons.
- [ ] Decide whether the mark should communicate medication tracking, personal health logging, or a broader identity.
- [ ] Confirm that the new design does not unintentionally resemble a medical symbol, trademark, or another app.

### Android assets

- [ ] Update `ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml` in both phone and Wear modules.
- [ ] Update `ic_launcher_background.xml` if the adaptive-icon background changes.
- [ ] Regenerate the legacy density webps and `wear/src/main/ic_launcher-playstore.png` if store tooling or older launchers still need them.
- [ ] Update the SVG preview and keep the editable source format available for future changes.
- [ ] Build both phone and Wear release variants and inspect the icon on a real launcher, including themed-icon mode.

### Product and copy

- [ ] Update the launcher label and any About, What's New, updater, notification, widget, and Wear-facing names.
- [ ] Search the repository for the old brand in code, resources, documentation, workflows, release text, and screenshots.
- [ ] Decide whether the package/application ID stays unchanged. Keeping it preserves updates and existing user data; changing it creates a separate app and migration problem.
- [ ] Update README, acknowledgements, community links, release notes, and the upstream-fork credit without overstating the change.
- [ ] Add or revise an in-app What's New entry only if users need to understand the change.

### Localization and accessibility

- [ ] Translate descriptive copy and accessibility labels; keep the brand name consistent unless there is a deliberate localized form.
- [ ] Check every supported locale for text width, launcher-label truncation, and culturally confusing colors or symbols.
- [ ] Keep content descriptions meaningful even when the image is unavailable or rendered monochrome.
- [ ] Run the locale-sync check and have translations reviewed before release.

### Release and migration

- [ ] Bump the app version according to the fork's release policy and update both phone and Wear modules together.
- [ ] Confirm the signing certificate is unchanged so the new build can update existing installations.
- [ ] Test upgrade, backup/restore, updater detection, notifications, and Wear pairing before publishing.
- [ ] Update release asset names, screenshots, workflow copy, and the GitHub Release/Discussion announcement.
- [ ] Keep a dated copy of the previous artwork and record the reason for the change.
- [ ] Have someone unfamiliar with the design review the final icon at small size before cutting the release.
