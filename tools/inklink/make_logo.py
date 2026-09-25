#!/usr/bin/env python3
"""Draw the Folio 120x120 1-bit logo and write src/images/Logo120.h.

The glyph: an open book seen from the front (two curved pages on a spine) with
a bookmark ribbon. Rendered with 4x supersampling, then thresholded.
Usage: python3 tools/inklink/make_logo.py [--rotate N] [--png out.png]
The panel blit is not rotated by the renderer, so --rotate (quarter turns
clockwise) maps the logical drawing into panel orientation.
"""
import argparse
import math
import struct
import zlib

N = 120
SS = 4
S = N * SS


def draw():
    img = [[0] * S for _ in range(S)]  # 1 = ink

    def fill_poly(pts):
        ys = [p[1] for p in pts]
        for y in range(max(0, int(min(ys))), min(S, int(max(ys)) + 1)):
            xs = []
            for i in range(len(pts)):
                (x1, y1), (x2, y2) = pts[i], pts[(i + 1) % len(pts)]
                if (y1 <= y < y2) or (y2 <= y < y1):
                    xs.append(x1 + (y - y1) * (x2 - x1) / (y2 - y1))
            xs.sort()
            for a, b in zip(xs[::2], xs[1::2]):
                for x in range(max(0, int(a)), min(S, int(b) + 1)):
                    img[y][x] = 1

    def page(side):
        # Outer page edge curves up towards the spine; built as a polygon.
        cx = S / 2
        top_out, top_in = 0.30 * S, 0.24 * S
        bot_out, bot_in = 0.80 * S, 0.74 * S
        pts = []
        steps = 40
        for i in range(steps + 1):  # top edge from spine to outer
            t = i / steps
            x = cx + side * (0.02 * S + t * 0.40 * S)
            y = top_in + (top_out - top_in) * t - 0.05 * S * math.sin(math.pi * t)
            pts.append((x, y))
        for i in range(steps, -1, -1):  # bottom edge back to spine
            t = i / steps
            x = cx + side * (0.02 * S + t * 0.40 * S)
            y = bot_in + (bot_out - bot_in) * t - 0.05 * S * math.sin(math.pi * t)
            pts.append((x, y))
        return pts

    def stroke_page(side, width):
        outer = page(side)
        fill_poly(outer)
        # Hollow it: shrink towards its centroid.
        cx = sum(p[0] for p in outer) / len(outer)
        cy = sum(p[1] for p in outer) / len(outer)
        inner = []
        for x, y in outer:
            dx, dy = x - cx, y - cy
            d = math.hypot(dx, dy) or 1
            inner.append((x - dx / d * width, y - dy / d * width))
        erase(inner)

    def erase(pts):
        ys = [p[1] for p in pts]
        for y in range(max(0, int(min(ys))), min(S, int(max(ys)) + 1)):
            xs = []
            for i in range(len(pts)):
                (x1, y1), (x2, y2) = pts[i], pts[(i + 1) % len(pts)]
                if (y1 <= y < y2) or (y2 <= y < y1):
                    xs.append(x1 + (y - y1) * (x2 - x1) / (y2 - y1))
            xs.sort()
            for a, b in zip(xs[::2], xs[1::2]):
                for x in range(max(0, int(a)), min(S, int(b) + 1)):
                    img[y][x] = 0

    w = 0.055 * S
    stroke_page(-1, w)
    stroke_page(1, w)
    # Text lines on each page.
    for side in (-1, 1):
        for k in range(3):
            y0 = 0.40 * S + k * 0.10 * S
            x0 = S / 2 + side * 0.10 * S
            x1 = S / 2 + side * 0.33 * S
            lo, hi = sorted((x0, x1))
            fill_poly([(lo, y0), (hi, y0 + side * 0.012 * S), (hi, y0 + 0.025 * S + side * 0.012 * S), (lo, y0 + 0.025 * S)])
    # Bookmark ribbon hanging from the right page top.
    rx = S / 2 + 0.22 * S
    fill_poly([(rx, 0.12 * S), (rx + 0.08 * S, 0.12 * S), (rx + 0.08 * S, 0.36 * S), (rx + 0.04 * S, 0.31 * S), (rx, 0.36 * S)])
    # Spine.
    fill_poly([(S / 2 - 0.012 * S, 0.24 * S), (S / 2 + 0.012 * S, 0.24 * S), (S / 2 + 0.012 * S, 0.76 * S), (S / 2 - 0.012 * S, 0.76 * S)])

    out = [[0] * N for _ in range(N)]
    for y in range(N):
        for x in range(N):
            acc = sum(img[y * SS + j][x * SS + i] for j in range(SS) for i in range(SS))
            out[y][x] = 1 if acc * 2 >= SS * SS else 0
    return out


def rotate(m, q):
    for _ in range(q % 4):
        m = [[m[N - 1 - x][y] for x in range(N)] for y in range(N)]
    return m


def write_png(path, m):
    raw = b"".join(b"\x00" + bytes(0 if v else 255 for v in row) for row in m)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", N, N, 8, 0, 0, 0, 0)) +
                chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rotate", type=int, default=0)
    ap.add_argument("--png")
    ap.add_argument("--out", default="src/images/Logo120.h")
    a = ap.parse_args()
    logical = draw()
    if a.png:
        write_png(a.png, logical)
    m = rotate(logical, a.rotate)
    data = []
    for y in range(N):
        for xb in range(N // 8):
            byte = 0
            for bit in range(8):
                if not m[y][xb * 8 + bit]:  # 1 bits are white on this panel
                    byte |= 0x80 >> bit
            data.append(byte)
    lines = []
    for i in range(0, len(data), 19):
        lines.append("    " + ", ".join(f"0x{v:02x}" for v in data[i:i + 19]) + ",")
    with open(a.out, "w") as f:
        f.write("#pragma once\n#include <cstdint>\n\n// Folio logo (tools/inklink/make_logo.py). Image dimensions: 120x120\n")
        f.write("static const uint8_t Logo120[] = {\n" + "\n".join(lines) + "\n};\n")
    print("wrote", a.out)


if __name__ == "__main__":
    main()
