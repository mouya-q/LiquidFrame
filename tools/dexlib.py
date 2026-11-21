"""Minimal, dependency-free DEX reader for LiquidFrame camera-APK analysis.

Why this exists
---------------
The module has to run on many MIUI/HyperOS camera builds, and each build is
obfuscated independently. Determining *which* class plays a pipeline role from a
new APK therefore has to be structural, not name-based. This reader recovers the
structure that decision needs: class names, superclasses, declared fields with
their types, and declared methods with their prototypes.

Correctness notes that cost a debugging round already:
  * `class_data_item` stores fields and methods as *deltas*. The index must be
    accumulated (`idx += diff`); assigning the delta yields plausible-looking
    but wrong members for every class after the first.
  * String data is MUTF-8 with a uleb128 utf16 length prefix.
  * `proto_id_item` carries the return type; parameters live in a type_list.

Usage:  from dexlib import DexFile
"""

import os

ACC_STATIC = 0x0008


def uleb128(data, p):
    r = 0
    shift = 0
    while p < len(data):
        b = data[p]
        p += 1
        r |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            break
        shift += 7
        if shift > 28:
            break
    return r, p


def u2(d, p):
    return d[p] | (d[p + 1] << 8)


def u4(d, p):
    return d[p] | (d[p + 1] << 8) | (d[p + 2] << 16) | (d[p + 3] << 24)


def _read_string(d, off):
    _, p = uleb128(d, off)
    out = []
    while p < len(d):
        b = d[p]
        p += 1
        if b == 0:
            break
        if b < 0x80:
            out.append(chr(b))
        elif (b & 0xE0) == 0xC0:
            if p >= len(d):
                break
            b2 = d[p]
            p += 1
            out.append(chr(((b & 0x1F) << 6) | (b2 & 0x3F)))
        else:
            if p + 1 >= len(d):
                break
            b2 = d[p]
            b3 = d[p + 1]
            p += 2
            out.append(chr(((b & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F)))
    return "".join(out)


class MethodRef(object):
    __slots__ = ("cls", "name", "params", "ret", "is_static")

    def __init__(self, cls, name, params, ret, is_static=False):
        self.cls = cls
        self.name = name
        self.params = params
        self.ret = ret
        self.is_static = is_static

    def signature(self):
        return "%s->%s(%s)%s" % (
            self.cls, self.name, "".join(self.params), self.ret
        )

    def __repr__(self):
        return "<%s static=%s>" % (self.signature(), self.is_static)


class DexClass(object):
    __slots__ = ("name", "super_name", "fields", "methods", "dex")

    def __init__(self, name, super_name, fields, methods, dex):
        self.name = name
        self.super_name = super_name
        # list of (field_name, type_descriptor, is_static)
        self.fields = fields
        # list of MethodRef
        self.methods = methods
        self.dex = dex

    def instance_field_types(self):
        return [t for (_, t, st) in self.fields if not st]

    def __repr__(self):
        return "<DexClass %s (%d fields, %d methods)>" % (
            self.name, len(self.fields), len(self.methods)
        )


class DexFile(object):
    def __init__(self, data):
        self.d = data
        self.string_count = u4(data, 0x38)
        self.string_ids_off = u4(data, 0x3C)
        self.type_count = u4(data, 0x40)
        self.type_ids_off = u4(data, 0x44)
        self.proto_ids_off = u4(data, 0x4C)
        self.field_ids_off = u4(data, 0x54)
        self.method_ids_off = u4(data, 0x5C)
        self.class_defs_off = u4(data, 0x64)
        self.class_count = u4(data, 0x60)
        self._strings = {}
        self._types = {}

    def is_dex(self):
        return len(self.d) >= 0x70 and self.d[0:4] == b"dex\n"

    # ---- id lookups -------------------------------------------------------
    def string(self, idx):
        if idx in self._strings:
            return self._strings[idx]
        if idx < 0 or idx >= self.string_count:
            return None
        off = u4(self.d, self.string_ids_off + idx * 4)
        val = _read_string(self.d, off) if 0 < off < len(self.d) else None
        self._strings[idx] = val
        return val

    def type_name(self, idx):
        if idx in self._types:
            return self._types[idx]
        if idx >= self.type_count:
            return "?"
        p = self.type_ids_off + idx * 4
        if p + 4 > len(self.d):
            return "?"
        val = self.string(u4(self.d, p)) or "?"
        self._types[idx] = val
        return val

    def method_ref(self, idx):
        b = self.method_ids_off + idx * 8
        if b + 8 > len(self.d):
            return None
        cls_idx = u2(self.d, b)
        proto_idx = u2(self.d, b + 2)
        name = self.string(u4(self.d, b + 4)) or "?"
        cls = self.type_name(cls_idx)
        ret, params = self.proto(proto_idx)
        return MethodRef(cls, name, params, ret)

    def proto(self, proto_idx):
        pb = self.proto_ids_off + proto_idx * 12
        if pb + 12 > len(self.d):
            return "?", []
        ret = self.type_name(u4(self.d, pb + 4))
        params_off = u4(self.d, pb + 8)
        params = []
        if params_off and params_off + 4 <= len(self.d):
            size = u4(self.d, params_off)
            for i in range(min(size, 255)):
                pp = params_off + 4 + i * 2
                if pp + 2 > len(self.d):
                    break
                params.append(self.type_name(u2(self.d, pp)))
        return ret, params

    def field_ref(self, idx):
        b = self.field_ids_off + idx * 8
        if b + 8 > len(self.d):
            return None, None
        type_idx = u2(self.d, b + 2)
        name = self.string(u4(self.d, b + 4)) or "?"
        return name, self.type_name(type_idx)

    # ---- classes ----------------------------------------------------------
    def classes(self):
        out = []
        for ci in range(self.class_count):
            base = self.class_defs_off + ci * 32
            if base + 32 > len(self.d):
                break
            cidx = u4(self.d, base)
            sidx = u4(self.d, base + 8)
            cdo = u4(self.d, base + 24)
            if cidx >= self.type_count:
                continue
            name = self.type_name(cidx)
            sup = None
            if sidx != 0xFFFFFFFF and sidx < self.type_count:
                sup = self.type_name(sidx)
            fields, methods = [], []
            if cdo != 0 and cdo < len(self.d):
                self._read_class_data(cdo, fields, methods)
            out.append(DexClass(name, sup, fields, methods, self))
        return out

    def _read_class_data(self, off, fields, methods):
        p = off
        sf, p = uleb128(self.d, p)
        ifv, p = uleb128(self.d, p)
        dm, p = uleb128(self.d, p)
        vm, p = uleb128(self.d, p)

        # static fields
        idx = 0
        for _ in range(sf):
            diff, p = uleb128(self.d, p)
            idx += diff
            _, p = uleb128(self.d, p)  # access flags
            n, t = self.field_ref(idx)
            if n is not None:
                fields.append((n, t, True))
        # instance fields
        idx = 0
        for _ in range(ifv):
            diff, p = uleb128(self.d, p)
            idx += diff
            _, p = uleb128(self.d, p)
            n, t = self.field_ref(idx)
            if n is not None:
                fields.append((n, t, False))
        # direct methods
        idx = 0
        for _ in range(dm):
            diff, p = uleb128(self.d, p)
            idx += diff
            access, p = uleb128(self.d, p)
            _, p = uleb128(self.d, p)  # code_off
            mr = self.method_ref(idx)
            if mr is not None:
                mr.is_static = bool(access & ACC_STATIC)
                methods.append(mr)
        # virtual methods
        idx = 0
        for _ in range(vm):
            diff, p = uleb128(self.d, p)
            idx += diff
            access, p = uleb128(self.d, p)
            _, p = uleb128(self.d, p)
            mr = self.method_ref(idx)
            if mr is not None:
                mr.is_static = bool(access & ACC_STATIC)
                methods.append(mr)


def iter_apk_dexes(apk_or_dir):
    """Yields (label, DexFile) for a directory of .dex files or an APK/ZIP."""
    import zipfile

    if os.path.isdir(apk_or_dir):
        for name in sorted(os.listdir(apk_or_dir)):
            if not name.endswith(".dex"):
                continue
            with open(os.path.join(apk_or_dir, name), "rb") as fh:
                data = fh.read()
            dex = DexFile(data)
            if dex.is_dex():
                yield name, dex
        return

    with zipfile.ZipFile(apk_or_dir) as zf:
        names = sorted(
            n for n in zf.namelist()
            if n.startswith("classes") and n.endswith(".dex")
        )
        for name in names:
            data = zf.read(name)
            dex = DexFile(data)
            if dex.is_dex():
                yield name, dex