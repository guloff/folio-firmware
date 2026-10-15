#!/usr/bin/env python3
"""Turn pictures into Folio sleep-screen BMPs for the X4 Pro.

Each input is center-cropped to the panel's 3:5 portrait shape, resized to
480x800, dithered (Floyd-Steinberg) to the panel's four gray levels
0/85/170/255 — the same levels FolioLink's screensaver studio uses — and
written as a 24-bit BMP, the format the studio uploads. A PNG preview is
written next to it.

Usage: python3 tools/inklink/make_sleep_bmp.py OUT_DIR IMAGE[=NAME] ...
Needs Pillow.
"""
import sys
from pathlib import Path

from PIL import Image

WIDTH, HEIGHT = 480, 800
LEVELS = (0, 85, 170, 255)


def crop_to_panel(img: Image.Image) -> Image.Image:
    target = WIDTH / HEIGHT
    w, h = img.size
    if w / h > target:
        new_w = round(h * target)
        left = (w - new_w) // 2
        return img.crop((left, 0, left + new_w, h))
    new_h = round(w / target)
    top = (h - new_h) // 2
    return img.crop((0, top, w, top + new_h))


def dither(img: Image.Image) -> Image.Image:
    gray = img.convert("L")
    palette = Image.new("P", (1, 1))
    flat = []
    for level in LEVELS:
        flat += [level, level, level]
    palette.putpalette(flat + [0] * (768 - len(flat)))
    return gray.convert("RGB").quantize(palette=palette, dither=Image.Dither.FLOYDSTEINBERG).convert("RGB")


def main() -> None:
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for arg in sys.argv[2:]:
        src, _, name = arg.partition("=")
        name = name or Path(src).stem
        img = Image.open(src)
        img = crop_to_panel(img.convert("RGB")).resize((WIDTH, HEIGHT), Image.LANCZOS)
        result = dither(img)
        result.save(out / f"{name}.bmp", format="BMP")
        result.save(out / f"{name}.png", format="PNG")
        print(f"{name}.bmp")


if __name__ == "__main__":
    main()
