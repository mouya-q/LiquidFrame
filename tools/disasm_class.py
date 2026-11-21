#!/usr/bin/env python3
"""Disassemble selected classes (full bytecode) from a directory of .dex files.

Reuses the disassembler core of dex2text.py but only emits the requested
classes, so a 200 MB camera APK can be explored without producing GB of text.

Usage:  python3 tools/disasm_class.py <dex_dir> 'LW8/d;' 'LKi/f;' > out.txt
        python3 tools/disasm_class.py <dex_dir> --re 'LKi/.*;'
"""
import os
import re as _re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import dex2text  # noqa: E402


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = set(a for a in sys.argv[1:] if a.startswith("--"))
    src = args[0]
    pats = args[1:]
    if not pats:
        print("usage: disasm_class.py <src> 'LPkg/Cls;'...")
        sys.exit(1)
    if "--re" in flags:
        regexes = [_re.compile(p) for p in pats]
        match = lambda n: any(r.fullmatch(n) for r in regexes)
    else:
        want = set(pats)
        match = lambda n: n in want

    for label, data in dex2text.iter_dexes(src):
        dex = dex2text.DexFile(data)
        if not dex.is_dex():
            continue
        classes = dex.classes()
        for (name, sup, fields, methods, code_offs) in classes:
            if not match(name):
                continue
            print("=" * 78)
            print("class %s extends %s   [%s]" % (name, sup, label))
            print("=" * 78)
            statics = [(n, t) for (n, t, s) in fields if s]
            insts = [(n, t) for (n, t, s) in fields if not s]
            if statics:
                print("  # static fields")
                for n, t in statics:
                    print("  .field static %s %s" % (t, n))
            if insts:
                print("  # instance fields")
                for n, t in insts:
                    print("  .field %s %s" % (t, n))
            print("  # methods (%d)" % len(methods))
            for i, m in enumerate(methods):
                cls_name, meth_name, params, ret, is_static, kind = m
                co = code_offs[i] if i < len(code_offs) else 0
                mods = "static " if is_static else ""
                print()
                print("  .method %s%s %s(%s)%s" % (
                    mods, kind, meth_name, ", ".join(params), ret))
                if co:
                    res = dex.disasm_code(co)
                    if res and isinstance(res, tuple) and len(res) == 4:
                        instrs, regs, ins_sz, outs_sz = res
                        print("    # registers=%d ins=%d outs=%d" % (regs, ins_sz, outs_sz))
                        for (off, mn, operands) in instrs:
                            print("    %04x: %-30s %s" % (off, mn, operands))
                    else:
                        print("    # (no code)")
                else:
                    print("    # (abstract/native)")
                print("  .end method")
            print()


if __name__ == "__main__":
    main()
