import os, gc, sys

def uleb128(d, p):
    r = 0; s = 0
    while p < len(d):
        b = d[p]; p += 1
        r |= (b & 0x7f) << s
        if (b & 0x80) == 0: break
        s += 7
        if s > 28: break
    return r, p

def u2(d, p): return d[p] | (d[p+1] << 8)
def u4(d, p): return d[p] | (d[p+1] << 8) | (d[p+2] << 16) | (d[p+3] << 24)

def readstr(d, off):
    _, p = uleb128(d, off)
    out = []
    while p < len(d):
        b = d[p]; p += 1
        if b == 0: break
        if b < 0x80: out.append(chr(b))
        elif (b & 0xE0) == 0xC0:
            if p >= len(d): break
            b2 = d[p]; p += 1
            out.append(chr(((b & 0x1F) << 6) | (b2 & 0x3F)))
        else:
            if p+1 >= len(d): break
            b2 = d[p]; b3 = d[p+1]; p += 2
            out.append(chr(((b & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F)))
    return ''.join(out)

class D:
    def __init__(s, data):
        s.d = data
        s.sc = u4(data, 0x38); s.so = u4(data, 0x3C)
        s.tc = u4(data, 0x40); s.to = u4(data, 0x44)
        s.po = u4(data, 0x4C); s.fo = u4(data, 0x54)
        s.mo = u4(data, 0x5C); s.co = u4(data, 0x64); s.cc = u4(data, 0x60)
        s._s = {}; s._t = {}
    def S(s, i):
        if i in s._s: return s._s[i]
        if i < 0 or i >= s.sc: return None
        off = u4(s.d, s.so + i*4)
        v = readstr(s.d, off) if 0 < off < len(s.d) else None
        s._s[i] = v; return v
    def T(s, i):
        if i in s._t: return s._t[i]
        if i >= s.tc: return '?'
        p = s.to + i*4
        if p+4 > len(s.d): return '?'
        v = s.S(u4(s.d, p)) or '?'
        s._t[i] = v; return v
    def M(s, mi):
        b = s.mo + mi*8
        if b+8 > len(s.d): return '?', '?', [], '?'
        cls = s.T(u2(s.d, b)); pi = u2(s.d, b+2); nm = s.S(u4(s.d, b+4)) or '?'
        pb = s.po + pi*12; rt = '?'; ps = []
        if pb+12 <= len(s.d):
            rt = s.T(u4(s.d, pb+4)); poff = u4(s.d, pb+8)
            if poff and poff+4 <= len(s.d):
                n = u4(s.d, poff)
                for j in range(min(n, 255)):
                    pp = poff + 4 + j*2
                    if pp+2 > len(s.d): break
                    ps.append(s.T(u2(s.d, pp)))
        return cls, nm, ps, rt
    def F(s, fi):
        b = s.fo + fi*8
        if b+8 > len(s.d): return '?', '?'
        ty = s.T(u2(s.d, b+2)); nm = s.S(u4(s.d, b+4)) or '?'
        return nm, ty

# Structural signature of the 6.4 capture-metadata record (la/a):
#   short focal, float aperture, long exposure, int iso, long time, Location gps
# A class is a "capture metadata record" when it declares a Location instance field
# AND at least one float/long/int primitive instance field.
LOC = 'Landroid/location/Location;'
dd = '/tmp/cam670'
hits = []
for fn in sorted(os.listdir(dd)):
    if not fn.endswith('.dex'): continue
    with open(os.path.join(dd, fn), 'rb') as f:
        data = f.read()
    if len(data) < 0x70 or data[0:4] != b'dex\n': continue
    D_ = D(data)
    for ci in range(D_.cc):
        base = D_.co + ci*32
        if base+32 > len(data): break
        cidx = u4(data, base)
        if cidx >= D_.tc: continue
        cname = D_.T(cidx)
        cdo = u4(data, base + 24)
        if cdo == 0 or cdo >= len(data): continue
        p = cdo
        sf, p = uleb128(data, p); ifv, p = uleb128(data, p)
        dm, p = uleb128(data, p); vm, p = uleb128(data, p)
        fi = 0
        for _ in range(sf):
            fi, p = uleb128(data, p); _, p = uleb128(data, p)
        fi = 0
        types = []
        for _ in range(ifv):
            fi, p = uleb128(data, p); _, p = uleb128(data, p)
            n, t = D_.F(fi)
            types.append(t)
        if LOC not in types:
            continue
        prim = [t for t in types if t in ('F', 'J', 'I', 'S', 'D')]
        if not prim:
            continue
        # Also look for the method that consumes this class in this same class or nearby
        hits.append((fn, cname, types, prim))
    del D_
    gc.collect()

print('=== Classes declaring a Location instance field (+ primitives) ===')
for fn, cn, types, prim in hits:
    print('[%s] %s' % (fn, cn))
    print('    field types: %s' % (', '.join(types)))
print('total:', len(hits))