#!/usr/bin/env python3
"""Build and sign a checker or mod using D8 and the HMG-API mod SDK (no manufacturer binaries)."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True)


def archive(folder, destination, prefix=""):
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as output:
        for path in sorted(folder.rglob("*")):
            if path.is_file() and path.name != ".DS_Store":
                output.write(path, prefix + path.relative_to(folder).as_posix())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("check", "mod"))
    parser.add_argument("--d8-jar", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--key", type=Path, default=Path.home() / ".config/carplay-ultra-lite-ev6/author.key")
    args = parser.parse_args()
    args.sdk = args.sdk.resolve()
    args.d8_jar = args.d8_jar.resolve()
    args.key = args.key.expanduser().resolve()
    if args.key.is_relative_to(ROOT):
        parser.error("keep the signing key outside the repository")
    engine = args.sdk / "lib/hmgapi-engine.jar"
    if not engine.is_file() or not args.d8_jar.is_file():
        parser.error("missing SDK engine or D8 jar")
    src = ROOT / "mod/src/ioniq6/cpclimate"
    template = ROOT / ("check/mod" if args.kind == "check" else "mod")
    metadata = json.loads((template / "mod.json").read_text())
    output = ROOT / "out" / args.kind
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="ev6-package-") as temporary:
        work = Path(temporary)
        package = work / "package"
        shutil.copytree(template, package, ignore=shutil.ignore_patterns("src", ".DS_Store"))
        shutil.copy2(ROOT / "LICENSE", package / "LICENSE")
        classes = work / "classes"
        classes.mkdir()
        compile_args = ["javac", "--release", "8", "-Xlint:-options", "-d", classes]
        dex_args = ["java", "-cp", args.d8_jar, "com.android.tools.r8.D8", "--release", "--min-api", "19", "--lib", args.android_jar.resolve()]
        if args.kind == "check":
            sources = [src / "CarApi.java", ROOT / "check/src/ioniq6/cpclimate/check/Main.java"]
            jar = package / "cp-climate-check.jar"
        else:
            library = args.sdk / "lib"
            dependencies = [library / "hmgapi-runtime.jar", library / "hmgapi-runtime-stubs.jar"]
            cp = os.pathsep.join(str(p) for p in [args.android_jar.resolve(), *dependencies])
            compile_args += ["-cp", cp]
            for dep in dependencies:
                dex_args += ["--classpath", dep]
            sources = sorted(src.glob("*.java"))
            jar = package / "hooks.jar"
        run(*compile_args, *sources)
        run(*dex_args, "--output", jar, *sorted(classes.rglob("*.class")))
        with zipfile.ZipFile(jar) as dex:
            if not dex.read("classes.dex").startswith(b"dex\n035\0"):
                raise SystemExit("expected Android 4.4 compatible dex version 035")

        args.key.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if not args.key.exists():
            previous_umask = os.umask(0o077)
            try:
                run("java", "-cp", engine, "hmgapi.engine.Signing", "keygen", args.key)
            finally:
                os.umask(previous_umask)
        pinned = ROOT / "signing/author.pub"
        public = Path(str(args.key) + ".pub")
        if pinned.exists() and pinned.read_bytes() != public.read_bytes():
            raise SystemExit("author key differs from signing/author.pub; use the original key for updates")
        run("java", "-cp", engine, "hmgapi.engine.Signing", "sign", package, args.key)
        run("java", "-cp", engine, "hmgapi.engine.Signing", "verify", package)
        name = metadata["id"] + "-" + metadata["version"]
        mod_zip = output / (name + ".zip")
        archive(package, mod_zip)
        usb = work / "usb"
        updates = usb / "HMGAPI/updates"
        updates.mkdir(parents=True)
        shutil.copy2(mod_zip, updates / mod_zip.name)
        usb_zip = output / (name + "-usb.zip")
        archive(usb, usb_zip)
        shutil.copy2(public, output / "author.pub")
        if args.kind == "check":
            shutil.copy2(jar, ROOT / "check/cp-climate-check.jar")
        sums = "".join(hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name + "\n" for p in (mod_zip, usb_zip))
        (output / "SHA256SUMS").write_text(sums)
        print(sums, end="")
        print("USB package:", usb_zip)


if __name__ == "__main__":
    main()
