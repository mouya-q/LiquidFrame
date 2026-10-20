"""
Fast check of PanelScan's core, kept in lockstep with the Kotlin.

The alpha-based scanner this replaces could never fire on a finished photo (alpha is 255
everywhere, so it saw the whole frame and rejected it). These are the cases that matter:
  1. a photo containing a panel -> found, at roughly the right place
  2. a photo with no panel      -> None
  3. a bright uniform sky band   -> rejected, because a sky has no glyph columns
"""
WORK_WIDTH = 160
RUN_TOLERANCE = 12.0
MIN_RUN_FRACTION = 0.42
ROW_MEAN_MIN = 84.0
EDGE_MIN = 14.0
MAX_HEIGHT_FRACTION = 0.35
MIN_HEIGHT_FRACTION = 0.012
GLYPH_DROP_MIN = 34.0
GLYPH_MIN_RUN = 3

W, H = 1200, 800


def luma(p):
    return 0.213 * ((p >> 16) & 0xFF) + 0.715 * ((p >> 8) & 0xFF) + 0.072 * (p & 0xFF)


def grey(v):
    v = max(0, min(255, int(v)))
    return (v << 16) | (v << 8) | v


def rgb(r, g, b):
    return (max(0, min(255, int(r))) << 16) | (max(0, min(255, int(g))) << 8) | max(0, min(255, int(b)))


def build(with_panel=True, with_text=True, with_sky_band=False):
    px = [0] * (W * H)
    for y in range(H):
        t = y / H
        br, bg, bb = int(46 + 118 * t), int(72 + 84 * t), int(146 - 44 * t)
        base = y * W
        for x in range(W):
            n = ((x * 7 + y * 13) % 29) - 14
            e = 26 if (x // 150 + y // 150) % 2 == 0 else -26
            px[base + x] = rgb(br + n + e, bg + n + e, bb + n + e)
    if with_sky_band:
        for y in range(400, 470):
            base = y * W
            for x in range(40, 1160):
                px[base + x] = grey(232 + (((x + y) % 4) - 1))
    if with_panel:
        for y in range(660, 736):
            base = y * W
            for x in range(60, 1140):
                px[base + x] = grey(208 + (((x + y) % 5) - 2))
        if with_text:
            for y in range(684, 712):
                base = y * W
                for x in range(110, 300):
                    if (x // 6) % 3 != 2:
                        px[base + x] = grey(58)
                for x in range(880, 1040):
                    if (x // 6) % 3 != 2:
                        px[base + x] = grey(58)
    return px


def rowstats(px, w, h):
    """Mirror of the Kotlin pass 1: per-row mean and longest near-constant run bounds."""
    step = max(1, w // WORK_WIDTH)
    sw, sh = w // step, h // step
    mean = [0.0] * sh
    runf = [0.0] * sh
    rstart = [0] * sh
    rend = [0] * sh
    for sy in range(sh):
        base = (sy * step) * w
        total = 0.0
        anchor = 0.0
        rl = 0
        rfrom = 0
        best = 0
        bfrom = 0
        bto = 0
        for sx in range(sw):
            v = luma(px[base + sx * step])
            total += v
            if rl == 0:
                anchor = v
                rl = 1
                rfrom = sx
            elif abs(v - anchor) <= RUN_TOLERANCE:
                rl += 1
            else:
                if rl > best:
                    best, bfrom, bto = rl, rfrom, sx - 1
                anchor = v
                rl = 1
                rfrom = sx
        if rl > best:
            best, bfrom, bto = rl, rfrom, sw - 1
        mean[sy] = total / sw
        runf[sy] = best / float(sw)
        rstart[sy] = bfrom
        rend[sy] = bto
    return step, sw, sh, mean, runf, rstart, rend


def find(px, w, h):
    step, sw, sh, mean, runf, rstart, rend = rowstats(px, w, h)
    flat = [runf[i] >= MIN_RUN_FRACTION and mean[i] >= ROW_MEAN_MIN for i in range(sh)]

    def mean_of(a, b):
        return sum(mean[a:b + 1]) / (b - a + 1) if b >= a else 0.0

    def consider(top, bottom):
        if bottom <= top:
            return None
        bm = mean_of(top, bottom)
        if bm < ROW_MEAN_MIN:
            return None
        dev = sum(abs(mean[i] - bm) for i in range(top, bottom + 1))
        n = bottom - top + 1
        cons = 1.0 - min(dev / n, 30.0) / 30.0
        te = abs(bm - (mean[top - 1] if top > 0 else bm))
        if te < EDGE_MIN:
            return None
        if bottom < sh - 1 and abs(bm - mean[bottom + 1]) < EDGE_MIN:
            return None
        return cons * min(te / EDGE_MIN, 2.0)

    best = (0.0, -1, -1)
    run = -1
    for sy in range(sh):
        if flat[sy]:
            if run < 0:
                run = sy
        elif run >= 0:
            sc = consider(run, sy - 1)
            if sc and sc > best[0]:
                best = (sc, run, sy - 1)
            run = -1
    if run >= 0:
        sc = consider(run, sh - 1)
        if sc and sc > best[0]:
            best = (sc, run, sh - 1)
    if best[1] < 0 or best[2] <= best[1]:
        return None

    bt, bb = best[1], best[2]
    rows = bb - bt + 1
    if rows < max(2, int(sh * MIN_HEIGHT_FRACTION)) or rows > sh * MAX_HEIGHT_FRACTION:
        return None

    min_run = max(1, int(sw * MIN_RUN_FRACTION))
    left, right = sw, -1
    for sy in range(bt, bb + 1):
        st, en = rstart[sy], rend[sy]
        if en - st + 1 < min_run:
            continue
        left = min(left, st)
        right = max(right, en)
    if right < left:
        return None
    return (left * step, bt * step, (right - left + 1) * step, rows * step)


def has_label_detail(px, w, h, panel):
    l, t, pw, ph = panel
    left, top = max(0, int(l)), max(0, int(t))
    right, bottom = min(w, int(l + pw)), min(h, int(t + ph))
    if right - left < 16 or bottom - top < 6:
        return False
    tot = n = 0
    for y in range(top, bottom):
        base = y * w
        for x in range(left, right, 2):
            tot += luma(px[base + x])
            n += 1
    if n == 0:
        return False
    tone = tot / n
    dark = cols = 0
    longest = 0
    run = 0
    for x in range(left, right):
        cols += 1
        cmin = 255.0
        for y in range(top, bottom):
            v = luma(px[y * w + x])
            if v < cmin:
                cmin = v
        if tone - cmin > GLYPH_DROP_MIN:
            dark += 1
            run += 1
            longest = max(longest, run)
        else:
            run = 0
    if cols == 0 or longest < GLYPH_MIN_RUN:
        return False
    return dark / float(cols) <= 0.80


px = build(True, True)
r = find(px, W, H)
print('panel     ->', r)
assert r is not None, 'FAIL: panel not found'
l, t, wd, ht = r
assert abs(t - 660) < 45, 'FAIL top %s' % t
assert abs(ht - 76) < 45, 'FAIL height %s' % ht
assert abs(l - 60) < 45, 'FAIL left %s' % l
assert abs(wd - 1080) < 90, 'FAIL width %s' % wd
assert has_label_detail(px, W, H, r), 'FAIL: real panel rejected'

px2 = build(False)
r2 = find(px2, W, H)
print('no panel  ->', r2)
assert r2 is None, 'FAIL false positive: %s' % (r2,)

px3 = build(False, False, with_sky_band=True)
r3 = find(px3, W, H)
print('sky band  ->', r3)
if r3 is not None:
    ok = has_label_detail(px3, W, H, r3)
    print('  hasLabelDetail(sky) =', ok)
    assert not ok, 'FAIL: sky accepted as a panel'

print('PASS')