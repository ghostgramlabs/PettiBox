"""PettiBox flat icon: one symmetric open-box glyph -> vector drawables + PNGs + preview.

  python tools/icon/flat_icon.py [variant] [write|preview] [ajar|open]

variant "orange": persimmon box on cream (the original PettiBox look)
variant "cream":  cream box on a persimmon background
Without "write" it only renders a preview sheet into the system temp dir.
"""
import math
import os
import sys
import tempfile

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "app", "src", "main", "res")

PALETTES = {
    "orange": dict(bg=("#FFF6EA", "#F6DEC4"), body="#D85A36", rim="#E8703F", lid="#A9401F",
                   lid_edge="#E8703F", card="#FFFDF8", band="#F0B84C", clasp="#F6C453"),
    "cream": dict(bg=("#E8683C", "#C9472A"), body="#FFF8F1", rim="#FFFFFF", lid="#F2CDB4",
                  lid_edge="#FFF8F1", card="#FFD35C", band="#F4A93A", clasp="#E8683C"),
}


def rrect(x, y, w, h, r, n=10):
    pts = []
    for cx, cy, a0 in ((x + w - r, y + r, -90), (x + w - r, y + h - r, 0), (x + r, y + h - r, 90), (x + r, y + r, 180)):
        for i in range(n + 1):
            a = math.radians(a0 + 90 * i / n)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def fillet(pts, r, n=10):
    out = []
    for i in range(len(pts)):
        p0, p1, p2 = pts[i - 1], pts[i], pts[(i + 1) % len(pts)]
        k1 = min(r, math.dist(p0, p1) / 2) / math.dist(p0, p1)
        k2 = min(r, math.dist(p1, p2) / 2) / math.dist(p1, p2)
        a = (p1[0] + (p0[0] - p1[0]) * k1, p1[1] + (p0[1] - p1[1]) * k1)
        b = (p1[0] + (p2[0] - p1[0]) * k2, p1[1] + (p2[1] - p1[1]) * k2)
        for j in range(n + 1):
            t = j / n
            out.append(((1 - t) ** 2 * a[0] + 2 * (1 - t) * t * p1[0] + t * t * b[0],
                        (1 - t) ** 2 * a[1] + 2 * (1 - t) * t * p1[1] + t * t * b[1]))
    return out


def rot(pts, deg, pv):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return [(pv[0] + (x - pv[0]) * c - (y - pv[1]) * s, pv[1] + (x - pv[0]) * s + (y - pv[1]) * c) for x, y in pts]


STYLE = "ajar"  # the shipped icon; "open" was an alternative


def raw_shapes(p):
    if STYLE == "open":  # lid swung right back, seen head-on
        card = rot(rrect(42, 29, 24, 30, 3.5), -7, (54, 50))
        band = rot(rrect(42, 29, 24, 6, 3), -7, (54, 50))
        return [  # (name, polygon, colour, in monochrome)
            ("lid", fillet([(27, 51), (33, 27), (75, 27), (81, 51)], 5), p["lid"], True),
            ("lid_edge", rrect(31, 23, 46, 7, 3.5), p["lid_edge"], True),
            ("card", card, p["card"], True),
            ("band", band, p["band"], False),
            ("body", rrect(23, 49, 62, 34, 8), p["body"], True),
            ("rim", rrect(23, 49, 62, 6, 3), p["rim"], False),
            ("clasp", rrect(48.5, 55, 11, 12, 3.5), p["clasp"], False),
        ]
    # "ajar": lid tipped up on its left hinge, a card slipping out of the gap
    hinge = (23, 50)
    card = rot(rrect(52, 26, 22, 28, 3.5), 14, (63, 44))
    band = rot(rrect(52, 26, 22, 6, 3), 14, (63, 44))
    return [
        ("inside", rrect(26, 44, 56, 10, 3), p["lid"], False),
        ("card", card, p["card"], True),
        ("band", band, p["band"], False),
        ("body", rrect(23, 49, 62, 34, 8), p["body"], True),
        ("lid", rot(rrect(21, 37, 66, 13, 6), -17, hinge), p["rim"], True),
        ("clasp", rrect(48.5, 54, 11, 13, 3.5), p["clasp"], False),
    ]


def shapes(p):
    """Glyph centred on the canvas and scaled so it sits inside the 66dp safe circle."""
    sh = raw_shapes(p)
    pts = [q for s in sh for q in s[1]]
    cx = (min(q[0] for q in pts) + max(q[0] for q in pts)) / 2
    cy = (min(q[1] for q in pts) + max(q[1] for q in pts)) / 2
    k = 32.5 / max(math.dist(q, (cx, cy)) for q in pts)
    return [(n, [(54 + (x - cx) * k, 54 + (y - cy) * k) for x, y in poly], c, m) for n, poly, c, m in sh]


def hexrgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def gradient(px, top, bot):
    t = np.linspace(0, 1, px)
    tt = t[:, None] * 0.7 + t[None, :] * 0.3
    a, b = np.array(hexrgb(top), float), np.array(hexrgb(bot), float)
    img = a[None, None] * (1 - tt[..., None]) + b[None, None] * tt[..., None]
    return Image.fromarray(np.dstack([img, np.full((px, px), 255)]).astype(np.uint8), "RGBA")


def render(p, px, bg=True, mono=False):
    ss = 4
    P = px * ss
    k = P / 108
    img = gradient(P, *p["bg"]) if bg else Image.new("RGBA", (P, P), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    sh = shapes(p)
    for name, pts, col, inmono in sh:
        if mono and not inmono:
            continue
        d.polygon([(x * k, y * k) for x, y in pts], fill=(255, 255, 255, 255) if mono else hexrgb(col) + (255,))
    if mono:  # clasp and the lid/body seam as holes
        a = np.array(img.split()[3])
        m = Image.new("L", (P, P), 0)
        md = ImageDraw.Draw(m)
        md.polygon([(x * k, y * k) for x, y in dict((s[0], s[1]) for s in sh)["clasp"]], fill=255)
        a[np.array(m) > 0] = 0
        img.putalpha(Image.fromarray(a))
    return img.resize((px, px), Image.LANCZOS)


def masked(p, px, shape):
    full = render(p, 108 * px // 72)
    off = (full.width - px) // 2
    icon = full.crop((off, off, off + px, off + px))
    if shape == "square":
        return icon
    m = Image.new("L", (px * 4, px * 4), 0)
    d = ImageDraw.Draw(m)
    if shape == "circle":
        d.ellipse((0, 0, px * 4 - 1, px * 4 - 1), fill=255)
    else:
        d.rounded_rectangle((0, 0, px * 4 - 1, px * 4 - 1), radius=int(px * 4 * 0.22), fill=255)
    icon.putalpha(m.resize((px, px), Image.LANCZOS))
    return icon


def fmt(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def vec(p, mono):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- Generated by tools/icon/flat_icon.py; edit the glyph there, not here. -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp"', '    android:height="108dp"',
           '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    sh = shapes(p)
    clasp = dict((s[0], s[1]) for s in sh)["clasp"]
    for name, pts, col, inmono in sh:
        if mono and not inmono:
            continue
        d = "M" + " L".join(f"{fmt(x)},{fmt(y)}" for x, y in pts) + " Z"
        if mono and name == "body":
            d += " M" + " L".join(f"{fmt(x)},{fmt(y)}" for x, y in clasp) + " Z"
            out.append('    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd"')
        else:
            out.append(f'    <path android:fillColor="{"#FFFFFFFF" if mono else col}"')
        out.append(f'        android:pathData="{d}" />')
    out.append("</vector>")
    return "\n".join(out) + "\n"


def bg_xml(p):
    top, bot = p["bg"]
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/icon/flat_icon.py; edit the palette there, not here. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="32"
                android:startY="0"
                android:endX="76"
                android:endY="108"
                android:startColor="{top}"
                android:endColor="{bot}" />
        </aapt:attr>
    </path>
</vector>
"""


ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""


def preview_row(p, sheet, y0, dark):
    x = 20
    for shape in ("circle", "squircle"):
        for size in (200, 96, 48):
            sheet.alpha_composite(masked(p, size, shape), (x, y0 + (200 - size) // 2))
            x += size + 20
    bgc, fgc = ((40, 60, 110), (215, 227, 255)) if not dark else ((215, 227, 255), (40, 60, 110))
    glyph = render(p, 144, bg=False, mono=True)
    tile = Image.new("RGBA", (144, 144), bgc + (255,))
    g = Image.new("RGBA", (144, 144), fgc + (255,))
    g.putalpha(glyph.split()[3])
    tile.alpha_composite(g)
    tile = tile.crop((24, 24, 120, 120))
    cm = Image.new("L", (384, 384), 0)
    ImageDraw.Draw(cm).ellipse((0, 0, 383, 383), fill=255)
    tile.putalpha(cm.resize((96, 96), Image.LANCZOS))
    sheet.alpha_composite(tile, (x, y0 + 52))


if __name__ == "__main__":
    if len(sys.argv) > 3:
        STYLE = sys.argv[3]
    variants = [sys.argv[1]] if len(sys.argv) > 1 else list(PALETTES)
    sheet = Image.new("RGBA", (1000, 240 * 2 * len(variants)), (245, 245, 245, 255))
    for i, v in enumerate(variants):
        y = i * 480
        sheet.paste(Image.new("RGBA", (1000, 240), (30, 30, 34, 255)), (0, y + 240))
        preview_row(PALETTES[v], sheet, y + 20, False)
        preview_row(PALETTES[v], sheet, y + 260, True)
    out = os.path.join(tempfile.gettempdir(), "pettibox-flat-preview.png")
    sheet.save(out)
    print("preview:", out)
    if len(sys.argv) > 2 and sys.argv[2] == "write":
        p = PALETTES[sys.argv[1]]
        for d, k in {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}.items():
            folder = os.path.join(RES, f"mipmap-{d}")
            for stale in ("ic_launcher_foreground.png", "ic_launcher_monochrome.png"):
                if os.path.exists(os.path.join(folder, stale)):
                    os.remove(os.path.join(folder, stale))
            masked(p, round(48 * k), "squircle").save(os.path.join(folder, "ic_launcher.png"))
            masked(p, round(48 * k), "circle").save(os.path.join(folder, "ic_launcher_round.png"))
        for name, text in (("ic_launcher_foreground.xml", vec(p, False)), ("ic_launcher_monochrome.xml", vec(p, True)),
                           ("ic_launcher_background.xml", bg_xml(p))):
            with open(os.path.join(RES, "drawable", name), "w", newline="\n") as f:
                f.write(text)
        for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
            with open(os.path.join(RES, "mipmap-anydpi-v26", name), "w", newline="\n") as f:
                f.write(ADAPTIVE_XML)
        masked(p, 512, "square").save(os.path.join(ROOT, "store", "pettibox-icon-512.png"))
        print("written")
