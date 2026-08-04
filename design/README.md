# Design assets

## `SwitchboardLogo.png`

The Switchboard mark: 1606×1606 RGBA, **foreground only** on transparency, with the four
connector terminals sitting tangent to the canvas. It carries no background — that is a
separate decision per platform — and no corner mask.

This is the source of truth for the Android launcher icon. Keep it here rather than on
someone's desktop: without it the icon cannot be regenerated at all.

## Regenerating the Android launcher icon

```sh
python3 design/generate-android-icons.py    # from the repo root; needs Pillow + NumPy
```

That rewrites the adaptive-icon foreground, the themed-icon monochrome layer, and the legacy
square/round bitmaps across all five densities. It should print `~70%` for the mark's share of
the visible tile; a number far from that means something is wrong with the input.

The **background colour lives in `app/src/main/res/values/ic_launcher_background.xml`**, and
the script reads it from there. The legacy bitmaps bake the colour in, so changing it means
editing that file and re-running the script — otherwise the two disagree. The adaptive
foreground and monochrome layers are colour-independent.

Two decisions in the script that look wrong but are not, and should not be "simplified":

- Only the **solid** artwork is fitted to the safe circle, not the full alpha extent. The glow
  is a large part of the image and is meant to bleed past the safe zone and fill the layer.
- The glow is **feathered at the master's canvas border**, where it is cut off at up to 52%
  alpha. Inset into the safe zone that cut would otherwise show as a hard line.

## iOS

`iosApp/iosApp/Assets.xcassets/AppIcon.appiconset` is **not** generated from this file and is
deliberately different — it needs Icon Composer's template and its own padding. Do not try to
bring the two platforms into pixel agreement.
