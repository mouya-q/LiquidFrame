#!/usr/bin/env python3
"""
Full DEX disassembler — converts every class, field, method, and instruction
into human-readable text. No Java required.

Output layout:
    analysis/<dex_name>_classes.txt   — every class with fields, methods, and
                                         disassembled bytecode

Usage:
    python3 tools/dex2text.py <input.dex|input.apk|dir_of_dex> <output_dir>
"""

import os, sys, struct, zipfile

# ── helpers ──────────────────────────────────────────────────────────────
def uleb128(d, p):
    r = 0; s = 0
    while p < len(d):
        b = d[p]; p += 1
        r |= (b & 0x7F) << s
        if (b & 0x80) == 0: break
        s += 7
        if s > 28: break
    return r, p

def sleb128(d, p):
    r = 0; s = 0
    while p < len(d):
        b = d[p]; p += 1
        r |= (b & 0x7F) << s
        s += 7
        if (b & 0x80) == 0:
            if s < 32 and (b & 0x40):
                r |= -(1 << s)
            break
        if s > 35: break
    return r, p

def u1(d, p): return d[p], p+1
def u2(d, p): return d[p] | (d[p+1]<<8), p+2
def u4(d, p): return d[p] | (d[p+1]<<8) | (d[p+2]<<16) | (d[p+3]<<24), p+4

def _read_string(d, off):
    _, p = uleb128(d, off)
    out = []
    while p < len(d):
        b = d[p]; p += 1
        if b == 0: break
        if b < 0x80:
            out.append(chr(b))
        elif (b & 0xE0) == 0xC0:
            if p >= len(d): break
            b2 = d[p]; p += 1
            out.append(chr(((b & 0x1F) << 6) | (b2 & 0x3F)))
        elif (b & 0xF0) == 0xE0:
            if p+1 >= len(d): break
            b2 = d[p]; b3 = d[p+1]; p += 2
            out.append(chr(((b & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F)))
        elif (b & 0xF8) == 0xF0:
            # 4-byte UTF-8 → supplementary character (may produce surrogates)
            if p+2 >= len(d): break
            b2 = d[p]; b3 = d[p+1]; b4 = d[p+2]; p += 3
            cp = ((b & 0x07) << 18) | ((b2 & 0x3F) << 12) | ((b3 & 0x3F) << 6) | (b4 & 0x3F)
            out.append(chr(cp))
        else:
            out.append('?')
    return "".join(out)

# ── Dalvik opcode table ──────────────────────────────────────────────────
# (mnemonic, format_code,  instr_width_in_16bit_units)
# format codes: 10x,12x,11n,11x,10t,20t,22x,21t,21s,21h,20bc,22b,22t,22s,22c,
#               22cs,30t,23x,23c,32x,31i,31t,31c,35c,3rc,35ms,3rms,35c2,3rc2,
#               45cc,4rcc,51l
OPCODES = {
    0x00: ("nop",            "10x", 1),
    0x01: ("move",           "12x", 1),
    0x02: ("move/from16",    "22x", 2),
    0x03: ("move/16",        "32x", 3),
    0x04: ("move-wide",      "12x", 1),
    0x05: ("move-wide/from16","22x",2),
    0x06: ("move-wide/16",   "32x", 3),
    0x07: ("move-object",    "12x", 1),
    0x08: ("move-object/from16","22x",2),
    0x09: ("move-object/16", "32x", 3),
    0x0a: ("move-result",    "11x", 1),
    0x0b: ("move-result-wide","11x",1),
    0x0c: ("move-result-object","11x",1),
    0x0d: ("move-exception", "11x", 1),
    0x0e: ("return-void",    "10x", 1),
    0x0f: ("return",         "11x", 1),
    0x10: ("return-wide",    "11x", 1),
    0x11: ("return-object",  "11x", 1),
    0x12: ("const/4",        "11n", 1),
    0x13: ("const/16",       "21s", 2),
    0x14: ("const",          "31i", 3),
    0x15: ("const/high16",   "21h", 2),
    0x16: ("const-wide/16",  "21s", 2),
    0x17: ("const-wide/32",  "31i", 3),
    0x18: ("const-wide",     "51l", 5),
    0x19: ("const-wide/high16","21h",2),
    0x1a: ("const-string",   "21c", 2),
    0x1b: ("const-string/jumbo","31c",3),
    0x1c: ("const-class",    "21c", 2),
    0x1d: ("monitor-enter",  "11x", 1),
    0x1e: ("monitor-exit",   "11x", 1),
    0x1f: ("check-cast",     "21c", 2),
    0x20: ("instance-of",    "22c", 2),
    0x21: ("array-length",   "12x", 1),
    0x22: ("new-instance",   "21c", 2),
    0x23: ("new-array",      "22c", 2),
    0x24: ("filled-new-array","35c",3),
    0x25: ("filled-new-array/range","3rc",3),
    0x26: ("fill-array-data","31t", 3),
    0x27: ("throw",          "11x", 1),
    0x28: ("goto",           "10t", 1),
    0x29: ("goto/16",        "20t", 2),
    0x2a: ("goto/32",        "30t", 3),
    0x2b: ("packed-switch",  "31t", 3),
    0x2c: ("sparse-switch",  "31t", 3),
    0x2d: ("cmpl-float",     "23x", 2),
    0x2e: ("cmpg-float",     "23x", 2),
    0x2f: ("cmpl-double",    "23x", 2),
    0x30: ("cmpg-double",    "23x", 2),
    0x31: ("cmp-long",       "23x", 2),
    0x32: ("if-eq",          "22t", 2),
    0x33: ("if-ne",          "22t", 2),
    0x34: ("if-lt",          "22t", 2),
    0x35: ("if-ge",          "22t", 2),
    0x36: ("if-gt",          "22t", 2),
    0x37: ("if-le",          "22t", 2),
    0x38: ("if-eqz",         "21t", 2),
    0x39: ("if-nez",         "21t", 2),
    0x3a: ("if-ltz",         "21t", 2),
    0x3b: ("if-gez",         "21t", 2),
    0x3c: ("if-gtz",         "21t", 2),
    0x3d: ("if-lez",         "21t", 2),
    0x3e: ("aget-boolean",   "23x", 2),
    0x44: ("aget",           "23x", 2),
    0x45: ("aget-wide",      "23x", 2),
    0x46: ("aget-object",    "23x", 2),
    0x47: ("aget-boolean",   "23x", 2),
    0x48: ("aget-byte",      "23x", 2),
    0x49: ("aget-char",      "23x", 2),
    0x4a: ("aget-short",     "23x", 2),
    0x4b: ("aput",           "23x", 2),
    0x4c: ("aput-wide",      "23x", 2),
    0x4d: ("aput-object",    "23x", 2),
    0x4e: ("aput-boolean",   "23x", 2),
    0x4f: ("aput-byte",      "23x", 2),
    0x50: ("aput-char",      "23x", 2),
    0x51: ("aput-short",     "23x", 2),
    0x52: ("iget",           "22c", 2),
    0x53: ("iget-wide",      "22c", 2),
    0x54: ("iget-object",    "22c", 2),
    0x55: ("iget-boolean",   "22c", 2),
    0x56: ("iget-byte",      "22c", 2),
    0x57: ("iget-char",      "22c", 2),
    0x58: ("iget-short",     "22c", 2),
    0x59: ("iput",           "22c", 2),
    0x5a: ("iput-wide",      "22c", 2),
    0x5b: ("iput-object",    "22c", 2),
    0x5c: ("iput-boolean",   "22c", 2),
    0x5d: ("iput-byte",      "22c", 2),
    0x5e: ("iput-char",      "22c", 2),
    0x5f: ("iput-short",     "22c", 2),
    0x60: ("sget",           "21c", 2),
    0x61: ("sget-wide",      "21c", 2),
    0x62: ("sget-object",    "21c", 2),
    0x63: ("sget-boolean",   "21c", 2),
    0x64: ("sget-byte",      "21c", 2),
    0x65: ("sget-char",      "21c", 2),
    0x66: ("sget-short",     "21c", 2),
    0x67: ("sput",           "21c", 2),
    0x68: ("sput-wide",      "21c", 2),
    0x69: ("sput-object",    "21c", 2),
    0x6a: ("sput-boolean",   "21c", 2),
    0x6b: ("sput-byte",      "21c", 2),
    0x6c: ("sput-char",      "21c", 2),
    0x6d: ("sput-short",     "21c", 2),
    0x6e: ("invoke-virtual",  "35c", 3),
    0x6f: ("invoke-super",   "35c", 3),
    0x70: ("invoke-direct",  "35c", 3),
    0x71: ("invoke-static",  "35c", 3),
    0x72: ("invoke-interface","35c", 3),
    0x74: ("invoke-virtual/range","3rc",3),
    0x75: ("invoke-super/range","3rc",3),
    0x76: ("invoke-direct/range","3rc",3),
    0x77: ("invoke-static/range","3rc",3),
    0x78: ("invoke-interface/range","3rc",3),
    0x7a: ("invoke-virtual",  "35c", 3), # polymorphic
    0x7b: ("invoke-virtual/range","3rc",3), # polymorphic/range
    0x44: ("aget",           "23x", 2),
    0x7c: ("neg-int",        "12x", 1),
    0x7d: ("not-int",        "12x", 1),
    0x7e: ("neg-long",       "12x", 1),
    0x7f: ("not-long",       "12x", 1),
    0x80: ("neg-float",      "12x", 1),
    0x81: ("neg-double",     "12x", 1),
    0x82: ("int-to-long",    "12x", 1),
    0x83: ("int-to-float",   "12x", 1),
    0x84: ("int-to-double",  "12x", 1),
    0x85: ("long-to-int",    "12x", 1),
    0x86: ("long-to-float",  "12x", 1),
    0x87: ("long-to-double", "12x", 1),
    0x88: ("float-to-int",   "12x", 1),
    0x89: ("float-to-long",  "12x", 1),
    0x8a: ("float-to-double","12x", 1),
    0x8b: ("double-to-int",  "12x", 1),
    0x8c: ("double-to-long", "12x", 1),
    0x8d: ("double-to-float","12x", 1),
    0x8e: ("int-to-byte",    "12x", 1),
    0x8f: ("int-to-char",    "12x", 1),
    0x90: ("int-to-short",   "12x", 1),
    0x91: ("add-int",        "23x", 2),
    0x92: ("sub-int",        "23x", 2),
    0x93: ("mul-int",        "23x", 2),
    0x94: ("div-int",        "23x", 2),
    0x95: ("rem-int",        "23x", 2),
    0x96: ("and-int",        "23x", 2),
    0x97: ("or-int",         "23x", 2),
    0x98: ("xor-int",        "23x", 2),
    0x99: ("shl-int",        "23x", 2),
    0x9a: ("shr-int",        "23x", 2),
    0x9b: ("ushr-int",       "23x", 2),
    0x9c: ("add-long",       "23x", 2),
    0x9d: ("sub-long",       "23x", 2),
    0x9e: ("mul-long",       "23x", 2),
    0x9f: ("div-long",       "23x", 2),
    0xa0: ("rem-long",       "23x", 2),
    0xa1: ("and-long",       "23x", 2),
    0xa2: ("or-long",        "23x", 2),
    0xa3: ("xor-long",       "23x", 2),
    0xa4: ("shl-long",       "23x", 2),
    0xa5: ("shr-long",       "23x", 2),
    0xa6: ("ushr-long",      "23x", 2),
    0xa7: ("add-float",      "23x", 2),
    0xa8: ("sub-float",      "23x", 2),
    0xa9: ("mul-float",      "23x", 2),
    0xaa: ("div-float",      "23x", 2),
    0xab: ("rem-float",      "23x", 2),
    0xac: ("add-double",     "23x", 2),
    0xad: ("sub-double",     "23x", 2),
    0xae: ("mul-double",     "23x", 2),
    0xaf: ("div-double",     "23x", 2),
    0xb0: ("rem-double",     "23x", 2),
    0xb1: ("add-int/2addr",  "12x", 1),
    0xb2: ("sub-int/2addr",  "12x", 1),
    0xb3: ("mul-int/2addr",  "12x", 1),
    0xb4: ("div-int/2addr",  "12x", 1),
    0xb5: ("rem-int/2addr",  "12x", 1),
    0xb6: ("and-int/2addr",  "12x", 1),
    0xb7: ("or-int/2addr",   "12x", 1),
    0xb8: ("xor-int/2addr",  "12x", 1),
    0xb9: ("shl-int/2addr",  "12x", 1),
    0xba: ("shr-int/2addr",  "12x", 1),
    0xbb: ("ushr-int/2addr", "12x", 1),
    0xbc: ("add-long/2addr", "12x", 1),
    0xbd: ("sub-long/2addr", "12x", 1),
    0xbe: ("mul-long/2addr", "12x", 1),
    0xbf: ("div-long/2addr", "12x", 1),
    0xc0: ("rem-long/2addr", "12x", 1),
    0xc1: ("and-long/2addr", "12x", 1),
    0xc2: ("or-long/2addr",  "12x", 1),
    0xc3: ("xor-long/2addr", "12x", 1),
    0xc4: ("shl-long/2addr", "12x", 1),
    0xc5: ("shr-long/2addr", "12x", 1),
    0xc6: ("ushr-long/2addr","12x", 1),
    0xc7: ("add-float/2addr","12x", 1),
    0xc8: ("sub-float/2addr","12x", 1),
    0xc9: ("mul-float/2addr","12x", 1),
    0xca: ("div-float/2addr","12x", 1),
    0xcb: ("rem-float/2addr","12x", 1),
    0xcc: ("add-double/2addr","12x",1),
    0xcd: ("sub-double/2addr","12x",1),
    0xce: ("mul-double/2addr","12x",1),
    0xcf: ("div-double/2addr","12x",1),
    0xd0: ("rem-double/2addr","12x",1),
    0xd1: ("add-int/lit8",   "22b", 2),
    0xd2: ("rsub-int/lit8",  "22b", 2),
    0xd3: ("mul-int/lit8",   "22b", 2),
    0xd4: ("div-int/lit8",   "22b", 2),
    0xd5: ("rem-int/lit8",   "22b", 2),
    0xd6: ("and-int/lit8",   "22b", 2),
    0xd7: ("or-int/lit8",    "22b", 2),
    0xd8: ("xor-int/lit8",   "22b", 2),
    0xd9: ("shl-int/lit8",   "22b", 2),
    0xda: ("shr-int/lit8",   "22b", 2),
    0xdb: ("ushr-int/lit8",  "22b", 2),
    0xdc: ("add-int/lit16",  "22s", 2),
    0xdd: ("rsub-int/lit16", "22s", 2),
    0xde: ("mul-int/lit16",  "22s", 2),
    0xdf: ("div-int/lit16",  "22s", 2),
    0xe0: ("rem-int/lit16",  "22s", 2),
    0xe1: ("and-int/lit16",  "22s", 2),
    0xe2: ("or-int/lit16",   "22s", 2),
    0xe3: ("xor-int/lit16",  "22s", 2),
    0xfa: ("invoke-polymorphic","45cc",4),
    0xfb: ("invoke-polymorphic/range","4rcc",4),
    0xfc: ("packed-switch-payload","",0), # payload
    0xfd: ("sparse-switch-payload","",0), # payload
    0xfe: ("fill-array-data-payload","",0), # payload
}

# ── DexFile ──────────────────────────────────────────────────────────────
class DexFile:
    def __init__(self, data):
        self.d = data
        self.string_count = u4(data, 0x38)[0]
        self.string_ids_off = u4(data, 0x3C)[0]
        self.type_count = u4(data, 0x40)[0]
        self.type_ids_off = u4(data, 0x44)[0]
        self.proto_count = u4(data, 0x48)[0]
        self.proto_ids_off = u4(data, 0x4C)[0]
        self.field_count = u4(data, 0x50)[0]
        self.field_ids_off = u4(data, 0x54)[0]
        self.method_count = u4(data, 0x58)[0]
        self.method_ids_off = u4(data, 0x5C)[0]
        self.class_count = u4(data, 0x60)[0]
        self.class_defs_off = u4(data, 0x64)[0]
        self._s = {}
        self._t = {}

    def is_dex(self):
        return len(self.d) >= 0x70 and self.d[0:4] == b"dex\n"

    def string(self, idx):
        if idx in self._s: return self._s[idx]
        if idx < 0 or idx >= self.string_count: return None
        off = u4(self.d, self.string_ids_off + idx * 4)[0]
        v = _read_string(self.d, off) if 0 < off < len(self.d) else None
        self._s[idx] = v
        return v

    def type_name(self, idx):
        if idx in self._t: return self._t[idx]
        if idx >= self.type_count: return "?"
        p = self.type_ids_off + idx * 4
        v = self.string(u4(self.d, p)[0]) or "?"
        self._t[idx] = v
        return v

    def proto(self, idx):
        pb = self.proto_ids_off + idx * 12
        if pb + 12 > len(self.d): return "?", []
        ret = self.type_name(u4(self.d, pb + 4)[0])
        po = u4(self.d, pb + 8)[0]
        params = []
        if po and po + 4 <= len(self.d):
            sz = u4(self.d, po)[0]
            for i in range(min(sz, 255)):
                pp = po + 4 + i * 2
                if pp + 2 > len(self.d): break
                params.append(self.type_name(u2(self.d, pp)[0]))
        return ret, params

    def method_ref(self, idx):
        b = self.method_ids_off + idx * 8
        if b + 8 > len(self.d): return None
        cls = self.type_name(u2(self.d, b)[0])
        ret, params = self.proto(u2(self.d, b + 2)[0])
        name = self.string(u4(self.d, b + 4)[0]) or "?"
        return (cls, name, params, ret)

    def field_ref(self, idx):
        b = self.field_ids_off + idx * 8
        if b + 8 > len(self.d): return None, None
        t = self.type_name(u2(self.d, b + 2)[0])
        n = self.string(u4(self.d, b + 4)[0]) or "?"
        return n, t

    def classes(self):
        out = []
        for ci in range(self.class_count):
            base = self.class_defs_off + ci * 32
            if base + 32 > len(self.d): break
            cidx = u4(self.d, base)[0]
            sidx = u4(self.d, base + 8)[0]
            cdo = u4(self.d, base + 24)[0]
            if cidx >= self.type_count: continue
            name = self.type_name(cidx)
            sup = self.type_name(sidx) if sidx != 0xFFFFFFFF and sidx < self.type_count else None
            if cdo != 0 and cdo < len(self.d):
                fields, methods, code_offs = self._read_class_data(cdo)
            else:
                fields, methods, code_offs = [], [], []
            out.append((name, sup, fields, methods, code_offs))
        return out

    def _read_class_data(self, off):
        p = off
        sf, p = uleb128(self.d, p)
        ifv, p = uleb128(self.d, p)
        dm, p = uleb128(self.d, p)
        vm, p = uleb128(self.d, p)
        fields = []
        # static fields
        idx = 0
        for _ in range(sf):
            diff, p = uleb128(self.d, p); idx += diff
            _, p = uleb128(self.d, p)  # access
            n, t = self.field_ref(idx)
            if n: fields.append((n, t, True))
        # instance fields
        idx = 0
        for _ in range(ifv):
            diff, p = uleb128(self.d, p); idx += diff
            _, p = uleb128(self.d, p)
            n, t = self.field_ref(idx)
            if n: fields.append((n, t, False))
        methods = []
        code_offs = []
        # direct methods
        idx = 0
        for _ in range(dm):
            diff, p = uleb128(self.d, p); idx += diff
            access, p = uleb128(self.d, p)
            co, p = uleb128(self.d, p)
            mr = self.method_ref(idx)
            if mr:
                methods.append((*mr, bool(access & 0x0008), "direct"))
                code_offs.append(co)
        # virtual methods
        idx = 0
        for _ in range(vm):
            diff, p = uleb128(self.d, p); idx += diff
            access, p = uleb128(self.d, p)
            co, p = uleb128(self.d, p)
            mr = self.method_ref(idx)
            if mr:
                methods.append((*mr, bool(access & 0x0008), "virtual"))
                code_offs.append(co)
        return fields, methods, code_offs

    # ── code_item disassembly ──────────────────────────────────────────
    def disasm_code(self, code_off):
        """Return list of (offset, mnemonic, operands_str) for a code_item."""
        if code_off == 0 or code_off >= len(self.d):
            return []
        p = code_off
        regs, p = u2(self.d, p)
        ins_size, p = u2(self.d, p)
        outs_size, p = u2(self.d, p)
        tries_size, p = u2(self.d, p)
        debug_off, p = u4(self.d, p)
        insns_size, p = u4(self.d, p)  # in 16-bit units
        insns_start = p
        insns_end = p + insns_size * 2

        out = []
        while p < insns_end:
            opcode = self.d[p]
            info = OPCODES.get(opcode)
            if info is None:
                # Unknown opcode — skip 1 unit
                out.append((p - insns_start, "unknown-0x%02x" % opcode, ""))
                p += 2
                continue
            mnemonic, fmt, width = info
            if width == 0:
                # payload marker — stop normal disasm
                out.append((p - insns_start, mnemonic, "(payload)"))
                p += 2
                continue

            ip = p  # instruction start
            raw = self.d[ip:ip + width*2]
            operands = self._decode_operands(opcode, mnemonic, fmt, raw, ip - insns_start)
            out.append((ip - insns_start, mnemonic, operands))
            p += width * 2

        return out, regs, ins_size, outs_size

    def _decode_operands(self, op, mn, fmt, raw, rel_off):
        """Decode instruction operands to a readable string."""
        d = raw
        if len(d) < 2:
            return ""

        def reg(n):
            return "v%d" % n

        try:
            if fmt == "10x":
                return ""
            elif fmt == "12x":
                a = (d[0] >> 4) & 0xF
                b = d[0] & 0xF
                return "%s, %s" % (reg(a), reg(b))
            elif fmt == "11n":
                a = (d[0] >> 4) & 0xF
                b = d[0] & 0xF
                # sign-extend 4-bit
                if b & 8: b -= 16
                return "%s, #%d" % (reg(a), b)
            elif fmt == "11x":
                a = d[1]
                return reg(a)
            elif fmt == "10t":
                off = d[1]
                if off & 0x80: off -= 256
                return "goto +%d" % off
            elif fmt == "20t":
                off = d[1] | (d[2] << 8)
                if off & 0x8000: off -= 65536
                return "%+d" % off
            elif fmt == "22x":
                a = d[1]
                b = d[2] | (d[3] << 8)
                return "%s, %s" % (reg(a), reg(b))
            elif fmt == "21t":
                a = d[1]
                off = d[2] | (d[3] << 8)
                if off & 0x8000: off -= 65536
                return "%s, %+d" % (reg(a), off)
            elif fmt == "21s":
                a = d[1]
                lit = d[2] | (d[3] << 8)
                if lit & 0x8000: lit -= 65536
                return "%s, #%d" % (reg(a), lit)
            elif fmt == "21h":
                a = d[1]
                lit = d[2] | (d[3] << 8)
                if "wide" in mn or "high16" in mn:
                    return "%s, #%dL" % (reg(a), (lit << 16))
                return "%s, #%d" % (reg(a), lit)
            elif fmt == "31i":
                a = d[1]
                lit = d[2] | (d[3] << 8) | (d[4] << 16) | (d[5] << 24)
                return "%s, #0x%08x" % (reg(a), lit)
            elif fmt == "31t":
                a = d[1]
                off = d[2] | (d[3] << 8) | (d[4] << 16) | (d[5] << 24)
                return "%s, %+d" % (reg(a), off)
            elif fmt == "31c":
                a = d[1]
                idx = d[2] | (d[3] << 8) | (d[4] << 16) | (d[5] << 24)
                s = self.string(idx)
                return '%s, "%s"' % (reg(a), s or "")
            elif fmt == "51l":
                a = d[1]
                lit = d[2] | (d[3] << 8) | (d[4] << 16) | (d[5] << 24)
                lit |= (d[6] << 32) | (d[7] << 40) | (d[8] << 48) | (d[9] << 56)
                return "%s, #0x%016xL" % (reg(a), lit)
            elif fmt == "21c":
                a = d[1]
                idx = d[2] | (d[3] << 8)
                if "string" in mn:
                    s = self.string(idx)
                    return '%s, "%s"' % (reg(a), s or "")
                elif "class" in mn:
                    return "%s, %s" % (reg(a), self.type_name(idx))
                elif "sget" in mn or "sput" in mn or "new-instance" in mn:
                    n, t = self.field_ref(idx) if "new-instance" not in mn else (None, self.type_name(idx))
                    if "new-instance" in mn:
                        return "%s, %s" % (reg(a), t)
                    return "%s, %s.%s:%s" % (reg(a), "?", n, t)
                else:
                    return "%s, [idx=%d]" % (reg(a), idx)
            elif fmt == "22c":
                a = (d[0] >> 4) & 0xF
                b = d[0] & 0xF
                idx = d[2] | (d[3] << 8)
                if "instance-of" in mn:
                    return "%s, %s, %s" % (reg(b), reg(a), self.type_name(idx))
                elif "new-array" in mn:
                    return "%s, %s, %s" % (reg(b), reg(a), self.type_name(idx))
                elif "iget" in mn or "iput" in mn:
                    n, t = self.field_ref(idx)
                    return "%s, %s, .field %s:%s" % (reg(a), reg(b), n, t)
                else:
                    return "%s, %s, [idx=%d]" % (reg(a), reg(b), idx)
            elif fmt == "23x":
                a = (d[0] >> 4) & 0xF
                b = d[0] & 0xF
                c = d[1]
                return "%s, %s, %s" % (reg(a), reg(b), reg(c))
            elif fmt == "22b":
                a = (d[0] >> 4) & 0xF
                b = d[0] & 0xF
                lit = d[2]
                if lit & 0x80: lit -= 256
                return "%s, %s, #%d" % (reg(a), reg(b), lit)
            elif fmt == "22s":
                a = (d[0] >> 4) & 0xF
                b = d[0] & 0xF
                lit = d[2] | (d[3] << 8)
                if lit & 0x8000: lit -= 65536
                return "%s, %s, #%d" % (reg(a), reg(b), lit)
            elif fmt == "22t":
                a = (d[0] >> 4) & 0xF
                b = d[0] & 0xF
                off = d[2] | (d[3] << 8)
                if off & 0x8000: off -= 65536
                return "%s, %s, %+d" % (reg(a), reg(b), off)
            elif fmt == "32x":
                a = d[1] | (d[2] << 8)
                b = d[3] | (d[4] << 8)
                return "%s, %s" % (reg(a), reg(b))
            elif fmt == "30t":
                off = d[1] | (d[2] << 8) | (d[3] << 16) | (d[4] << 24)
                if off & 0x80000000: off -= 0x100000000
                return "%+d" % off
            elif fmt == "35c":
                a = (d[0] >> 4) & 0xF  # arg count
                b = d[1]
                idx = d[2] | (d[3] << 8)
                regs_used = []
                reg_letters = [d[4], d[5], (d[0] & 0xF)]
                # 35c format: AAAA BBBB CCCC DDDD EEEE FFFF
                # where A=count, B..F = reg list in 4-bit chunks
                # Byte layout: [op|A][B][idx_lo][idx_hi][C][D][E][F]
                # Actually it's: AA BBBBBB CCCC DDDD EEEE FFFF
                # raw = [op|A4, B4, idx_lo, idx_hi, C4|D4, E4|F4]
                c_reg = (d[4] >> 4) & 0xF
                d_reg = d[4] & 0xF
                e_reg = (d[5] >> 4) & 0xF
                f_reg = d[5] & 0xF
                # B is first reg, then C,D,E,F
                reg_list = []
                if a >= 1: reg_list.append(b)
                if a >= 2: reg_list.append(c_reg)
                if a >= 3: reg_list.append(d_reg)
                if a >= 4: reg_list.append(e_reg)
                if a >= 5: reg_list.append(f_reg)
                mr = self.method_ref(idx)
                if mr:
                    cls, name, params, ret = mr
                    reg_strs = ", ".join(reg(r) for r in reg_list)
                    return "%s.%s(%s)%s { %s }" % (cls, name, ", ".join(params), ret, reg_strs)
                return "[idx=%d] { %s }" % (idx, ", ".join(reg(r) for r in reg_list))
            elif fmt == "3rc":
                a = (d[0] >> 4) & 0xF  # count
                idx = d[2] | (d[3] << 8)
                first_reg = d[4] | (d[5] << 8)
                mr = self.method_ref(idx)
                if mr:
                    cls, name, params, ret = mr
                    return "%s.%s(%s)%s { v%d .. v%d }" % (cls, name, ", ".join(params), ret, first_reg, first_reg + a - 1)
                return "[idx=%d] { v%d .. v%d }" % (idx, first_reg, first_reg + a - 1)
            elif fmt == "45cc":
                idx = d[2] | (d[3] << 8)
                mr = self.method_ref(idx)
                if mr:
                    cls, name, params, ret = mr
                    return "%s.%s(%s)%s (polymorphic)" % (cls, name, ", ".join(params), ret)
                return "[idx=%d] (polymorphic)" % idx
            elif fmt == "4rcc":
                idx = d[2] | (d[3] << 8)
                mr = self.method_ref(idx)
                if mr:
                    cls, name, params, ret = mr
                    return "%s.%s(%s)%s (polymorphic/range)" % (cls, name, ", ".join(params), ret)
                return "[idx=%d] (polymorphic/range)" % idx
            else:
                return "[fmt=%s]" % fmt
        except (IndexError, struct.error):
            return "(decode error)"

# ── main ─────────────────────────────────────────────────────────────────
def process_dex(label, dex_data, out_dir):
    dex = DexFile(dex_data)
    if not dex.is_dex():
        print("  SKIP %s (not a valid dex)" % label)
        return

    out_path = os.path.join(out_dir, "%s_classes.txt" % label.replace(".dex", ""))
    classes = dex.classes()
    total_methods = 0
    with open(out_path, "w", encoding="utf-8", errors="replace") as f:
        f.write("# Disassembled from %s\n" % label)
        f.write("# %d classes, %d strings, %d types, %d methods\n\n" % (
            len(classes), dex.string_count, dex.type_count, dex.method_count
        ))
        for (name, sup, fields, methods, code_offs) in classes:
            total_methods += len(methods)
            # Class header
            f.write("\n" + "="*78 + "\n")
            f.write("class %s" % name)
            if sup:
                f.write(" extends %s" % sup)
            f.write("\n")
            f.write("="*78 + "\n")

            # Fields
            static_fields = [(n, t) for n, t, s in fields if s]
            inst_fields = [(n, t) for n, t, s in fields if not s]
            if static_fields:
                f.write("\n  # static fields\n")
                for n, t in static_fields:
                    f.write("  .field static %s %s\n" % (t, n))
            if inst_fields:
                f.write("\n  # instance fields\n")
                for n, t in inst_fields:
                    f.write("  .field %s %s\n" % (t, n))

            # Methods
            f.write("\n  # methods (%d)\n" % len(methods))
            for i, m in enumerate(methods):
                cls_name, meth_name, params, ret, is_static, kind = m
                co = code_offs[i] if i < len(code_offs) else 0
                modifiers = "static " if is_static else ""
                f.write("\n  .method %s%s %s(%s)%s\n" % (
                    modifiers, kind, meth_name, ", ".join(params), ret
                ))
                # Disassemble body
                if co and co != 0:
                    result = dex.disasm_code(co)
                    if result and isinstance(result, tuple) and len(result) == 4:
                        instrs, regs, ins_sz, outs_sz = result
                        f.write("    # registers=%d, ins=%d, outs=%d\n" % (regs, ins_sz, outs_sz))
                        for (off, mn, operands) in instrs:
                            f.write("    %04x: %-28s %s\n" % (off, mn, operands))
                    else:
                        f.write("    # (no code)\n")
                else:
                    f.write("    # (abstract/native/no code)\n")
                f.write("  .end method\n")

    sz = os.path.getsize(out_path)
    print("  OK %s → %s (%d classes, %d methods, %.1f MB)" % (
        label, os.path.basename(out_path), len(classes), total_methods, sz/1048576.0
    ))


def iter_dexes(input_path):
    """Yield (label, data) for APK, directory of .dex, or single .dex file."""
    if os.path.isdir(input_path):
        for name in sorted(os.listdir(input_path)):
            if not name.endswith(".dex"): continue
            with open(os.path.join(input_path, name), "rb") as fh:
                yield name, fh.read()
    elif input_path.endswith(".apk") or input_path.endswith(".apk.1") or zipfile.is_zipfile(input_path):
        with zipfile.ZipFile(input_path) as zf:
            names = sorted(n for n in zf.namelist() if n.startswith("classes") and n.endswith(".dex"))
            for name in names:
                yield name, zf.read(name)
    elif input_path.endswith(".dex"):
        with open(input_path, "rb") as fh:
            yield os.path.basename(input_path), fh.read()
    else:
        print("Unknown input: %s" % input_path)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    if len(args) < 1:
        print("Usage: dex2text.py <input.dex|input.apk|dir> [output_dir]")
        sys.exit(1)
    input_path = args[0]
    out_dir = args[1] if len(args) > 1 else os.path.join(os.path.dirname(input_path) or ".", "analysis")

    if not os.path.exists(input_path):
        print("Input not found: %s" % input_path)
        sys.exit(1)

    os.makedirs(out_dir, exist_ok=True)
    print("Output directory: %s" % out_dir)

    total_classes = 0
    for label, data in iter_dexes(input_path):
        print("Processing %s (%.1f MB)..." % (label, len(data)/1048576.0))
        process_dex(label, data, out_dir)

    print("\nDone. Output in: %s" % out_dir)


if __name__ == "__main__":
    main()