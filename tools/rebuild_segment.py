#!/usr/bin/env python3
"""Rebuild our injected segment using the existing recipe's verified library bindings.

This does not rediscover or relax any stock-library offset. It reuses all verify/patch
sites, and every rel32 target, from the checked d3a47296 recipe; only our own segment
and its internal branch/table offsets are regenerated. No manufacturer binary is needed.
"""
import argparse
import hashlib
import json
import struct
import subprocess
from pathlib import Path
from elftools.elf.elffile import ELFFile

ROOT = Path(__file__).resolve().parents[1]
SEGMENT = 0x30000
STUBS = {0x9c20: "cpc_stub_info", 0x11718: "cpc_stub_stream", 0x12430: "cpc_stub_setup"}
GUARDS_SHA256 = "ccd98c09cd21ae62899322d244fb08463bf1e3eb8d66de982d0098ddfdc5fab8"
TARGETS_SHA256 = "60cb57eebe5e3acf5ccf3bc0b937dcc9116b97aa6e14adf9b161501456870a54"


def guards(lines):
    return [line for line in lines if line.startswith(("verify ", "patch ", "file ", "id "))]


def run(*args):
    subprocess.run([str(x) for x in args], cwd=ROOT, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zig", type=Path, required=True)
    parser.add_argument("--reference", type=Path)
    args = parser.parse_args()
    recipe = ROOT / "mod/recipes/carplay-climate-m1.recipe"
    original = (args.reference or recipe).read_bytes().decode()
    lines = original.splitlines()
    if "d3a47296437df56fb1ffc7ac3dfca4cb" not in original or "file /system/lib/libCarPlayCore.so" not in lines:
        raise ValueError("only the existing checked CarPlayCore library recipe is supported")
    references = [line.split() for line in lines if line.startswith("rel32 ")]
    if len(references) != 33 or any(int(r[1], 0) - int(r[2], 0) != 4 * i for i, r in enumerate(references)):
        raise ValueError("unexpected reference table layout")
    targets = [int(r[3], 0) for r in references]
    if hashlib.sha256("\n".join(guards(lines)).encode()).hexdigest() != GUARDS_SHA256:
        raise ValueError("reference stock guards or byte patches differ from the checked recipe")
    if hashlib.sha256(json.dumps(targets).encode()).hexdigest() != TARGETS_SHA256:
        raise ValueError("reference library bindings differ from the checked recipe")
    old_branches = {int(line.split()[1], 0) for line in lines if line.startswith("branch ")}
    if old_branches != set(STUBS): raise ValueError("unexpected branch sites")
    work = ROOT / "out/native"
    work.mkdir(parents=True, exist_ok=True)
    flags = ["--target=armv7a-linux-androideabi", "-mthumb", "-march=armv7-a", "-mfloat-abi=soft", "-Os",
        "-fPIC", "-ffreestanding", "-fno-builtin", "-fno-stack-protector", "-fno-unwind-tables",
        "-fno-asynchronous-unwind-tables", "-fvisibility=hidden", "-fno-common", "-Wall", "-Wextra", "-Werror"]
    native = ROOT / "native"
    run("clang", *flags, "-I", native, f'-DVSPI_FILE="{ROOT / "data/vspi.bplist"}"', "-c", native / "seg.S", "-o", work / "seg.o")
    run("clang", *flags, "-I", native, "-c", native / "hooks.c", "-o", work / "hooks.o")
    linker = work / "seg.ld"
    linker.write_text("ENTRY(cpc_stub_info)\nSECTIONS { . = 0x30000; .seg : { *(.text.entry) *(.text .text.*) *(.rodata .rodata.*) } "
        "/DISCARD/ : { *(.ARM.exidx*) *(.ARM.attributes) *(.comment) *(.note*) } }\n")
    run(args.zig.resolve(), "ld.lld", "-static", "--no-dynamic-linker", "-T", linker, "-o", work / "seg.elf", work / "seg.o", work / "hooks.o")
    with (work / "seg.elf").open("rb") as stream:
        elf = ELFFile(stream)
        if elf.elfclass != 32 or not elf.little_endian or elf["e_machine"] != "EM_ARM":
            raise ValueError("injected segment must be little-endian ARM32")
        if [s.name for s in elf.iter_sections() if s["sh_flags"] & 2] != [".seg"]:
            raise ValueError("injected code must have exactly one allocated section")
        if any(s.name.startswith(".rel") for s in elf.iter_sections()): raise ValueError("unresolved relocations")
        if any(s.name and s["st_shndx"] == "SHN_UNDEF" for s in elf.get_section_by_name(".symtab").iter_symbols()):
            raise ValueError("undefined injected symbols")
        symbols = {s.name: s["st_value"] for s in elf.get_section_by_name(".symtab").iter_symbols() if s.name}
        section = elf.get_section_by_name(".seg")
        if section["sh_addr"] != SEGMENT: raise ValueError("wrong link address")
        code = bytearray(section.data())
    table = symbols["cpc_table"] - SEGMENT
    for i, target in enumerate(targets): struct.pack_into("<i", code, table + i * 4, target - (SEGMENT + table))
    blob = (ROOT / "data/vspi.bplist").read_bytes()
    start = symbols["cpc_vspi"] - SEGMENT
    end = symbols["cpc_vspi_end"] - SEGMENT
    if code[start:end] != blob: raise ValueError("compiled vehicle description differs from source data")
    rewritten = []
    emitted = False
    for line in lines:
        if line.startswith("segment "):
            rewritten.append("segment 0x30000 " + code.hex())
        elif line.startswith("rel32 "):
            if not emitted:
                rewritten.extend(f"rel32 {table + i * 4:#x} {table:#x} {target:#x}" for i, target in enumerate(targets))
                emitted = True
        elif line.startswith("branch "):
            at = int(line.split()[1], 0)
            rewritten.append(f"branch {at:#x} {(symbols[STUBS[at]] & ~1) - SEGMENT:#x}")
        elif line.startswith("#") and ("stock d3a" in line or "generated by" in line):
            rewritten.append("# stock d3a47296437df56fb1ffc7ac3dfca4cb; own segment rebuilt with unchanged stock verify/patch sites and bindings")
        else: rewritten.append(line)
    if guards(lines) != guards(rewritten): raise ValueError("stock guards or patch sites changed")
    recipe.write_bytes(("\r\n".join(rewritten) + "\r\n").encode())
    (work / "segment.bin").write_bytes(code)
    print(f"Rebuilt {len(code)} injected bytes, description {len(blob)} bytes, table {table:#x}; all stock guards and bindings preserved")
    print("Segment SHA256:", hashlib.sha256(code).hexdigest())


if __name__ == "__main__": main()
