#!/usr/bin/env python3
"""Regenerate the Android launcher icon assets from SwitchboardLogo.png.

    python3 design/generate-android-icons.py        # run from the repo root

Rewrites, for all five densities:
    app/src/main/res/mipmap-*/ic_launcher_foreground.png
    app/src/main/res/mipmap-*/ic_launcher_monochrome.png
    app/src/main/res/mipmap-*/ic_launcher.png
    app/src/main/res/mipmap-*/ic_launcher_round.png

The background colour is read from values/ic_launcher_background.xml rather
than set here, so the legacy bitmaps -- which bake it in -- cannot drift from
the colour the adaptive icon actually uses. Change the colour there, re-run
this, commit both.

Requires Pillow and NumPy.
"""
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'design' / 'SwitchboardLogo.png'
RES = ROOT / 'app' / 'src' / 'main' / 'res'
BG_XML = RES / 'values' / 'ic_launcher_background.xml'

# Launchers reveal the centre 72dp of a 108dp layer and inscribe their mask in
# it. 66dp is the diameter that clears the roundest mask a launcher can apply.
SAFE_DIAM_DP, CANVAS_DP = 66.0, 108.0
FEATHER = 60
DENSITIES = [('mdpi', 108), ('hdpi', 162), ('xhdpi', 216), ('xxhdpi', 324), ('xxxhdpi', 432)]
LEGACY = [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]


def background_colour():
    m = re.search(r'name="ic_launcher_background">#([0-9A-Fa-f]{6,8})<', BG_XML.read_text())
    if not m:
        sys.exit(f'could not read ic_launcher_background from {BG_XML}')
    h = m.group(1)[-6:]
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def downscale(img, tw, th):
    """Resize through premultiplied alpha. PIL filters channels independently,
    which drags the colour sitting under the transparent pixels into the mark's
    edges as a halo."""
    a = np.array(img).astype(np.float64)
    pre = np.concatenate([a[..., :3] * (a[..., 3:4] / 255.0), a[..., 3:4]], axis=2)
    small = np.array(Image.fromarray(np.clip(pre, 0, 255).astype(np.uint8), 'RGBA')
                     .resize((tw, th), Image.LANCZOS)).astype(np.float64)
    oa = small[..., 3:4]
    rgb = np.divide(small[..., :3] * 255.0, np.maximum(oa, 1e-6))
    return Image.fromarray(
        np.clip(np.concatenate([rgb, oa], axis=2), 0, 255).astype(np.uint8), 'RGBA')


def main():
    bg = background_colour()
    src = Image.open(SRC).convert('RGBA')
    arr = np.array(src).astype(np.float64)
    alpha = arr[..., 3]
    H, W = alpha.shape
    solid = alpha > 200

    # The glow runs off the master's canvas at up to 52% alpha. Full-bleed on
    # iOS that is invisible -- the tile crops there anyway -- but inset into
    # Android's safe zone it would end in a hard line against the background.
    # Fade only the translucent glow; the terminals are solid and touch the
    # edge legitimately, so keep clear of them.
    yy, xx = np.mgrid[0:H, 0:W]
    edge = np.minimum.reduce([xx, yy, W - 1 - xx, H - 1 - yy]).astype(np.float64)
    near_solid = np.array(Image.fromarray((solid * 255).astype(np.uint8), 'L')
                          .filter(ImageFilter.MaxFilter(17))).astype(np.float64) / 255.0
    arr[..., 3] = alpha * np.maximum(np.clip(edge / FEATHER, 0.0, 1.0), near_solid)
    fg_src = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), 'RGBA')

    # Only the *solid* mark has to fit the safe circle. The glow is a large
    # part of the image and is meant to bleed past it and fill the layer;
    # fitting the glow too would shrink the mark by roughly half.
    ys, xs = np.where(solid)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    cx, cy = (x0 + x1 + 1) / 2.0, (y0 + y1 + 1) / 2.0
    rad = 0.0
    for y in range(y0, y1 + 1):
        row = np.where(solid[y])[0]
        if len(row):
            rad = max(rad, np.hypot(row.min() - cx, y - cy), np.hypot(row.max() - cx, y - cy))

    pct = 100 * (x1 - x0 + 1) / 2 / rad * SAFE_DIAM_DP / 72
    print(f'background #{bg[0]:02X}{bg[1]:02X}{bg[2]:02X} · '
          f'mark at {pct:.1f}% of the visible tile (expect ~70%)')

    silhouette = Image.fromarray(np.concatenate([
        np.full((H, W, 3), 255, np.uint8),
        (solid * 255).astype(np.uint8)[..., None]], axis=2), 'RGBA')

    def layer(px, source):
        scale = (SAFE_DIAM_DP / 2 / CANVAS_DP) * px / rad
        small = downscale(source, max(1, round(W * scale)), max(1, round(H * scale)))
        out = Image.new('RGBA', (px, px), (0, 0, 0, 0))
        out.paste(small, (round(px / 2 - cx * scale), round(px / 2 - cy * scale)), small)
        return out

    for name, px in DENSITIES:
        layer(px, fg_src).save(RES / f'mipmap-{name}' / 'ic_launcher_foreground.png')
        # Themed icons tint this flat, so it is the mark only -- a tinted glow
        # reads as smudge.
        layer(px, silhouette).save(RES / f'mipmap-{name}' / 'ic_launcher_monochrome.png')

    # minSdk 26 means mipmap-anydpi-v26 always wins, so these never reach the
    # launcher; they exist for sideload tooling that reads a bitmap instead of
    # rendering the adaptive XML. Render what the launcher shows so the two
    # cannot disagree.
    for name, px in LEGACY:
        ss = px * 4
        disc = Image.alpha_composite(Image.new('RGBA', (ss, ss), bg + (255,)), layer(ss, fg_src))
        vis = disc.crop((ss * 18 // 108, ss * 18 // 108, ss * 90 // 108, ss * 90 // 108))
        v = vis.width
        for filename, shape in (('ic_launcher.png', 'sq'), ('ic_launcher_round.png', 'round')):
            mask = Image.new('L', (v, v), 0)
            d = ImageDraw.Draw(mask)
            if shape == 'round':
                d.ellipse((0, 0, v - 1, v - 1), fill=255)
            else:
                d.rounded_rectangle((0, 0, v - 1, v - 1), radius=int(v * 0.20), fill=255)
            out = Image.new('RGBA', (v, v), (0, 0, 0, 0))
            out.paste(vis, (0, 0), mask)
            out.resize((px, px), Image.LANCZOS).save(RES / f'mipmap-{name}' / filename)

    print('wrote 20 bitmaps')


if __name__ == '__main__':
    main()
