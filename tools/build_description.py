#!/usr/bin/env python3
"""Generate the native Vehicle page from the same Java schema used at runtime."""
import argparse
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    args = parser.parse_args()
    work = ROOT / "out/description"
    classes = work / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    lib = args.sdk.resolve() / "lib"
    cp = os.pathsep.join(str(p) for p in (args.android_jar.resolve(), lib / "hmgapi-runtime.jar", lib / "hmgapi-runtime-stubs.jar"))
    sources = sorted((ROOT / "mod/src/ioniq6/cpclimate").glob("*.java"))
    subprocess.run(["javac", "--release", "8", "-cp", cp, "-d", str(classes), *map(str, sources),
        str(ROOT / "tools/MkVspi.java")], cwd=ROOT, check=True)
    generated = subprocess.run(["java", "-cp", str(classes) + os.pathsep + cp, "ioniq6.cpclimate.MkVspi", "EV6"],
        cwd=ROOT, check=True, capture_output=True)
    services = work / "services.json"
    services.write_bytes(generated.stdout)
    subprocess.run(["python3", str(ROOT / "tools/mkvspi.py"), str(ROOT / "data/vspi-livi.bplist"), str(services),
        str(ROOT / "data/vspi.bplist")], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
