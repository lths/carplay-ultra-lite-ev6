#!/usr/bin/env python3
"""Puts the car's page into the description the native part sends when CarPlay connects.

The phone builds CarPlay's Vehicle app from vehicleStateProtocolInfo in the /info answer (data/vspi.bplist,
built into the native recipe), not from the configResponse on the vehicle state channel. This replaces the
automaker settings accessory's services (LIVI's two version lines) in LIVI's description with the page's,
as tools/MkVspi.java prints them.

usage: mkvspi.py <LIVI's vspi.bplist> <services.json from MkVspi> <out vspi.bplist>"""
import json, plistlib, struct, sys


def encode(root):
    """A binary plist written as LIVI's own writer does (data/vsdump-src/bplist.rs): every value its own
    object, in the order met, nothing shared. Python's plistlib shares equal values, which the head unit's
    plist reader has never been given; LIVI's description re-encoded with this is byte for byte the original."""
    nodes = []

    def marker(kind, count):
        if count < 0x0f:
            return bytes([(kind << 4) | count])
        size, log = (4, 2) if count > 0xffff else (2, 1) if count > 0xff else (1, 0)
        return bytes([(kind << 4) | 0x0f, 0x10 | log]) + count.to_bytes(size, "big")

    def add(v):
        idx = len(nodes)
        nodes.append(None)
        if isinstance(v, bool):
            node = (bytes([9 if v else 8]), None)
        elif isinstance(v, int):
            if v < 0:
                sys.exit("a negative number in the description")
            size, log = (8, 3) if v > 0xffffffff else (4, 2) if v > 0xffff else (2, 1) if v > 0xff else (1, 0)
            node = (bytes([0x10 | log]) + v.to_bytes(size, "big"), None)
        elif isinstance(v, float):
            node = (bytes([0x23]) + struct.pack(">d", v), None)
        elif isinstance(v, str):
            if v.isascii():
                node = (marker(0x5, len(v)) + v.encode("ascii"), None)
            else:
                u = v.encode("utf-16-be")
                node = (marker(0x6, len(u) // 2) + u, None)
        elif isinstance(v, (bytes, bytearray)):
            node = (marker(0x4, len(v)) + bytes(v), None)
        elif isinstance(v, list):
            node = (marker(0xa, len(v)), [add(x) for x in v])
        elif isinstance(v, dict):
            refs = [add(k) for k in v]
            refs += [add(x) for x in v.values()]
            node = (marker(0xd, len(v)), refs)
        else:
            sys.exit(f"a {type(v).__name__} in the description")
        nodes[idx] = node
        return idx

    top = add(root)
    ref_size = 4 if len(nodes) > 0xffff else 2 if len(nodes) > 0xff else 1
    out = bytearray(b"bplist00")
    offsets = []
    for head, refs in nodes:
        offsets.append(len(out))
        out += head
        for r in refs or []:
            out += r.to_bytes(ref_size, "big")
    table = len(out)
    offset_size = 4 if table > 0xffff else 2 if table > 0xff else 1
    for o in offsets:
        out += o.to_bytes(offset_size, "big")
    out += bytes(6) + bytes([offset_size, ref_size]) + struct.pack(">QQQ", len(nodes), top, table)
    return bytes(out)

ACCESSORY = 1280   # automaker settings

src, services, out = sys.argv[1:4]
with open(src, "rb") as f:
    original = f.read()
d = plistlib.loads(original)
if encode(d) != original:
    sys.exit("this writer doesn't reproduce LIVI's description byte for byte")
with open(services, encoding="utf-8") as f:
    new = json.load(f)
found = [a for c in d["pluginConfigs"] for a in c["accessories"] if a["iid"] == ACCESSORY]
if len(found) != 1:
    sys.exit(f"{len(found)} automaker settings accessories in {src}")
taken = {c["iid"] for cfg in d["pluginConfigs"] for a in cfg["accessories"] if a["iid"] != ACCESSORY
         for s in a["services"] for c in [s] + s["characteristics"]} | {a["iid"] for cfg in d["pluginConfigs"] for a in cfg["accessories"]}
mine = [c["iid"] for s in new for c in [s] + s["characteristics"]]
if len(set(mine)) != len(mine) or taken & set(mine):
    sys.exit("the page's iids repeat or collide with LIVI's")
found[0]["services"] = new
data = encode(d)
if plistlib.loads(data) != d:
    sys.exit("the new description doesn't read back as written")
with open(out, "wb") as f:
    f.write(data)
print(f"{out}: {len(data)} bytes, {len(new)} services, {len(mine)} iids in accessory {ACCESSORY}")
