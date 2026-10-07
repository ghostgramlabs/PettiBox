"""PettiBox Play Store feature graphic (1024x500), built from the launcher icon's glyph.

  python tools/icon/feature_graphic.py [FONT_DIR]

FONT_DIR should hold NotoSerif.ttf and NotoSans.ttf (the variable fonts from
github.com/google/fonts, OFL) so the type matches the app's Android serif.
Without them it falls back to Georgia / Segoe UI from C:/Windows/Fonts.
Writes store/pettibox-feature-graphic.png.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from flat_icon import PALETTES, ROOT, hexrgb, render  # noqa: E402

W, H = 1024, 500
SS = 2  # supersample, then downscale for clean edges
P = PALETTES["cream"]
CREAM = hexrgb(P["body"])


def font(font_dir, kind, size, weight):
    noto = os.path.join(font_dir or "", "NotoSerif.ttf" if kind == "serif" else "NotoSans.ttf")
    if font_dir and os.path.exists(noto):
        f = ImageFont.truetype(noto, size)
        f.set_variation_by_name(weight)
        return f
    fallback = {"serif": "georgiab.ttf", "sans": "segoeuisl.ttf" if weight != "SemiBold" else "seguisb.ttf"}[kind]
    return ImageFont.truetype(os.path.join("C:/Windows/Fonts", fallback), size)


def background(w, h):
    # Same persimmon ramp as the icon background, run diagonally across the banner.
    top, bot = (np.array(hexrgb(c), float) for c in P["bg"])
    y, x = np.mgrid[0:h, 0:w]
    t = (x / w) * 0.45 + (y / h) * 0.55
    img = top * (1 - t[..., None]) + bot * t[..., None]
    return Image.fromarray(np.dstack([img, np.full((h, w), 255)]).astype(np.uint8), "RGBA")


def main(font_dir):
    w, h = W * SS, H * SS
    img = background(w, h)

    # Soft glow disc behind the box, so the glyph sits on a gentle spotlight.
    glow = Image.new("L", (w, h), 0)
    cx, cy, r = int(w * 0.755), int(h * 0.53), int(h * 0.44)
    ImageDraw.Draw(glow).ellipse((cx - r, cy - r, cx + r, cy + r), fill=70)
    glow_layer = Image.new("RGBA", (w, h), (255, 214, 170, 0))
    glow_layer.putalpha(glow.filter(ImageFilter.GaussianBlur(40 * SS)))
    img.alpha_composite(glow_layer)

    # The icon glyph itself (open box + card), large on the right.
    glyph_px = int(h * 1.32)
    glyph = render(P, glyph_px, bg=False)
    bbox = glyph.getbbox()
    glyph = glyph.crop(bbox)
    # A soft drop shadow keeps the cream box from melting into the glow.
    # Padded canvas so the blur fades out instead of clipping at the glyph's box.
    pad = 60 * SS
    mask = Image.new("L", (glyph.width + 2 * pad, glyph.height + 2 * pad), 0)
    mask.paste(glyph.split()[3].point(lambda a: a * 0.28), (pad, pad))
    shadow = Image.new("RGBA", mask.size, (120, 30, 10, 0))
    shadow.putalpha(mask.filter(ImageFilter.GaussianBlur(14 * SS)))
    gx, gy = cx - glyph.width // 2, cy - glyph.height // 2
    img.alpha_composite(shadow, (gx - pad + 6 * SS, gy - pad + 16 * SS))
    img.alpha_composite(glyph, (gx, gy))

    # Type and pills go on their own layer: drawing translucent fills straight
    # onto an RGBA image would overwrite its alpha instead of blending.
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    left = int(72 * SS)
    title = font(font_dir, "serif", 112 * SS, "ExtraBold")
    tag = font(font_dir, "sans", 40 * SS, "Medium")
    small = font(font_dir, "sans", 25 * SS, "SemiBold")

    d.text((left, 118 * SS), "PettiBox", font=title, fill=CREAM + (255,))
    d.text((left + 4 * SS, 268 * SS), "Save anything.", font=tag, fill=CREAM + (255,))
    d.text((left + 4 * SS, 318 * SS), "Find it later.", font=tag, fill=CREAM + (215,))

    # Pill row of what it saves.
    x, y = left, 392 * SS
    for label in ("Links", "Screenshots", "PDFs", "Notes"):
        tw = d.textlength(label, font=small)
        pad_x, pill_h = 18 * SS, 44 * SS
        d.rounded_rectangle((x, y, x + tw + 2 * pad_x, y + pill_h), radius=pill_h // 2,
                            fill=(255, 248, 241, 46), outline=CREAM + (150,), width=2 * SS)
        d.text((x + pad_x, y + pill_h / 2), label, font=small, fill=CREAM + (255,), anchor="lm")
        x += tw + 2 * pad_x + 12 * SS

    img.alpha_composite(layer)
    out = os.path.join(ROOT, "store", "pettibox-feature-graphic.png")
    img.resize((W, H), Image.LANCZOS).convert("RGB").save(out)
    print("written:", out)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else None)
