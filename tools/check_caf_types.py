#!/usr/bin/env python3
"""Checks the type numbers VehiclePage.java sends against Apple's own names for them, read from an extracted
CarAccessoryFramework binary (the _CAFServiceType*/_CAFCharacteristicType*/_CAFAccessoryType* constants, each a
CFString holding the number). A number Apple doesn't know, or knows under another name, fails.

usage: check_caf_types.py <CarAccessoryFramework> <VehiclePage.java>      (needs: pip install lief)"""
import re, struct, sys
import lief

EXPECT = {  # VehiclePage constant -> Apple's name
    "STATIC_SETTING": "ServiceTypeStaticSetting", "BOOLEAN_SETTING": "ServiceTypeBooleanSetting",
    "BUTTON_SETTING": "ServiceTypeButtonSetting", "SECTION": "ServiceTypeSettingsSection",
    "T_IDENTIFIER": "CharacteristicTypeIdentifier", "T_LABEL": "CharacteristicTypeUserVisibleLabel",
    "T_VALUE": "CharacteristicTypeUserVisibleValue", "T_CATEGORY": "CharacteristicTypeSettingsCategory",
    "T_SYMBOL": "CharacteristicTypeSymbolName", "T_SORT": "CharacteristicTypeSortOrder",
    "T_SECTION": "CharacteristicTypeSectionIdentifier", "T_ON": "CharacteristicTypeOn",
    "T_BUTTON_ACTION": "CharacteristicTypeButtonAction",
    "T_PROMINENCE": "CharacteristicTypeProminenceInfo", "T_CHILDREN": "CharacteristicTypeChildrenSettingsIdentifiers",
}
BASE = 0x180000000  # the dyld shared cache's base: its pointers keep the target in their low 36 bits


def apple_types(path):
    lief.logging.disable()
    m = lief.parse(path)

    def rd(va, n):
        try:
            return bytes(m.get_content_from_virtual_address(va, n))
        except Exception:
            return None

    out = {}
    for s in m.symbols:
        if not re.match(r"_CAF(Service|Characteristic|Accessory)Type", s.name) or not s.value:
            continue
        p = rd(s.value, 8)
        cf = rd((struct.unpack("<Q", p)[0] & 0xFFFFFFFFF) + BASE, 32) if p else None
        if not cf:
            continue
        sp, ln = struct.unpack("<QQ", cf[16:32])
        text = rd((sp & 0xFFFFFFFFF) + BASE, ln)
        if text and text.startswith(b"0x"):
            out[s.name[4:]] = int(text, 16)
    return out


def main():
    apple = apple_types(sys.argv[1])
    src = open(sys.argv[2], encoding="utf-8").read()
    mine = {k: int(v, 16) for k, v in re.findall(r"\b([A-Z_]+) = (0x[0-9A-Fa-f]+)L", src)}
    fails = 0
    for const, name in EXPECT.items():
        ok = const in mine and apple.get(name) == mine[const]
        fails += not ok
        print(("ok   " if ok else "FAIL ") + f"{const} {mine.get(const, 0):#x} is Apple's {name} ({apple.get(name, 0):#x})")
    ok = apple.get("AccessoryTypeAutomakerSettings") == 0x5000001
    fails += not ok
    print(("ok   " if ok else "FAIL ") + "the accessory the page goes into (0x5000001) is Apple's AutomakerSettings")
    extra = [k for k in mine if k.startswith(("T_", "STATIC", "BOOLEAN", "BUTTON", "SECTION")) and k not in EXPECT and k != "T_SECTION_NAME"]
    print(("ok   " if not extra else "FAIL ") + f"every type constant in VehiclePage is checked ({len(EXPECT)}; unchecked: {extra})")
    fails += bool(extra)
    print("ALL OK" if not fails else f"{fails} FAILED")
    sys.exit(1 if fails else 0)


main()
