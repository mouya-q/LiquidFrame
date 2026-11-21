#!/usr/bin/env python3
"""Find every call site of a target method across all dex files in a directory.

Usage:
  python3 tools/find_calls.py <dex_dir> 'LW8/d;->f' 'LW8/d;->e'

Prints caller class#method(dex) plus a few instructions of context around each
invoke that references a method whose class+name matches a target.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import dex2text  # noqa: E402


def main():
    src = sys.argv[1]
    pats = sys.argv[2:]
    if not pats:
        print("usage: find_calls.py <src> 'LCls;->name' ...")
        sys.exit(1)
    targets = []
    for p in pats:
        m = re.fullmatch(r"(L[^;]+;)->([^\(]+)", p)
        if not m:
            print("bad target: %s" % p)
            sys.exit(1)
        targets.append((m.group(1), m.group(2)))
    tset = set(targets)

    for label, data in dex2text.iter_dexes(src):
        dex = dex2text.DexFile(data)
        if not dex.is_dex():
            continue
        for (name, sup, fields, methods, code_offs) in dex.classes():
            for i, m in enumerate(methods):
                cls_name, meth_name, params, ret, is_static, kind = m
                co = code_offs[i] if i < len(code_offs) else 0
                if not co:
                    continue
                res = dex.disasm_code(co)
                if not (res and isinstance(res, tuple) and len(res) == 4):
                    continue
                instrs, regs, ins_sz, outs_sz = res
                lines = []
                for (off, mn, operands) in instrs:
                    lines.append((off, mn, operands))
                for idx, (off, mn, operands) in enumerate(lines):
                    # The disassembler prints resolved refs as
                    # "LCls;.name(params)ret { regs }" inside operands.
                    opnd = operands or ""
                    for (tc, tn) in tset:
                        if "%s.%s(" % (tc, tn) in opnd:
                            context = lines[max(0, idx - 6):idx + 5]
                            print("--- [%s] caller: %s#%s" % (label, name, meth_name))
                            for (o2, m2, p2) in context:
                                mark = ">>" if o2 == off else "  "
                                print("  %s %04x: %-28s %s" % (mark, o2, m2, p2))
                            print()
                            break
    print("done.")


if __name__ == "__main__":
    main()
