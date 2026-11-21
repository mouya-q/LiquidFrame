#!/usr/bin/env python3

import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexlib import DexFile  # noqa: E402

BITMAP = "Landroid/graphics/Bitmap;"
CANVAS = "Landroid/graphics/Canvas;"
PAINT = "Landroid/graphics/Paint;"
LOCATION = "Landroid/location/Location;"
INPUT_STREAM = "Ljava/io/InputStream;"
BYTE_ARRAY = "[B"
STRING = "Ljava/lang/String;"
OBJ = "Ljava/lang/Object;"
VOID, INT, BOOLEAN = "V", "I", "Z"
FLOAT = "F"
CAPTURE_RUN = ["S", "F", "J", "I"]
DRAW_RECT_PARAMS = [FLOAT, FLOAT, FLOAT, FLOAT, PAINT]
MAX_RECORD_FIELDS = 40

def record_plan(instance_types):
    """Exact port of DexScanner.recordPlan (including the early `return null`)."""
    if not instance_types or len(instance_types) > MAX_RECORD_FIELDS:
        return None
    for anchor in range(0, len(instance_types) - len(CAPTURE_RUN) + 1):
        if instance_types[anchor:anchor + len(CAPTURE_RUN)] != CAPTURE_RUN:
            continue
        time_idx = loc_idx = -1
        for j in range(anchor + len(CAPTURE_RUN), len(instance_types)):
            if time_idx < 0 and instance_types[j] == "J":
                time_idx = j
            if loc_idx < 0 and instance_types[j] == LOCATION:
                loc_idx = j
                break
        if time_idx >= 0 and loc_idx >= 0:
            return dict(focal=anchor, aperture=anchor + 1, exposure=anchor + 2,
                        iso=anchor + 3, time=time_idx, location=loc_idx)
        return None
    return None

def is_canvas_wrapper(cls):
    has_bitmap_ctor = any(m["ctor"] and m["params"] == [BITMAP] for m in cls["methods"])
    has_draw_rect = any(m["params"] == DRAW_RECT_PARAMS and m["ret"] == VOID
                        for m in cls["methods"])
    return has_bitmap_ctor and has_draw_rect and CANVAS in cls["field_types"]

def has_composite(cls):
    return any(BITMAP in m["params"] and
               (m["ret"] == BITMAP or "WatermarkRemover" in m["ret"])
               for m in cls["methods"])

def is_data_interface_candidate(cls):
    zero = {m["ret"] for m in cls["methods"] if not m["params"]}
    if not {BOOLEAN, INPUT_STREAM, BYTE_ARRAY, INT, VOID}.issubset(zero):
        return False
    if (any(m["ctor"] and m["params"] == [BYTE_ARRAY] for m in cls["methods"]) and
            BYTE_ARRAY in cls["instance_field_types"]):
        return False
    return True

def is_byte_data_impl(cls):
    if not any(m["ctor"] and m["params"] == [BYTE_ARRAY] for m in cls["methods"]):
        return False
    if BYTE_ARRAY not in cls["instance_field_types"]:
        return False
    return any(not m["params"] and m["ret"] == INPUT_STREAM for m in cls["methods"])

def load_classes(apk_path):
    """Return [{name, super, field_types, instance_field_types, methods:[...]}]."""
    out = []
    with zipfile.ZipFile(apk_path) as zf:
        names = sorted((n for n in zf.namelist()
                        if n.startswith("classes") and n.endswith(".dex")),
                       key=lambda n: (len(n), n))
        for name in names:
            data = zf.read(name)
            dex = DexFile(data)
            if not dex.is_dex():
                continue
            for cls in dex.classes():
                methods = []
                for m in cls.methods:
                    methods.append(dict(
                        name=m.name, params=list(m.params), ret=m.ret,
                        static=m.is_static, ctor=(m.name == "<init>"),
                    ))
                field_types = [t for (_, t, _st) in cls.fields]
                inst_types = [t for (_, t, st) in cls.fields if not st]
                out.append(dict(
                    name=cls.name, super=cls.super_name,
                    field_types=field_types, instance_field_types=inst_types,
                    methods=methods, dex=name,
                ))
            del data
    return out

def check(apk, classes):
    result = {}
    by_name = {c["name"]: c for c in classes}

    recs = [(c["name"], record_plan(c["instance_field_types"]), c["dex"])
            for c in classes]
    recs = [r for r in recs if r[1]]
    result["capture_record"] = recs

    rec_types = {r[0] for r in recs}
    receivers = []
    for c in classes:
        for m in c["methods"]:
            if len(m["params"]) != 3:
                continue
            if m["params"][0] not in rec_types:
                continue
            if m["params"][1] != BOOLEAN or m["params"][2] != INT:
                continue
            receivers.append((c["name"], m["name"], c["dex"]))
    result["capture_receiver"] = receivers

    factories = []
    for owner in ("Lcom/xiaomi/cam/watermark/b;", "Lcom/xiaomi/cam/watermark/a;"):
        cls = by_name.get(owner)
        if not cls:
            continue
        qual = []
        for m in cls["methods"]:
            if m["ret"] != BITMAP:
                continue
            if BITMAP not in m["params"]:
                continue
            if "Landroid/content/Context;" not in m["params"]:
                continue
            qual.append(m)
        if qual:
            win = next((m for m in qual if m["name"] == "c"), qual[0])
            factories.append(dict(owner=owner, method=win["name"],
                                  params=len(win["params"]),
                                  static=win["static"],
                                  bitmap_idx=win["params"].index(BITMAP),
                                  qualified=len(qual)))
            break
    result["watermark_factory"] = factories

    guards = []
    jpeg = by_name.get("Lcom/xiaomi/gl/texture/Jpeg;")
    if jpeg:
        for m in jpeg["methods"]:
            if m["name"] not in ("bitmapToJpeg", "bitmapToJpegBuffer"):
                continue
            if not m["static"]:
                continue
            if not m["params"] or m["params"][0] != BITMAP:
                continue
            if m["ret"] not in (BYTE_ARRAY, "Ljava/nio/ByteBuffer;"):
                continue
            guards.append(dict(method=m["name"], ret=m["ret"],
                               params=[p.split("/")[-1] for p in m["params"]]))
    result["final_jpeg_guard"] = guards

    ifaces = [c["name"] for c in classes if is_data_interface_candidate(c)]
    result["data_interface"] = ifaces

    byte_impls = [c["name"] for c in classes if is_byte_data_impl(c)]
    result["byte_data_impl"] = byte_impls

    rm_suffix = "watermark/WatermarkRemover$"
    task = None
    for c in classes:
        has_rm = any(any(rm_suffix in p for p in m["params"]) for m in c["methods"])
        if not has_rm:
            continue
        for iface in ifaces:
            setter = next((m for m in c["methods"]
                           if m["ret"] == VOID and m["params"] == [iface]), None)
            filler = next((m for m in c["methods"]
                           if m["ret"] == VOID and m["params"] == [iface, INT]), None)
            if setter and filler:
                task = dict(owner=c["name"], iface=iface,
                            setter=setter["name"], filler=filler["name"], dex=c["dex"])
                break
        if task:
            break
    result["task_data_class"] = task

    helpers = []
    iface_set = set(ifaces)
    for c in classes:
        for m in c["methods"]:
            if m["ret"] in iface_set and m["params"] == [INT, BITMAP]:
                helpers.append((c["name"], m["name"], m["ret"]))
    result["encode_helpers"] = helpers

    recorders = []
    for c in classes:
        for m in c["methods"]:
            if len(m["params"]) != 9:
                continue
            if m["params"][2] != OBJ:
                continue
            if m["params"][6] != STRING:
                continue
            if m["ret"] != BOOLEAN:
                continue
            recorders.append((c["name"], m["name"]))
    result["live_recorder"] = recorders

    cwa = by_name.get("Lcom/xiaomi/camera/bean/CloudWmAttribute;")
    native = []
    if cwa:
        for m in cwa["methods"]:
            if m["ctor"] and m["params"] == [STRING, BYTE_ARRAY]:
                native.append(dict(ctor=True, fields=[f for f in cwa["field_types"]]))
    result["native_watermark"] = native

    result["canvas_wrapper"] = [c["name"] for c in classes if is_canvas_wrapper(c)]

    result["composite"] = [c["name"] for c in classes if has_composite(c)]
    return result

REQUIRED = [
    ("capture_record", "捕获元数据记录"),
    ("capture_receiver", "捕获消费方法 (record,Z,I)"),
    ("watermark_factory", "水印工厂"),
    ("final_jpeg_guard", "最终 JPEG 守卫"),
    ("native_watermark", "原生水印属性构造器"),
    ("live_recorder", "实况照片快照"),
]

OPTIONAL = [
    ("data_interface", "数据接口"),
    ("byte_data_impl", "字节数据实现"),
    ("task_data_class", "Task 数据落地类"),
    ("encode_helpers", "编码辅助方法"),
]

def main():
    apks = [a for a in sys.argv[1:] if not a.startswith("-")]
    if not apks:
        print(__doc__)
        return 2
    all_ok = True
    for apk in apks:
        print("=" * 78)
        print("APK: %s" % os.path.basename(apk))
        print("=" * 78)
        classes = load_classes(apk)
        print("classes parsed: %d" % len(classes))
        res = check(apk, classes)

        for key, label in REQUIRED + OPTIONAL:
            v = res.get(key)
            if key == "task_data_class":
                ok = v is not None
                shown = ("%s setter=%s filler=%s iface=%s" %
                         (v["owner"], v["setter"], v["filler"], v["iface"])) if ok else "MISSING"
            elif key == "capture_record":
                ok = len(v) == 1
                shown = "; ".join("%s(anchor=%d time=%d loc=%d) [%s]" %
                                  (n, p["focal"], p["time"], p["location"], d)
                                  for (n, p, d) in v) or "MISSING"
            elif key == "capture_receiver":
                ok = len(v) >= 1
                shown = "; ".join("%s.%s [%s]" % (c, m, d) for (c, m, d) in v) or "MISSING"
            elif key == "watermark_factory":
                ok = len(v) >= 1
                shown = ("%s->%s params=%d static=%s bitmapIdx=%d (qualified=%d)" %
                         (v[0]["owner"], v[0]["method"], v[0]["params"],
                          v[0]["static"], v[0]["bitmap_idx"], v[0]["qualified"])
                         ) if ok else "MISSING"
            else:
                ok = len(v) > 0
                shown = str(len(v)) + " candidate(s)"
                if key in ("final_jpeg_guard", "encode_helpers", "live_recorder"):
                    shown += ": " + "; ".join(
                        (x["method"] + "->" + x["ret"]) if isinstance(x, dict)
                        else ("%s.%s" % (x[0], x[1])) for x in v[:3])
                elif key == "native_watermark":
                    shown += ": fields=" + ",".join(
                        f.split("/")[-1] for f in v[0]["fields"] if "m" in f)[:120]
            required = (key, label) in REQUIRED
            if not ok and not required:
                print("  [n/a ] %-32s %s (absent on this architecture; degrades by design)"
                      % (label, shown))
                continue
            print("  [%s] %-32s %s" % ("PASS" if ok else "FAIL", label, shown))
            all_ok = all_ok and ok

        print("  -- informational --")
        print("    canvas wrapper : %s" % (", ".join(res["canvas_wrapper"][:3]) or "none"))
        print("    composite      : %s (%d total)" %
              (", ".join(res["composite"][:3]) or "none", len(res["composite"])))
        print()

    print("=" * 78)
    print("RESULT: %s" % ("PASS — every runtime layer resolves" if all_ok
                          else "FAIL — a required layer is missing"))
    print("=" * 78)
    return 0 if all_ok else 1

if __name__ == "__main__":
    sys.exit(main())
