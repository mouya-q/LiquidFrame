#!/usr/bin/env python3
"""Verify the structural fingerprints planned for the LiquidFrame 6.7 deep-adaptation hooks.

Checks, across every classes*.dex of a camera APK (extracted dex dir or the APK itself):

  1. data interface  (gi/e): zero-arg methods returning Z, InputStream, [B, I, V
                            and no byte[] ctor / [B instance field.
  2. byte[] impl     (gi/a): ctor ([B)V + [B instance field + ()InputStream method.
  3. task data class (di/s): has (Lgi/e;)V + (Lgi/e;I)V and a method whose params
                            contain Lcom/xiaomi/cam/watermark/WatermarkRemover$b;.
  4. jpeg enc helper (wr/j.k): static/instance (I, Bitmap)Lgi/e;.
  5. live recorder   (rm/b.o): 9 params, [2]=Ljava/lang/Object;, [6]=String, ret Z.
  6. named: com/xiaomi/gl/texture/Jpeg bitmapToJpeg / bitmapToJpegBuffer shapes.
  7. named: com/xiaomi/camera/bean/CloudWmAttribute ctor (String, [B)V.

Usage: python3 tools/verify_new_matchers.py <apk-or-dex-dir>
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import dex2text  # noqa: E402


def iter_dex_paths(src):
    if os.path.isdir(src):
        for n in sorted(os.listdir(src)):
            if n.startswith("classes") and n.endswith(".dex"):
                yield os.path.join(src, n)
    elif src.lower().endswith(".apk") or ".apk" in os.path.basename(src):
        import zipfile
        with zipfile.ZipFile(src) as zf:
            for n in sorted(zf.namelist()):
                if n.startswith("classes") and n.endswith(".dex"):
                    yield zf.getInputStream and None  # placeholder, replaced below
    # (APK path handled by dex2text.iter_dexes directly)


def short_params(params):
    return tuple(params)


def main():
    src = sys.argv[1]
    data_iface = []      # 1
    byte_impls = []      # 2
    task_classes = []    # 3
    enc_helpers = []     # 4
    recorder_snaps = []  # 5
    jpeg_statics = []    # 6
    cloudwm_ctors = []   # 7

    for label, data in dex2text.iter_dexes(src):
        dex = dex2text.DexFile(data)
        if not dex.is_dex():
            continue
        for (name, sup, fields, methods, code_offs) in dex.classes():
            # ---- 6/7: named classes ----
            if name == "Lcom/xiaomi/gl/texture/Jpeg;":
                for m in methods:
                    cls, meth, params, ret, static, kind = m
                    if meth.startswith("bitmapToJpeg"):
                        jpeg_statics.append((label, meth, list(params), ret, static))
            if name == "Lcom/xiaomi/camera/bean/CloudWmAttribute;":
                for m in methods:
                    cls, meth, params, ret, static, kind = m
                    if meth == "<init>":
                        cloudwm_ctors.append((label, meth, list(params), ret))

            # ---- 1: data interface shape ----
            sigs = set()
            has_b_ctor = False
            b_field = False
            for m in methods:
                cls, meth, params, ret, static, kind = m
                if not params:
                    sigs.add(ret)
                if meth == "<init>" and params == ["[B"]:
                    has_b_ctor = True
            if any(f == "[B" for (fn, f, fst) in fields):
                b_field = True
            if {"Z", "Ljava/io/InputStream;", "[B", "I", "V"} <= sigs and not has_b_ctor and not b_field:
                data_iface.append((label, name))
            # ---- 2: byte[] impl ----
            if has_b_ctor and b_field and "Ljava/io/InputStream;" in sigs:
                byte_impls.append((label, name))

            # ---- 3/4/5 need resolved shapes ----
            mset = set()
            remover_param = False
            for m in methods:
                cls, meth, params, ret, static, kind = m
                mset.add((tuple(params), ret))
                for p in params:
                    if "watermark/WatermarkRemover$" in p:
                        remover_param = True
            # 3: task data class
            if remover_param:
                for (params, ret) in mset:
                    if len(params) == 1 and params[0].startswith("L") and params[0].endswith(";") \
                            and ("L", "V") and ret == "V":
                        pass
                # do the real check after we know the interface type below (two-phase)
                task_classes.append((label, name, sorted(mset)))  # keep for phase 2

            # ---- 4: (I, Bitmap)L<...>; recorded generally, filtered in phase 2 ----
            for (params, ret) in mset:
                if list(params) == ["I", "Landroid/graphics/Bitmap;"] and ret.startswith("L") and ret != "Landroid/graphics/Bitmap;":
                    enc_helpers.append((label, name, ret))

            # ---- 5: recorder snapshot shape ----
            for (params, ret) in mset:
                if len(params) == 9 and params[2] == "Ljava/lang/Object;" \
                        and params[6] == "Ljava/lang/String;" and ret == "Z":
                    recorder_snaps.append((label, name, list(params), ret))

    # phase 2: resolve the real data interface via the task-class cross-check:
    # the task class has BOTH (L<iface>;)V and (L<iface>;I)V and a WatermarkRemover$b param.
    iface_names = {n for (_, n) in data_iface}
    confirmed = {}
    for (label, name, mset) in task_classes:
        for cand in iface_names:
            has_set = ((cand,), 'V') in mset
            has_fill = ((cand, 'I'), 'V') in mset
            if has_set and has_fill:
                confirmed.setdefault(cand, []).append((label, name))
    print("== 3. task classes (set+fill on a data interface) ==")
    for cand, tcs in sorted(confirmed.items()):
        for (label, name) in tcs:
            print("  %s  %s uses %s" % (label, name, cand))
    iface = next(iter(confirmed)) if len(confirmed) == 1 else None
    if iface:
        print("RESOLVED data interface: %s" % iface)
        print("== 4. (I, Bitmap)%s encoders ==" % iface)
        for (label, name, ret) in enc_helpers:
            if ret == iface:
                print("  %s  %s" % (label, name))
    else:
        print("!! interface ambiguous: %r" % iface_names)
        print("== 4. raw (I,Bitmap)->L*; candidates ==")
        for (label, name, ret) in enc_helpers[:20]:
            print("  %s  %s -> %s" % (label, name, ret))

    print("== 5. recorder snapshot candidates ==")
    for (label, name, params, ret) in recorder_snaps:
        print("  %s  %s  %s" % (label, name, params))
    print("== 6. com/xiaomi/gl/texture/Jpeg encoders ==")
    for (label, meth, params, ret, static) in jpeg_statics:
        print("  %s  %s(%s)%s static=%s" % (label, meth, ",".join(params), ret, static))
    print("== 7. CloudWmAttribute ctors ==")
    for (label, meth, params, ret) in cloudwm_ctors:
        print("  %s  %s(%s)%s" % (label, meth, ",".join(params), ret))


if __name__ == "__main__":
    main()
