#!/usr/bin/env python3
"""Change only the embedded Vehicle-page model label; keep native code and offsets intact.

The supplied recipe already contains the plist from data/vspi.bplist. A shorter ASCII
label can occupy the same object slot, leaving padding before the next object. All
offsets, object references, the trailer and the compiled VSPI length stay unchanged.
For a longer label, regenerate the plist with MkVspi and rebuild against the stock library.
"""
import argparse
import plistlib
from pathlib import Path


def renamed(value, old, new):
    if isinstance(value, dict):
        return {renamed(k, old, new): renamed(v, old, new) for k, v in value.items()}
    if isinstance(value, list):
        return [renamed(v, old, new) for v in value]
    return new if value == old else value


def replace_label(blob, old, new):
    before, after = old.encode("ascii"), new.encode("ascii")
    if len(after) > len(before) or len(before) >= 15:
        raise ValueError("only shorter labels in small ASCII object slots are supported")
    if blob[:8] != b"bplist00":
        raise ValueError("expected a binary plist")
    size = blob[-26]
    count = int.from_bytes(blob[-24:-16], "big")
    table = int.from_bytes(blob[-8:], "big")
    result = bytearray(blob)
    changed = 0
    for i in range(count):
        offset = int.from_bytes(blob[table + i * size:table + (i + 1) * size], "big")
        if blob[offset] == (0x50 | len(before)) and blob[offset + 1:offset + 1 + len(before)] == before:
            result[offset:offset + 1 + len(before)] = bytes([0x50 | len(after)]) + after + bytes(len(before) - len(after))
            changed += 1
    if not changed:
        raise ValueError("old label not found; the description may already be updated")
    if len(result) != len(blob) or plistlib.loads(result) != renamed(plistlib.loads(blob), old, new):
        raise ValueError("description changed beyond the requested label")
    return bytes(result), changed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("old")
    parser.add_argument("new")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    data = root / "data/vspi.bplist"
    recipe = root / "mod/recipes/carplay-climate-m1.recipe"
    before = data.read_bytes()
    after, count = replace_label(before, args.old, args.new)
    text = recipe.read_bytes().decode()
    newline = "\r\n" if "\r\n" in text else "\n"
    lines = text.splitlines()
    matches = 0
    for i, line in enumerate(lines):
        parts = line.split()
        if parts[:1] != ["segment"]:
            continue
        segment = bytes.fromhex(parts[2])
        if segment.count(before) != 1:
            raise ValueError("recipe must contain the exact original plist once")
        updated = segment.replace(before, after, 1)
        start = segment.index(before)
        if updated[:start] != segment[:start] or updated[start + len(before):] != segment[start + len(before):]:
            raise ValueError("native bytes outside the description changed")
        lines[i] = " ".join([parts[0], parts[1], updated.hex()])
        matches += 1
    if matches != 1:
        raise ValueError("expected one recipe segment")
    lines[1] = "# stock d3a47296437df56fb1ffc7ac3dfca4cb; memory only; model label updated by tools/set_vehicle_model.py"
    data.write_bytes(after)
    recipe.write_bytes((newline.join(lines) + newline).encode())
    print(f"Updated {count} label(s) to {args.new}; plist length {len(after)}, native code and offsets unchanged")


if __name__ == "__main__":
    main()
