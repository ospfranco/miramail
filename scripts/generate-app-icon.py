#!/usr/bin/env python3
"""
Makes the Miramail app icon, a face with curly hair and round glasses, for both apps from one source image,
store/app-icon-source.png (a square illustration on a flat BACKGROUND):

  ios/Trustmail/Assets.xcassets/AppIcon.appiconset/icon-1024.png   full-bleed, opaque (iOS rounds the corners)
  android/app/src/main/res/drawable-nodpi/ic_launcher_foreground.png   the artwork inside the adaptive-icon safe zone
  android/app/src/main/res/drawable-nodpi/ic_launcher_monochrome.png   a one-color cut-out for themed icons
  store/play-store-icon-512.png   the 512px icon Google Play asks for in the store listing

and the splash screen, the logo in a SPLASH_CIRCLE-wide circle on the app's background:

  ios/Trustmail/Assets.xcassets/SplashLogo.imageset/splash-logo.png   the circle alone (LaunchScreen.storyboard)
  android/app/src/main/res/drawable-nodpi/splash_logo.png   the circle centered in the 288dp splash-icon box

The adaptive-icon background is the plain color `ic_launcher_background` in android/.../values/colors.xml
(keep it equal to BACKGROUND below; the script refuses to run otherwise).

Needs Pillow and numpy:
  python3 -m venv .venv && .venv/bin/pip install pillow numpy && .venv/bin/python scripts/generate-app-icon.py
Pass --preview DIR to also write masked previews (iOS rounded square, Android circle, Android themed).
"""
import argparse
import re
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "store/app-icon-source.png"
COLORS = ROOT / "android/app/src/main/res/values/colors.xml"
BACKGROUND = (0xE7, 0xCE, 0xEE)  # the source image's flat background

# Android keeps adaptive icons inside a 66dp circle of the 108dp canvas, and the artwork reaches 40% of the source's
# width from its center, so the source is shrunk to this share of the canvas.
ANDROID_SCALE = 0.75
# The themed icon keeps the dark parts of the artwork (hair, glasses, eyes, mouth) and drops the skin and background.
MONO_LUMINANCE = 100
MONO_SOFTNESS = 8
# Android draws the splash icon into a 288dp box and shows only the 192dp circle in its middle. iOS shows the same
# circle at the same size (pt). The logo is a little larger than the circle so the face fills it.
SPLASH_BOX = 288
SPLASH_CIRCLE = 192
SPLASH_LOGO = 212
SPLASH_PX_PER_DP = 3


def check_background():
    match = re.search(r'name="ic_launcher_background">#([0-9A-Fa-f]{6})<', COLORS.read_text())
    expected = "%02X%02X%02X" % BACKGROUND
    if not match or match.group(1).upper() != expected:
        raise SystemExit(f"ic_launcher_background in {COLORS.relative_to(ROOT)} must be #{expected}")


def faded_edges(img, fade):
    """The image with its outer `fade` px faded to transparent, so the square edge never shows over the background."""
    w, h = img.size
    ramp = lambda n: np.minimum(np.minimum(np.arange(n), np.arange(n)[::-1]) / fade, 1.0)
    alpha = (np.outer(ramp(h), ramp(w)) * 255).astype(np.uint8)
    out = img.convert("RGBA")
    out.putalpha(Image.fromarray(alpha))
    return out


def on_canvas(img, size, scale):
    """`img` shrunk to `scale` of a transparent `size` square, centered."""
    inner = round(size * scale)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    offset = (size - inner) // 2
    canvas.alpha_composite(img.resize((inner, inner), Image.LANCZOS), (offset, offset))
    return canvas


def monochrome(src):
    """White where the artwork is dark, transparent elsewhere."""
    rgb = np.asarray(src.convert("RGB")).astype(float)
    luminance = rgb @ [0.2126, 0.7152, 0.0722]
    alpha = np.clip((MONO_LUMINANCE + MONO_SOFTNESS - luminance) / (2 * MONO_SOFTNESS), 0, 1)
    mask = Image.fromarray((alpha * 255).astype(np.uint8)).filter(ImageFilter.MedianFilter(5))
    out = Image.new("RGBA", src.size, (255, 255, 255, 0))
    out.putalpha(mask)
    return out


def splash_circle(src):
    """The logo cut to a SPLASH_CIRCLE-wide circle, transparent around it, at SPLASH_PX_PER_DP."""
    k = SPLASH_PX_PER_DP
    size, logo = SPLASH_CIRCLE * k, SPLASH_LOGO * k
    canvas = Image.new("RGBA", (size, size), (*BACKGROUND, 255))
    offset = (size - logo) // 2
    canvas.paste(src.resize((logo, logo), Image.LANCZOS), (offset, offset))
    ss = 4  # supersampled mask for a smooth edge
    mask = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size * ss - 1, size * ss - 1], fill=255)
    canvas.putalpha(mask.resize((size, size), Image.LANCZOS))
    return canvas


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", type=Path)
    args = parser.parse_args()
    check_background()

    src = Image.open(SOURCE).convert("RGB")

    # iOS: opaque, full bleed
    ios = src.resize((1024, 1024), Image.LANCZOS)
    ios_path = ROOT / "ios/Trustmail/Assets.xcassets/AppIcon.appiconset/icon-1024.png"
    ios_path.parent.mkdir(parents=True, exist_ok=True)
    ios.save(ios_path)

    # Google Play listing icon: 512px, 32-bit PNG (Play applies its own corner mask)
    store = ROOT / "store"
    store.mkdir(exist_ok=True)
    src.resize((512, 512), Image.LANCZOS).convert("RGBA").save(store / "play-store-icon-512.png", optimize=True)

    # Android: transparent layers inside the safe zone
    res = ROOT / "android/app/src/main/res/drawable-nodpi"
    res.mkdir(parents=True, exist_ok=True)
    fg = on_canvas(faded_edges(src, src.width // 12), 512, ANDROID_SCALE)
    fg.save(res / "ic_launcher_foreground.png")
    mono = on_canvas(monochrome(src), 512, ANDROID_SCALE)
    mono.save(res / "ic_launcher_monochrome.png")

    # Splash: iOS gets the circle alone, Android the circle centered in its splash-icon box
    circle = splash_circle(src)
    splash_dir = ROOT / "ios/Trustmail/Assets.xcassets/SplashLogo.imageset"
    splash_dir.mkdir(parents=True, exist_ok=True)
    circle.save(splash_dir / "splash-logo.png", optimize=True)
    box = Image.new("RGBA", (SPLASH_BOX * SPLASH_PX_PER_DP,) * 2, (0, 0, 0, 0))
    box.alpha_composite(circle, ((SPLASH_BOX - SPLASH_CIRCLE) * SPLASH_PX_PER_DP // 2,) * 2)
    box.save(res / "splash_logo.png", optimize=True)

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
        # Android themed (monochrome) icon: light shapes on a dark circle
        themed = Image.new("RGBA", (512, 512), (0x3B, 0x4A, 0x6B, 255))
        tint = Image.new("RGBA", (512, 512), (0xD6, 0xE3, 0xFF, 255))
        themed.paste(tint, mask=mono.split()[3])
        themed.putalpha(circle)
        themed.save(args.preview / "android-themed.png")


if __name__ == "__main__":
    main()
