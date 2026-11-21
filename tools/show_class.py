"""Print the declared members of one or more classes, using the corrected dexlib.

Run:  python3 tools/show_class.py 'LKi/a;' 'LW8/d;'

Field/method indices in `class_data_item` are deltas and must be accumulated;
dexlib does that, so the members printed here are the real ones.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from dexlib import iter_apk_dexes  # noqa: E402


def main():
    want = set(sys.argv[2:])
    source = sys.argv[1] if len(sys.argv) > 1 else "/tmp/cam670"
    for label, dex in iter_apk_dexes(source):
        for cls in dex.classes():
            if want and cls.name not in want:
                continue
            print("=" * 78)
            print("%s extends %s   [%s]" % (cls.name, cls.super_name, label))
            statics = [(n, t) for (n, t, st) in cls.fields if st]
            insts = [(n, t) for (n, t, st) in cls.fields if not st]
            if statics:
                print("  static fields:")
                for n, t in statics:
                    print("    %-6s : %s" % (n, t))
            if insts:
                print("  instance fields (%d):" % len(insts))
                for n, t in insts:
                    print("    %-6s : %s" % (n, t))
            if cls.methods:
                print("  methods (%d):" % len(cls.methods))
                for m in cls.methods:
                    print("    %s%s" % (m.signature(), "  [static]" if m.is_static else ""))


if __name__ == "__main__":
    main()