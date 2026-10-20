#!/usr/bin/env python3
"""Measure the bottom capsule watermark panel in a reference photo.

The panel is a translucent glass capsule: it has a bright rim on its outline, so the
outline is found as a local brightness ridge rather than as a colour change.
"""
import sys

W = 832


def load(path):
    d = open(path, "rb").read()
    h = len(d) // (W * 4)
    return d, h


def luma(d, w, x, y):
    i = (y * w + x) * 4
    return 0.213 * d[i] + 0.715 * d[i + 1] + 0.072 * d[i + 2]


def main(path, label):
    d, h = load(path)
    print("=== %s  %dx%d ===" % (label, W, h))

    # ---- vertical extent: scan the left-margin column for the rim ridge ----------
    # The rim is a local maximum against its vertical neighbours.
    x_probe = max(2, W // 100)
    rows = []
    for y in range(int(h * 0.60), h):
        c = luma(d, W, x_probe, y)
        up = luma(d, W, x_probe, y - 2)
        dn = luma(d, W, x_probe, y + 2) if y + 2 < h else c
        if c - up > 12 and c - dn > 12:
            rows.append(y)
    print("rim rows at x=%d: %s" % (x_probe, rows[:12]))

    # ---- horizontal extent: scan a row inside the panel for left/right rim -------
    # Take a row a third of the way down the detected band.
    if len(rows) >= 2:
        y_mid = (rows[0] + rows[-1]) // 2
    else:
        y_mid = int(h * 0.90)
    cols = []
    for x in range(2, W - 2):
        c = luma(d, W, x, y_mid)
        lf = luma(d, W, x - 2, y_mid)
        rt = luma(d, W, x + 2, y_mid)
        if c - lf > 10 and c - rt > 10:
            cols.append(x)
    print("rim cols at y=%d: %s" % (y_mid, cols[:8] + ["..."] + cols[-8:]))

    # ---- summary -----------------------------------------------------------------
    if len(rows) >= 2:
        top, bot = rows[0], rows[-1]
        print("panel top=%d bottom=%d height=%d (%.3f of h)" % (top, bot, bot - top, (bot - top) / h))
        print("bottom margin=%d (%.3f of h)" % (h - 1 - bot, (h - 1 - bot) / h))
    if cols:
        left, right = cols[0], cols[-1]
        print("panel left=%d right=%d width=%d (%.3f of w)" % (left, right, right - left, (right - left) / W))
        print("side margin=%d (%.3f of w)" % (left, left / W))
        if len(rows) >= 2:
            hh = rows[-1] - rows[0]
            print("radius / height = %.3f" % (min(left, W - right) * 1.0 / max(1, hh)))
    print()


for p in sys.argv[1:]:
    main(p, p.split("/")[-1])