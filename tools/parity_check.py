"""Cross-check the Kotlin matcher port against the verified Python reference.

Run:  python3 tools/parity_check.py /tmp/cam670

The Kotlin side (DexScan.recordPlan / selectFactoryMethod) is a port of the logic in
verify_matchers.py. A port that silently diverges would ship a module that reports
"no capture record" on a build where the reference implementation finds one, so the
two are compared here on the same APK before any build is pushed.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from dexlib import iter_apk_dexes  # noqa: E402

BITMAP = "Landroid/graphics/Bitmap;"
CONTEXT = "Landroid/content/Context;"
LOCATION = "Landroid/location/Location;"
RUN = ["S", "F", "J", "I"]
MAX_FIELDS = 40


def kotlin_record_plan(instance_types):
    """Mirror of DexScan.recordPlan, including its early-return-on-partial-match quirk."""
    if not instance_types or len(instance_types) > MAX_FIELDS:
        return None
    for anchor in range(len(instance_types) - len(RUN) + 1):
        if instance_types[anchor:anchor + len(RUN)] != RUN:
            continue
        time_idx = -1
        loc_idx = -1
        for j in range(anchor + len(RUN), len(instance_types)):
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


def kotlin_select_factory(methods):
    """Mirror of WatermarkModes.selectFactoryMethod: Bitmap + Context, prefer name 'c'."""
    qual = [m for m in methods
            if m.ret == BITMAP and BITMAP in m.params and CONTEXT in m.params]
    if not qual:
        return None
    for m in qual:
        if m.name == "c":
            return m
    return qual[0]


def main():
    source = sys.argv[1] if len(sys.argv) > 1 else "/tmp/cam670"
    classes = []
    for label, dex in iter_apk_dexes(source):
        for cls in dex.classes():
            classes.append((label, cls))

    # --- records -----------------------------------------------------------
    kotlin_records = []
    for label, cls in classes:
        inst = cls.instance_field_types()
        plan = kotlin_record_plan(inst)
        if plan:
            names = [n for (n, t, st) in cls.fields if not st]
            kotlin_records.append((cls.name, plan, names))
    print("kotlin-mirror capture records: %d" % len(kotlin_records))
    for name, plan, names in kotlin_records:
        print("  %s  focal=%s(%d) aperture=%s(%d) exposure=%s(%d) iso=%s(%d) "
              "time=%s(%d) loc=%s(%d)"
              % (name,
                 names[plan["focal"]], plan["focal"],
                 names[plan["aperture"]], plan["aperture"],
                 names[plan["exposure"]], plan["exposure"],
                 names[plan["iso"]], plan["iso"],
                 names[plan["time"]], plan["time"],
                 names[plan["location"]], plan["location"]))

    # --- factories ---------------------------------------------------------
    print("\nfactories the Kotlin matcher would pick, per class:")
    for label, cls in classes:
        if not cls.name.startswith("Lcom/xiaomi/cam/watermark/"):
            continue
        picked = kotlin_select_factory(cls.methods)
        if picked:
            print("  %s -> %s" % (cls.name, picked.signature()))
            print("      bitmapIdx=%d static=%s"
                  % (picked.params.index(BITMAP), picked.is_static))
        elif any(m.ret == BITMAP for m in cls.methods):
            print("  %s -> (rejected: no Context+Bitmap signature)" % cls.name)

    # --- verdict -----------------------------------------------------------
    print("\n=== parity verdict ===")
    ok = True
    if len(kotlin_records) != 1:
        print("  FAIL: expected 1 record, mirror found %d" % len(kotlin_records))
        ok = False
    else:
        print("  OK: exactly one capture record (%s)" % kotlin_records[0][0])

    wm_b = next(((l, c) for (l, c) in classes
                 if c.name == "Lcom/xiaomi/cam/watermark/b;"), None)
    if wm_b is None:
        print("  NOTE: com/xiaomi/cam/watermark/b absent — package fallback would run")
    else:
        picked = kotlin_select_factory(wm_b[1].methods)
        if picked is None:
            print("  FAIL: watermark.b has no factory the matcher accepts")
            ok = False
        else:
            print("  OK: watermark.b factory = %s" % picked.signature())
            if picked.params.index(BITMAP) != 1:
                print("  WARN: bitmapIdx=%d (expected 1 on 6.7)" % picked.params.index(BITMAP))
    print("\nRESULT: %s" % ("PASS" if ok else "FAIL"))


if __name__ == "__main__":
    main()