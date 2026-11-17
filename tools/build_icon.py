"""Marble Hop icon (design-package appIcon): indigo #2F3E9E ground, a light-beech #E8C9A0 six-point star with a
7-hole cluster in the middle, and one mint #5FD6B0 glass marble with a white highlight on the lower-right tip. No text.

  uv run --system-certs --with pillow python3 tools/build_icon.py [--icon512 out.png] [--preview out.png]

Writes app/src/main/res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon) and
mipmap-xxxhdpi/ic_spatial_launcher.png. The adaptive launcher icon is the vector drawable ic_launcher_fg.xml
(same drawing) on @color/ic_launcher_background.
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024
SS = 4
INDIGO = (0x2F, 0x3E, 0x9E)
BEECH = (0xE8, 0xC9, 0xA0)
HOLE = (0xB0, 0x8A, 0x5E)
MINT = (0x5F, 0xD6, 0xB0)
WHITE = (0xFF, 0xFF, 0xFF)

# Artwork in a 108-unit box (the adaptive icon grid); layers scale it to 1024.
C = 54.0
R_OUT = 34.0
R_IN = R_OUT / math.sqrt(3)
STAR = [(C + (R_OUT if i % 2 == 0 else R_IN) * math.cos(math.radians(-90 + 30 * i)),
         C + (R_OUT if i % 2 == 0 else R_IN) * math.sin(math.radians(-90 + 30 * i))) for i in range(12)]
TIP = (C + R_OUT * math.cos(math.radians(30)), C + R_OUT * math.sin(math.radians(30)))
HOLES = [(C, C)] + [(C + 7.5 * math.cos(math.radians(60 * i)), C + 7.5 * math.sin(math.radians(60 * i))) for i in range(6)]
MR = 8.5


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def layer_bg():
    def d(dr, k):
        dr.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=INDIGO + (255,))
    return big(d)


def layer_star():
    def d(dr, k):
        dr.polygon([(x * k, y * k) for x, y in STAR], fill=BEECH + (255,))
        for x, y in HOLES:
            dr.ellipse(((x - 2.4) * k, (y - 2.4) * k, (x + 2.4) * k, (y + 2.4) * k), fill=HOLE + (255,))
    return big(d)


def layer_marble():
    def d(dr, k):
        x, y = TIP
        dr.ellipse(((x - MR) * k, (y - MR) * k, (x + MR) * k, (y + MR) * k), fill=MINT + (255,))
        dr.ellipse(((x - MR * 0.55) * k, (y - MR * 0.62) * k, (x - MR * 0.05) * k, (y - MR * 0.18) * k), fill=WHITE + (235,))
    return big(d)

def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def flatten(layers):
    out = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for layer in layers:
        out.alpha_composite(layer)
    return out


def main():
    layers = [layer_bg(), layer_star(), layer_marble()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = flatten(layers)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        w = 256
        tiles = layers + [flat]
        sheet = Image.new("RGB", (w * len(tiles), w), (60, 64, 70))
        for i, t in enumerate(tiles):
            r = t.resize((w, w), Image.LANCZOS)
            sheet.paste(r, (i * w, 0), r)
        sheet.save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()
