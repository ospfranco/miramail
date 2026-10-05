#!/usr/bin/env python3
"""
Makes the small preview image on the intro screen: the demo-mode phishing email with its verdict marks and
the shield tab, cropped from the UI-test screenshot of each platform (the "message-scanned" screenshot).

  python3 scripts/generate-intro-preview.py IOS_SCREENSHOT.png ANDROID_SCREENSHOT.png

Writes:
  ios/Trustmail/Assets.xcassets/IntroPreview.imageset/intro-preview.png
  android/app/src/main/res/drawable-nodpi/intro_preview.png

The screenshots come from the UI tests (they save `ios-message-scanned.png` / `android-message-scanned.png`).
Re-run this whenever the email screen changes. Needs Pillow (python3 -m venv .venv && .venv/bin/pip install pillow).
"""
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
WIDTH = 900  # output width in px

# Pixel boxes in the full-size screenshots: what to keep, and the demo-only status line to paint out
# ("Scripts in this email are blocked.", test scaffolding that doesn't belong in an intro image).
CROPS = {
    "ios": {"crop": (0, 380, 1206, 1441), "erase": (0, 1160, 940, 1232)},
    "android": {"crop": (0, 258, 1080, 1284), "erase": (0, 925, 700, 985)},
}


def make(source: Path, spec: dict, out: Path):
    image = Image.open(source).convert("RGB")
    ImageDraw.Draw(image).rectangle(spec["erase"], fill=(255, 255, 255))
    image = image.crop(spec["crop"])
    image = image.resize((WIDTH, round(image.height * WIDTH / image.width)), Image.LANCZOS)
    out.parent.mkdir(parents=True, exist_ok=True)
    image.save(out, optimize=True)
    print(f"{out.relative_to(ROOT)}  {image.width}x{image.height}")


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    ios_dir = ROOT / "ios/Trustmail/Assets.xcassets/IntroPreview.imageset"
    make(Path(sys.argv[1]), CROPS["ios"], ios_dir / "intro-preview.png")
    (ios_dir / "Contents.json").write_text(
        json.dumps(
            {
                "images": [{"filename": "intro-preview.png", "idiom": "universal", "scale": "3x"}],
                "info": {"author": "xcode", "version": 1},
            },
            indent=2,
        )
        + "\n"
    )
    make(Path(sys.argv[2]), CROPS["android"], ROOT / "android/app/src/main/res/drawable-nodpi/intro_preview.png")


if __name__ == "__main__":
    main()
