#!/usr/bin/env python3
"""Draw the scene-marker block textures: one 16x16 PNG per MarkerRole, in the manner of the
structure block (charcoal frame, grey field, a coloured glyph and coloured corner studs).

Pure Python (zlib + struct), no PIL. Re-run after changing a glyph or a colour; the colours are
the ones MarkerRole carries, repeated here because the texture must exist before the game does.

    python3 scripts/gen_marker_textures.py
"""
import struct
import zlib
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "common/src/testmod/resources/assets/worlddriver/textures/block"

# MarkerRole.color(), in enum order.
ROLES = {
    "origin": 0xFFFFFF,
    "start": 0x3CDC3C,
    "goal": 0x3C78FF,
    "forbid": 0xE03030,
    "stand": 0xF0E040,
    "watch": 0xB050E0,
    "via": 0x40D8D8,
    "pass": 0xF09030,
    "corner": 0x909090,
}

# 8x8 glyphs drawn in the role colour at the centre of the face. 'C' = colour, '.' = field.
GLYPHS = {
    "origin": [  # crosshair
        "...CC...",
        "...CC...",
        "...CC...",
        "CCCCCCCC",
        "CCCCCCCC",
        "...CC...",
        "...CC...",
        "...CC...",
    ],
    "start": [  # play triangle, pointing the way the body faces
        "CC......",
        "CCCC....",
        "CCCCCC..",
        "CCCCCCCC",
        "CCCCCCCC",
        "CCCCCC..",
        "CCCC....",
        "CC......",
    ],
    "goal": [  # target ring
        "..CCCC..",
        ".C....C.",
        "C..CC..C",
        "C.CCCC.C",
        "C.CCCC.C",
        "C..CC..C",
        ".C....C.",
        "..CCCC..",
    ],
    "forbid": [  # cross
        "CC....CC",
        ".CC..CC.",
        "..CCCC..",
        "...CC...",
        "...CC...",
        "..CCCC..",
        ".CC..CC.",
        "CC....CC",
    ],
    "stand": [  # arrow down onto the ground line
        "...CC...",
        "...CC...",
        "...CC...",
        ".CCCCCC.",
        "..CCCC..",
        "...CC...",
        "........",
        "CCCCCCCC",
    ],
    "watch": [  # eye
        "........",
        "..CCCC..",
        ".C....C.",
        "C..CC..C",
        "C..CC..C",
        ".C....C.",
        "..CCCC..",
        "........",
    ],
    "via": [  # waypoint diamond
        "...CC...",
        "..CCCC..",
        ".CCCCCC.",
        "CCCCCCCC",
        "CCCCCCCC",
        ".CCCCCC.",
        "..CCCC..",
        "...CC...",
    ],
    "pass": [  # check mark
        ".......C",
        "......CC",
        ".....CC.",
        "C...CC..",
        "CC.CC...",
        ".CCCC...",
        "..CC....",
        "........",
    ],
    "corner": [  # bounding-box bracket
        "CC......",
        "CC......",
        "CC......",
        "CC......",
        "CC......",
        "CC......",
        "CCCCCCCC",
        "CCCCCCCC",
    ],
}

FRAME = 0x262626      # 1 px outer border
BEVEL = 0x4A4A4A      # 1 px ring inside the border
FIELD = 0x5C5C5C      # the face
TRACE = 0x6B6B6B      # the command-block-style dot lattice on the field


def shade(rgb, k):
    r, g, b = (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255
    return (int(r * k) << 16) | (int(g * k) << 8) | int(b * k)


def face(role):
    colour = ROLES[role]
    dark = shade(colour, 0.45)
    px = [[FIELD] * 16 for _ in range(16)]
    for y in range(16):
        for x in range(16):
            if x in (0, 15) or y in (0, 15):
                px[y][x] = FRAME
            elif x in (1, 14) or y in (1, 14):
                px[y][x] = BEVEL
            elif x % 3 == 1 and y % 3 == 1:
                px[y][x] = TRACE
    # corner studs, the structure block's tell
    for cy, cx in ((2, 2), (2, 12), (12, 2), (12, 12)):
        for dy in range(2):
            for dx in range(2):
                px[cy + dy][cx + dx] = dark
    glyph = GLYPHS[role]
    # shadow first, one pixel down-right, then the glyph
    for gy, row in enumerate(glyph):
        for gx, ch in enumerate(row):
            if ch == "C":
                px[4 + gy + 1][min(15, 4 + gx + 1)] = dark
    for gy, row in enumerate(glyph):
        for gx, ch in enumerate(row):
            if ch == "C":
                px[4 + gy][4 + gx] = colour
    return px


def png(px):
    raw = b"".join(
        b"\x00" + b"".join(struct.pack(">BBBB", (c >> 16) & 255, (c >> 8) & 255, c & 255, 255) for c in row)
        for row in px)

    def chunk(tag, body):
        return struct.pack(">I", len(body)) + tag + body + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for role in ROLES:
        (OUT / f"marker_{role}.png").write_bytes(png(face(role)))
        print("wrote", OUT / f"marker_{role}.png")


if __name__ == "__main__":
    main()
