#!/usr/bin/env python3
"""Turn a supplied icon artwork PNG into an Android launcher-icon bitmap.

Why this exists
---------------
The launcher icon for LiquidFrame is supplied as a rendered PNG (rounded card
plus the brand line work).  Adaptive icons need a *bitmap* foreground layer, and
shipping the 1254x1254 original would waste ~1.2 MB in the APK for pixels no
launcher ever samples.  This script crops the artwork to its opaque bounding box
and box-downsamples it to 432 px (108 dp at 4x density) with premultiplied-alpha
averaging, so dark line work does not pick up a dark halo from the transparent
corners while being scaled down.

No third-party imaging library is used: the PNG is decoded and encoded with the
standard library only (zlib + struct), which is all the build environment has.

Usage
-----
    python3 tools/icon_from_png.py <input.png> <output.png> [--size 432]
                                   [--pad 0.01] [--mode crop|canvas]

`--mode crop` (default) trims to the artwork's opaque bounding box and centres
the result; use it when the artwork *is* the whole glyph.

`--mode canvas` keeps the original square canvas, transparent margins included.
That is the right mode for an adaptive-icon foreground: the supplied artwork
already places its line work inside the 72/108 dp safe area, so preserving the
canvas margins is what keeps the mark from being clipped by the launcher mask.

Output: RGBA8, non-interlaced PNG.
"""

from __future__ import annotations

import struct
import sys
import zlib

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def _read_chunks(data: bytes):
    if data[:8] != PNG_SIGNATURE:
        raise SystemExit("not a PNG file")
    offset = 8
    while offset + 8 <= len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        kind = data[offset + 4:offset + 8]
        body = data[offset + 8:offset + 8 + length]
        yield kind, body
        if kind == b"IEND":
            return
        offset += 12 + length


def decode_rgba(path: str):
    """Decode a non-interlaced 8-bit RGBA PNG into (width, height, rows)."""
    with open(path, "rb") as handle:
        data = handle.read()

    idat = bytearray()
    width = height = 0
    bit_depth = color_type = interlace = -1
    for kind, body in _read_chunks(data):
        if kind == b"IHDR":
            width, height, bit_depth, color_type, _comp, _filt, interlace = struct.unpack(
                ">IIBBBBB", body[:13]
            )
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break

    if bit_depth != 8 or color_type != 6:
        raise SystemExit(
            f"expected 8-bit RGBA (bit depth 8, color type 6); got {bit_depth}/{color_type}"
        )
    if interlace != 0:
        raise SystemExit("interlaced PNGs are not supported")

    raw = zlib.decompress(bytes(idat))
    bpp = 4
    stride = width * bpp
    if len(raw) != height * (stride + 1):
        raise SystemExit(
            f"unexpected IDAT size: {len(raw)} != {height * (stride + 1)}"
        )

    rows: list[bytearray] = []
    previous = bytearray(stride)
    offset = 0
    for _ in range(height):
        filter_type = raw[offset]
        offset += 1
        line = bytearray(raw[offset:offset + stride])
        offset += stride
        if filter_type == 1:  # Sub
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif filter_type == 2:  # Up
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 0xFF
        elif filter_type == 3:  # Average
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 0xFF
        elif filter_type == 4:  # Paeth
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                up_left = previous[i - bpp] if i >= bpp else 0
                up = previous[i]
                estimate = left + up - up_left
                dist_left = abs(estimate - left)
                dist_up = abs(estimate - up)
                dist_up_left = abs(estimate - up_left)
                if dist_left <= dist_up and dist_left <= dist_up_left:
                    predictor = left
                elif dist_up <= dist_up_left:
                    predictor = up
                else:
                    predictor = up_left
                line[i] = (line[i] + predictor) & 0xFF
        elif filter_type != 0:
            raise SystemExit(f"unknown PNG filter type {filter_type}")
        rows.append(line)
        previous = line

    return width, height, rows


def encode_rgba(path: str, width: int, height: int, rows: list[bytes]) -> None:
    """Encode rows of RGBA8 (unfiltered, filter type 0) as a PNG."""
    raw = bytearray()
    for row in rows:
        raw.append(0)
        raw += row
    compressed = zlib.compress(bytes(raw), 9)

    def chunk(kind: bytes, body: bytes) -> bytes:
        return (
            struct.pack(">I", len(body))
            + kind
            + body
            + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    with open(path, "wb") as handle:
        handle.write(PNG_SIGNATURE)
        handle.write(chunk(b"IHDR", header))
        handle.write(chunk(b"IDAT", compressed))
        handle.write(chunk(b"IEND", b""))


def opaque_bounds(width: int, height: int, rows, threshold: int = 8):
    """Bounding box of pixels whose alpha exceeds `threshold`."""
    top = bottom = None
    left = width
    right = -1
    for y in range(height):
        row = rows[y]
        row_left = None
        row_right = None
        for x in range(width):
            if row[x * 4 + 3] > threshold:
                if row_left is None:
                    row_left = x
                row_right = x
        if row_left is None:
            continue
        if top is None:
            top = y
        bottom = y
        if row_left < left:
            left = row_left
        if row_right > right:
            right = row_right
    if top is None:
        raise SystemExit("artwork is fully transparent")
    return top, bottom, left, right


def resample_box(
    rows,
    src_w: int,
    src_h: int,
    dst_w: int,
    dst_h: int,
    box_left: int,
    box_top: int,
    box_w: int,
    box_h: int,
) -> list[bytes]:
    """Area-average a source rectangle into dst_w x dst_h, premultiplying alpha."""
    # Precompute per-destination-column source coverage once; the same weights are
    # reused for every row, which keeps the inner loop cheap in pure Python.
    def coverage(count: int, span: int):
        table = []
        for index in range(count):
            start = index * span / count
            end = (index + 1) * span / count
            begin = int(start)
            finish = min(int(end) + 1, span)
            weights = []
            for source in range(begin, finish):
                overlap = min(end, source + 1) - max(start, source)
                if overlap > 1e-9:
                    weights.append((source, overlap))
            table.append(weights)
        return table

    columns = coverage(dst_w, box_w)
    lines = coverage(dst_h, box_h)

    out: list[bytes] = []
    for dest_y in range(dst_h):
        accum = [[0.0, 0.0, 0.0, 0.0] for _ in range(dst_w)]
        for source_y, row_weight in lines[dest_y]:
            row = rows[box_top + source_y]
            for dest_x in range(dst_w):
                cell = accum[dest_x]
                for source_x, col_weight in columns[dest_x]:
                    weight = row_weight * col_weight
                    base = (box_left + source_x) * 4
                    alpha = row[base + 3]
                    if alpha == 0:
                        continue
                    # Premultiply so transparent neighbours cannot darken the result.
                    scale = weight * alpha / 255.0
                    cell[0] += row[base] * scale
                    cell[1] += row[base + 1] * scale
                    cell[2] += row[base + 2] * scale
                    cell[3] += weight * alpha
        row_bytes = bytearray(dst_w * 4)
        for x in range(dst_w):
            red, green, blue, alpha_sum = accum[x]
            alpha = alpha_sum
            if alpha <= 0.0:
                continue
            # Un-premultiply back to straight RGBA.
            offset = x * 4
            row_bytes[offset] = min(255, int(red / alpha * 255.0 + 0.5))
            row_bytes[offset + 1] = min(255, int(green / alpha * 255.0 + 0.5))
            row_bytes[offset + 2] = min(255, int(blue / alpha * 255.0 + 0.5))
            row_bytes[offset + 3] = min(255, int(alpha + 0.5))
        out.append(bytes(row_bytes))
    return out


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__)
        return 2

    source = argv[1]
    destination = argv[2]
    size = 432
    pad_ratio = 0.01
    mode = "crop"
    index = 3
    while index < len(argv):
        if argv[index] == "--size":
            size = int(argv[index + 1])
            index += 2
        elif argv[index] == "--pad":
            pad_ratio = float(argv[index + 1])
            index += 2
        elif argv[index] == "--mode":
            mode = argv[index + 1]
            if mode not in ("crop", "canvas"):
                raise SystemExit(f"unknown mode {mode!r}")
            index += 2
        else:
            raise SystemExit(f"unknown argument {argv[index]!r}")

    width, height, rows = decode_rgba(source)

    if mode == "canvas":
        if width != height:
            raise SystemExit(f"--mode canvas needs a square source; got {width}x{height}")
        box_left = box_top = 0
        box_w = box_h = width
        print(f"source {width}x{height}  keeping full canvas")
    else:
        top, bottom, left, right = opaque_bounds(width, height, rows)

        box_w = right - left + 1
        box_h = bottom - top + 1
        pad_x = int(round(box_w * pad_ratio))
        pad_y = int(round(box_h * pad_ratio))
        box_left = max(0, left - pad_x)
        box_top = max(0, top - pad_y)
        box_w = min(width - box_left, box_w + 2 * pad_x)
        box_h = min(height - box_top, box_h + 2 * pad_y)

        print(f"source {width}x{height}  artwork box {(box_left, box_top, box_w, box_h)}")

        # Keep the artwork square so the adaptive-icon foreground is not distorted:
        # pad the shorter axis symmetrically inside the already-cropped box.
        side = max(box_w, box_h)
        pad_w = side - box_w
        pad_h = side - box_h
        box_left = max(0, box_left - pad_w // 2)
        box_top = max(0, box_top - pad_h // 2)
        box_w = min(width - box_left, side)
        box_h = min(height - box_top, side)
        if box_w != box_h:
            # The artwork sits against a canvas edge; shrink the longer axis instead.
            side = min(box_w, box_h)
            box_left += (box_w - side) // 2
            box_top += (box_h - side) // 2
            box_w = box_h = side

    print(f"box {(box_left, box_top, box_w, box_h)} -> {size}x{size}")

    pixels = resample_box(
        rows, width, height, size, size, box_left, box_top, box_w, box_h
    )
    encode_rgba(destination, size, size, pixels)
    print(f"wrote {destination} ({size}x{size} RGBA8)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))