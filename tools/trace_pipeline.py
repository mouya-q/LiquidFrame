"""Trace the 6.7 watermark pipeline: who calls the bitmap factory, and how the
capture record reaches it.

Run:  python3 tools/trace_pipeline.py /tmp/cam670

The obfuscated names differ per build, so this walks invoke instructions instead
of trusting a name list. It answers three questions:

  1. Which class is the capture record (declares Location + primitives + a
     watermark-config object)?
  2. Which method is the static watermark bitmap factory, and who calls it?
  3. Which method runs immediately above the factory on the same thread, so a
     ThreadLocal set there survives into the factory call?

DEX invoke opcodes (0x6e..0x72, 0x74..0x78) are 3 code units wide and carry the
method index in their second unit.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from dexlib import iter_apk_dexes, u2, u4, uleb128  # noqa: E402

INVOKE_OPS = {0x6E, 0x6F, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78}
BITMAP = "Landroid/graphics/Bitmap;"


def iter_code_units(dex, code_off):
    """Yield (index, opcode, next_unit) decoding instruction widths correctly.

    Widths are per the Dalvik bytecode table. The variable-length instructions
    (payloads 0x0100/0x0200/0x0300, switches, fill-array) are the reason a plain
    scan for invoke opcodes finds phantom targets: an operand that happens to be
    0x6e gets read as an invoke. Decoding widths avoids that.
    """
    n = u4(dex.d, code_off + 12)
    base = code_off + 16
    if base + n * 2 > len(dex.d):
        return
    p = 0
    while p < n:
        w = u2(dex.d, base + p * 2)
        op = w & 0xFF

        if op == 0x00:
            ident = u2(dex.d, base + (p + 1) * 2) if p + 1 < n else 0
            if ident == 0x0100:      # packed-switch-payload
                size = u2(dex.d, base + (p + 1) * 2 + 2)
                p += size * 2 + 4
                continue
            if ident == 0x0200:      # sparse-switch-payload
                size = u2(dex.d, base + (p + 1) * 2 + 2)
                p += size * 4 + 2
                continue
            if ident == 0x0300:      # fill-array-data-payload
                elem = u2(dex.d, base + (p + 1) * 2 + 2)
                size = u4(dex.d, base + p * 2 + 4)
                total = (elem * size + 1) // 2
                p += total + 4
                continue
            p += 1
            continue

        if op in INVOKE_OPS:
            tgt = u2(dex.d, base + (p + 1) * 2) if p + 1 < n else 0
            yield p, op, tgt
            p += 3
            continue

        if op in (0xFA, 0xFB):       # invoke-polymorphic / -range
            tgt = u2(dex.d, base + (p + 1) * 2) if p + 1 < n else 0
            yield p, op, tgt
            p += 4
            continue

        p += _width(op)


_W = [1] * 256
for _o in (0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1A, 0x1C, 0x1F,
           0x20, 0x22, 0x23, 0x29, 0xFE, 0xFF):
    _W[_o] = 2
for _o in (0x03, 0x06, 0x09, 0x14, 0x17, 0x1B, 0x24, 0x25, 0x26, 0x2A, 0x2B, 0x2C, 0xFC, 0xFD):
    _W[_o] = 3
_W[0x18] = 5
for _o in range(0x2D, 0x3E):
    _W[_o] = 2
for _o in range(0x44, 0x6E):
    _W[_o] = 2
for _o in range(0x90, 0xB0):
    _W[_o] = 2
for _o in range(0xD0, 0xE3):
    _W[_o] = 2


def _width(op):
    return _W[op]


def class_data(dex, cdo):
    """Returns (fields, methods) where methods carry their code_off."""
    fields = []
    methods = []
    p = cdo
    sf, p = uleb128(dex.d, p)
    ifv, p = uleb128(dex.d, p)
    dm, p = uleb128(dex.d, p)
    vm, p = uleb128(dex.d, p)

    idx = 0
    for _ in range(sf):
        diff, p = uleb128(dex.d, p)
        idx += diff
        _, p = uleb128(dex.d, p)
        n, t = dex.field_ref(idx)
        fields.append((n, t, True))
    idx = 0
    for _ in range(ifv):
        diff, p = uleb128(dex.d, p)
        idx += diff
        _, p = uleb128(dex.d, p)
        n, t = dex.field_ref(idx)
        fields.append((n, t, False))
    idx = 0
    for _ in range(dm):
        diff, p = uleb128(dex.d, p)
        idx += diff
        acc, p = uleb128(dex.d, p)
        co, p = uleb128(dex.d, p)
        mr = dex.method_ref(idx)
        if mr:
            mr.is_static = bool(acc & 0x8)
            methods.append((mr, co))
    idx = 0
    for _ in range(vm):
        diff, p = uleb128(dex.d, p)
        idx += diff
        acc, p = uleb128(dex.d, p)
        co, p = uleb128(dex.d, p)
        mr = dex.method_ref(idx)
        if mr:
            mr.is_static = bool(acc & 0x8)
            methods.append((mr, co))
    return fields, methods


def main():
    source = sys.argv[1] if len(sys.argv) > 1 else "/tmp/cam670"
    targets = sys.argv[2:] or [
        "Lcom/xiaomi/cam/watermark/b;",
        "Lcom/xiaomi/cam/watermark/a;",
        "LKi/a;",
    ]

    edges = []          # (callee_sig, caller_sig, caller_is_static)
    class_index = {}    # class -> (fields, methods)

    for label, dex in iter_apk_dexes(source):
        for ci in range(dex.class_count):
            base = dex.class_defs_off + ci * 32
            if base + 32 > len(dex.d):
                break
            cidx = u4(dex.d, base)
            if cidx >= dex.type_count:
                continue
            cname = dex.type_name(cidx)
            cdo = u4(dex.d, base + 24)
            if cdo == 0 or cdo >= len(dex.d):
                continue
            fields, methods = class_data(dex, cdo)
            class_index[cname] = (fields, methods, label)
            for mr, co in methods:
                if co == 0 or co >= len(dex.d):
                    continue
                for pos, op, tgt in iter_code_units(dex, co):
                    callee = dex.method_ref(tgt)
                    if callee is None:
                        continue
                    edges.append((callee, mr, pos))

    print("classes: %d   invoke edges: %d" % (len(class_index), len(edges)))

    print("\n=== callers of the watermark bitmap factory ===")
    for tcls in targets:
        if not tcls.startswith("Lcom/xiaomi/cam/watermark/"):
            continue
        for callee, caller, pos in edges:
            if callee.cls != tcls:
                continue
            if callee.ret != BITMAP or BITMAP not in callee.params:
                continue
            idx = callee.params.index(BITMAP)
            print("  callee: %s" % callee.signature())
            print("          static=%s bitmapIdx=%d" % (callee.is_static, idx))
            print("  caller: %s   (PC %d)  static=%s"
                  % (caller.signature(), pos, caller.is_static))
            # Report the caller's own declared fields, which is how metadata is threaded
            owner = caller.cls
            if owner in class_index:
                f, _, lab = class_index[owner]
                inst = [t for (n, t, st) in f if not st]
                if inst:
                    print("          caller fields (%s): %s" % (lab, ", ".join(inst)))
            print("")

    print("=== who feeds the capture record ===")
    for callee, caller, pos in edges:
        if callee.cls in targets and not callee.cls.startswith("Lcom/xiaomi/cam/watermark/"):
            print("  %s  <-  %s" % (callee.signature(), caller.signature()))


if __name__ == "__main__":
    main()