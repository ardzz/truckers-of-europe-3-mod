#!/usr/bin/env python3
"""Render the README hero and square cover from the reference-based truck artwork.

Requires Pillow and fontconfig with DejaVu Sans fonts. Run from any directory:
    python3 assets/artwork/render.py
The background is AI-assisted promotional artwork, not a gameplay capture.
"""
from pathlib import Path
import subprocess

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
WHITE = "#f6f1e6"
GOLD = "#ffd884"
MUTED = "#b8c7d6"


def font(size, bold=False):
    family = "DejaVu Sans Condensed:style=Bold" if bold else "DejaVu Sans:style=Book"
    path = subprocess.check_output(["fc-match", family, "-f", "%{file}"], text=True).strip()
    return ImageFont.truetype(path, size)


def text(draw, xy, label, size, fill=WHITE, bold=False, tracking=0):
    face = font(size, bold)
    x, y = xy
    if tracking:
        baseline = y - draw.textbbox((0, 0), label, font=face, anchor="ls")[1]
        for char in label:
            draw.text((x, baseline), char, font=face, fill=fill, anchor="ls")
            x += draw.textlength(char, font=face) + tracking
    else:
        draw.text((x, y), label, font=face, fill=fill, anchor="lt")


def tint(image, alpha):
    layer = Image.new("RGBA", image.size, (8, 15, 24, 0))
    layer.putalpha(alpha)
    return Image.alpha_composite(image.convert("RGBA"), layer).convert("RGB")


def render():
    scene = Image.open(HERE / "truck-rain-background.webp").convert("RGB")
    if scene.size != (1916, 821):
        raise ValueError("Artwork composition expects the reviewed 1916x821 background")

    # Keep the truck clear; darken only the headline column for legibility.
    column = Image.new("L", (scene.width, 1))
    column.putdata([round(205 * max(0, min(1, (1050 - x) / 800))) for x in range(scene.width)])
    hero = tint(scene, column.resize(scene.size))
    draw = ImageDraw.Draw(hero)
    draw.rectangle((112, 102, 176, 107), fill=GOLD)
    text(draw, (112, 135), "TRUCKERS OF EUROPE 3", 28, tracking=3)
    text(draw, (103, 225), "STORM", 151, bold=True)
    text(draw, (105, 396), "LINE", 165, GOLD, bold=True)
    text(draw, (112, 610), "RAIN ON. DIRT OFF.", 37, tracking=1)
    text(draw, (112, 741), "UNOFFICIAL ANDROID MOD", 23, MUTED, tracking=2)
    hero.save(HERE / "mod-hero.webp", quality=94, method=6)

    # A separate cab-focused crop keeps the square cover readable as a thumbnail.
    cover = scene.crop((900, 0, 1721, 821)).resize((1024, 1024), Image.Resampling.LANCZOS)
    shade = Image.new("L", (1, 1024))
    shade.putdata([
        round(max(116 * max(0, min(1, (300 - y) / 150)),
                  235 * max(0, min(1, (y - 525) / 390))))
        for y in range(1024)
    ])
    cover = tint(cover, shade.resize(cover.size))
    draw = ImageDraw.Draw(cover)
    text(draw, (63, 56), "TOE3", 74, GOLD, bold=True)
    text(draw, (60, 789), "STORMLINE", 101, bold=True)
    text(draw, (64, 927), "UNOFFICIAL MOD ARTWORK", 25, MUTED, tracking=1)
    cover.quantize(colors=256).save(HERE / "mod-cover.png", optimize=True)

    icon = scene.crop((940, 20, 1741, 821)).resize((512, 512), Image.Resampling.LANCZOS)
    draw = ImageDraw.Draw(icon)
    draw.rounded_rectangle((136, 377, 376, 467), radius=16, fill=GOLD, outline="#0e1a27", width=3)
    draw.text((256, 423), "MOD", font=font(82, bold=True), fill="#0e1a27", anchor="mm")
    icon.save(HERE / "launcher-icon.png", optimize=True)

    for name in ("mod-hero.webp", "mod-cover.png", "launcher-icon.png"):
        path = HERE / name
        with Image.open(path) as image:
            print(f"{name}: {image.width}x{image.height}, {path.stat().st_size:,} bytes")


if __name__ == "__main__":
    render()
