#!/usr/bin/env python3
"""
Draws the Safemail app icon, a smiley face sitting on an envelope, for both apps from one design:

  ios/Safemail/Assets.xcassets/AppIcon.appiconset/icon-1024.png   full-bleed, opaque (iOS rounds the corners)
  android/app/src/main/res/drawable-nodpi/ic_launcher_foreground.png   the artwork inside the adaptive-icon safe zone
  android/app/src/main/res/drawable-nodpi/ic_launcher_monochrome.png   a one-color cut-out for themed icons

The adaptive-icon background is the plain color `ic_launcher_background` in android/.../values/colors.xml
(keep it equal to BACKGROUND below).

Needs Pillow:  python3 -m venv .venv && .venv/bin/pip install pillow && .venv/bin/python scripts/generate-app-icon.py
Pass --preview DIR to also write masked previews (iOS rounded square, Android circle).
"""
import argparse
import math
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
BACKGROUND = (0x20, 0x8A, 0xEF)
WHITE = (255, 255, 255)
FLAP = (0xA9, 0xCC, 0xF6)
YELLOW = (0xFF, 0xD4, 0x3B)
YELLOW_EDGE = (0xE8, 0xA8, 0x00)
INK = (0x24, 0x1B, 0x00)

SS = 4            # supersampling for smooth edges
DESIGN = 1024     # the artwork is designed on a 1024 square
# Android keeps adaptive icons inside a 66dp circle of the 108dp canvas, so the artwork is shrunk there.
ANDROID_SCALE = 0.75


def bezier(p0, c, p1, steps=60):
    return [
        ((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * c[0] + t**2 * p1[0], (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * c[1] + t**2 * p1[1])
        for t in (i / steps for i in range(steps + 1))
    ]


def artwork(size, scale=1.0, mono=False):
    """The envelope and the smiley on a transparent square of `size` px (the whole design scaled by `scale`)."""
    px = size * SS / DESIGN
    s = lambda v: v * px * scale
    off = size * SS * (1 - scale) / 2
    p = lambda x, y: (off + s(x), off + s(y))

    img = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)  # replaces pixels (no blending), which is what the cut-outs below need

    solid = (255, 255, 255, 255)
    clear = (0, 0, 0, 0)
    c = lambda color: solid if mono else (*color, 255)

    def ellipse(cx, cy, rx, ry, fill):
        d.ellipse([*p(cx - rx, cy - ry), *p(cx + rx, cy + ry)], fill=fill)

    def stroke(points, width, fill):
        """A round-capped line, made of closely spaced dots so curves stay perfectly smooth."""
        pts = [p(*q) for q in points]
        r = s(width) / 2
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            steps = max(1, math.ceil(math.hypot(x1 - x0, y1 - y0) / (r / 4)))
            for i in range(steps + 1):
                t = i / steps
                x, y = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
                d.ellipse([x - r, y - r, x + r, y + r], fill=fill)

    # Envelope
    d.rounded_rectangle([*p(212, 470), *p(812, 800)], radius=s(44), fill=c(WHITE))
    stroke([(250, 506), (512, 668), (774, 506)], 26, clear if mono else (*FLAP, 255))

    # Smiley, with a small gap around it so it reads as sitting on the envelope
    cx, cy, r = 512, 372, 168
    ellipse(cx, cy, r + 18, r + 18, clear)
    ellipse(cx, cy, r, r, c(YELLOW_EDGE))
    ellipse(cx, cy, r - 12, r - 12, c(YELLOW))
    for ex in (cx - 58, cx + 58):
        ellipse(ex, cy - 20, 21, 27, clear if mono else (*INK, 255))
    stroke(bezier((cx - 76, cy + 52), (cx, cy + 150), (cx + 76, cy + 52)), 26, clear if mono else (*INK, 255))

    return img


def finish(img, size):
    return img.resize((size, size), Image.LANCZOS)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", type=Path)
    args = parser.parse_args()

    # iOS: opaque, full bleed
    ios = Image.new("RGBA", (1024 * SS, 1024 * SS), (*BACKGROUND, 255))
    ios.alpha_composite(artwork(1024))
    ios = finish(ios, 1024).convert("RGB")
    ios_path = ROOT / "ios/Safemail/Assets.xcassets/AppIcon.appiconset/icon-1024.png"
    ios_path.parent.mkdir(parents=True, exist_ok=True)
    ios.save(ios_path)

    # Android: transparent layers inside the safe zone
    res = ROOT / "android/app/src/main/res/drawable-nodpi"
    res.mkdir(parents=True, exist_ok=True)
    fg = finish(artwork(512, ANDROID_SCALE), 512)
    fg.save(res / "ic_launcher_foreground.png")
    mono = finish(artwork(512, ANDROID_SCALE, mono=True), 512)
    mono.save(res / "ic_launcher_monochrome.png")

    if args.preview:
        args.preview.mkdir(parents=True, exist_ok=True)
        # iOS: rounded square
        mask = Image.new("L", (1024, 1024), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, 1023, 1023], radius=229, fill=255)
        rounded = ios.convert("RGBA")
        rounded.putalpha(mask)
        rounded.save(args.preview / "ios-rounded.png")
        # Android: circle mask over background + foreground
        canvas = Image.new("RGBA", (512, 512), (*BACKGROUND, 255))
        canvas.alpha_composite(fg)
        circle = Image.new("L", (512, 512), 0)
        ImageDraw.Draw(circle).ellipse([0, 0, 511, 511], fill=255)
        canvas.putalpha(circle)
        canvas.save(args.preview / "android-circle.png")
        # Android themed (monochrome) icon: white shapes on a dark circle
        themed = Image.new("RGBA", (512, 512), (0x3B, 0x4A, 0x6B, 255))
        tint = Image.new("RGBA", (512, 512), (0xD6, 0xE3, 0xFF, 255))
        themed.paste(tint, mask=mono.split()[3])
        themed.putalpha(circle)
        themed.save(args.preview / "android-themed.png")


if __name__ == "__main__":
    main()
