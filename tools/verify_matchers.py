import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from dexlib import iter_apk_dexes  # noqa: E402

BITMAP = "Landroid/graphics/Bitmap;"
LOCATION = "Landroid/location/Location;"
PRIMITIVE_RUN = ["S", "F", "J", "I"]
MAX_RECORD_FIELDS = 40

def record_plan(instance_types):

    n = len(instance_types)
    for i in range(n - len(PRIMITIVE_RUN) + 1):
        if instance_types[i:i + len(PRIMITIVE_RUN)] != PRIMITIVE_RUN:
            continue
        focal, aperture, exposure, iso = i, i + 1, i + 2, i + 3
        time_idx = None
        for j in range(iso + 1, n):
            if instance_types[j] == "J":
                time_idx = j
                break
        loc_idx = None
        for j in range(iso + 1, n):
            if instance_types[j] == LOCATION:
                loc_idx = j
                break
        if time_idx is None or loc_idx is None:
            continue
        return {
            "focal": focal, "aperture": aperture, "exposure": exposure,
            "iso": iso, "time": time_idx, "location": loc_idx, "anchor": i,
        }
    return None

def main():
    source = sys.argv[1] if len(sys.argv) > 1 else "/tmp/cam670"
    all_classes = []
    for label, dex in iter_apk_dexes(source):
        for cls in dex.classes():
            all_classes.append((label, cls))
    print("classes: %d" % len(all_classes))

    print("\n=== A) capture record (S,F,J,I run + J + Location) ===")
    records = []
    for label, cls in all_classes:
        inst = [t for (n, t, st) in cls.fields if not st]
        if len(inst) > MAX_RECORD_FIELDS or LOCATION not in inst:
            continue
        plan = record_plan(inst)
        if plan is None:
            continue
        names = [n for (n, t, st) in cls.fields if not st]
        records.append((label, cls, plan, names, inst))
        print("  [%s] %s" % (label, cls.name))
        print("      anchor=%d  focal=%s aperture=%s exposure=%s iso=%s time=%s loc=%s"
              % (plan["anchor"], names[plan["focal"]], names[plan["aperture"]],
                 names[plan["exposure"]], names[plan["iso"]],
                 names[plan["time"]], names[plan["location"]]))
    print("  candidates: %d" % len(records))

    print("\n=== B) hook receiver: method(record, Z, I) ===")
    record_names = {c.name for (_, c, _, _, _) in records}
    receivers = []
    for label, cls in all_classes:
        for m in cls.methods:
            if not m.params:
                continue
            if m.params[0] not in record_names:
                continue
            rest = m.params[1:]
            if rest == ["Z", "I"]:
                receivers.append((label, cls, m))
                print("  [%s] %s" % (label, m.signature()))
                print("      declared on %s  static=%s" % (cls.name, m.is_static))
    print("  candidates: %d" % len(receivers))

    print("\n=== C) watermark bitmap factories ===")
    factories = []
    for label, cls in all_classes:
        if not cls.name.startswith("Lcom/xiaomi/cam/watermark/"):
            continue
        for m in cls.methods:
            if m.ret != BITMAP or BITMAP not in m.params:
                continue
            factories.append((label, cls, m, m.params.index(BITMAP)))
            print("  bitmapIdx=%d static=%-5s %s"
                  % (m.params.index(BITMAP), m.is_static, m.signature()))
    print("  candidates: %d" % len(factories))

    print("\n=== verdict ===")
    ok = True
    if len(records) != 1:
        print("  FAIL: expected exactly 1 capture record, found %d" % len(records))
        ok = False
    else:
        print("  OK: capture record = %s" % records[0][1].name)
    if len(receivers) != 1:
        print("  WARN: expected 1 receiver, found %d (hook will try each)" % len(receivers))
    else:
        print("  OK: receiver = %s" % receivers[0][2].signature())
    if not factories:
        print("  FAIL: no watermark factory found")
        ok = False
    else:
        print("  OK: %d factory/factories" % len(factories))
    print("\nRESULT: %s" % ("PASS" if ok else "FAIL"))

if __name__ == "__main__":
    main()
