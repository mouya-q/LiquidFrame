import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from dexlib import iter_apk_dexes, MethodRef  # noqa: E402

LOCATION = "Landroid/location/Location;"
BITMAP = "Landroid/graphics/Bitmap;"
PRIMITIVES = {"F", "J", "I", "S", "D", "B", "C", "Z"}

def collect(source):
    classes = []
    for label, dex in iter_apk_dexes(source):
        for cls in dex.classes():
            classes.append((label, cls))
    return classes

def report_metadata_candidates(classes):
    print("\n=== 1. capture-metadata record candidates ===")
    print("A record must declare a Location field and >=3 primitive instance fields.")
    hits = []
    for label, cls in classes:
        inst = [t for (_, t, st) in cls.fields if not st]
        if LOCATION not in inst:
            continue
        prims = [t for t in inst if t in PRIMITIVES]
        if len(prims) < 3:
            continue
        hits.append((label, cls, inst))
    for label, cls, inst in hits:
        print("  [%s] %s" % (label, cls.name))
        print("      instance fields: %s" % ", ".join(inst))
    print("  total: %d" % len(hits))
    return [h[1].name for h in hits]

def report_watermark_factories(classes):
    print("\n=== 2. watermark bitmap factories (package com/xiaomi/cam/watermark/) ===")
    hits = []
    for label, cls in classes:
        if not cls.name.startswith("Lcom/xiaomi/cam/watermark/"):
            continue
        for m in cls.methods:
            if m.ret != BITMAP:
                continue
            if BITMAP not in m.params:
                continue
            hits.append((label, cls, m))
    for label, cls, m in hits:
        idx = m.params.index(BITMAP)
        print("  [%s] %s" % (label, m.signature()))
        print("      static=%s  bitmapParamIndex=%d  paramCount=%d"
              % (m.is_static, idx, len(m.params)))
    print("  total: %d" % len(hits))
    return hits

def report_watermark_package(classes):
    print("\n=== 3. every class in com/xiaomi/cam/watermark/ ===")
    for label, cls in classes:
        if not cls.name.startswith("Lcom/xiaomi/cam/watermark/"):
            continue
        print("  [%s] %s extends %s  (%d methods, %d fields)"
              % (label, cls.name, cls.super_name, len(cls.methods), len(cls.fields)))
        for m in cls.methods:
            print("        %s%s" % (m.signature(), "  [static]" if m.is_static else ""))

def report_metadata_consumers(classes, record_names):
    """Find methods that take one of the metadata record types as a parameter."""
    print("\n=== 4. methods consuming a metadata record ===")
    found = 0
    for label, cls in classes:
        for m in cls.methods:
            for rec in record_names:
                if rec in m.params:
                    print("  [%s] %s" % (label, m.signature()))
                    print("      declared on %s, static=%s" % (cls.name, m.is_static))
                    found += 1
                    break
    if not found:
        print("  none — the record is only held as an object graph, not passed directly.")

def report_accessor_shapes(classes, record_names):
    """A record type with private fields usually has a getter/setter pair nearby."""
    print("\n=== 5. record accessors (methods taking/returning the record) ===")
    for label, cls in classes:
        for m in cls.methods:
            for rec in record_names:
                if m.ret == rec or rec in m.params:
                    print("  [%s] %s  on %s" % (label, m.signature(), cls.name))
                    break

def main():
    source = sys.argv[1] if len(sys.argv) > 1 else "/tmp/cam670"
    print("source: %s" % source)
    classes = collect(source)
    print("classes parsed: %d" % len(classes))
    records = report_metadata_candidates(classes)
    report_watermark_factories(classes)
    report_watermark_package(classes)
    if records:
        report_metadata_consumers(classes, records)
    else:
        print("\n(no metadata record found: capture EXIF line will be unavailable)")

if __name__ == "__main__":
    main()
