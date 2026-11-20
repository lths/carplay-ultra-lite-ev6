#!/usr/bin/env python3
"""Stage verified official HMG rc.7/wireless rc.8 updates around the legacy EV6 routing serializer."""
import argparse
import hashlib
import json
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
ASSETS = {
    "hmgapi-1.1.0-rc.7-update.zip": "60f7f2a601f725d1f1d41a12708094e6130f919fb7924669e178e2ac6d8e936c",
    "wireless-carplay-aa-2.2.0-rc.8.zip": "4837efbfc1e71a7f30c65c7d914dc7d00aa788cc7ab4ea17b1a1860e6e96d4ee",
    "adb-1.0.1-rc.1.zip": "37f18d4fd5161b91bd740345613ea5803cc1bc766202513cd736739f27bd9538",
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def unpack(path, destination):
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            p = PurePosixPath(name)
            if p.is_absolute() or ".." in p.parts or "\\" in name:
                raise ValueError("unsafe archive path")
        z.extractall(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--mod-package", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    args = parser.parse_args()
    for name, sha in ASSETS.items():
        if digest((args.assets / name).read_bytes()) != sha:
            raise ValueError("official release digest mismatch: " + name)
    engine = args.sdk.resolve() / "lib/hmgapi-engine.jar"
    output = ROOT / "out/upgrade"
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="ev6-hmg-upgrade-") as tmp:
        work = Path(tmp)
        for name in ("wireless-carplay-aa-2.2.0-rc.8.zip", "adb-1.0.1-rc.1.zip"):
            folder = work / name.removesuffix(".zip")
            unpack(args.assets / name, folder)
            subprocess.run(["java", "-cp", str(engine), "hmgapi.engine.Signing", "verify", str(folder)], check=True)
        wireless = json.loads((work / "wireless-carplay-aa-2.2.0-rc.8/mod.json").read_text())
        if not any("CVEV" in s["model"] and s["region"] == "USA" and s["firmware"] == "260507" for s in wireless["system"]):
            raise ValueError("wireless package does not support the owner's target")
        unpack(args.mod_package, work / "climate")
        subprocess.run(["java", "-cp", str(engine), "hmgapi.engine.Signing", "verify", str(work / "climate"),
                        "51951394-274a-8d4b-8396-c831913a9a44"], check=True)
        own = json.loads((work / "climate/mod.json").read_text())
        if own["version"] != json.loads((ROOT / "mod/mod.json").read_text())["version"]:
            raise ValueError("mod package version differs from source")
        # Both serializers hook this address. Shared identity must be OFF until routing is migrated.
        recipe = (work / "wireless-carplay-aa-2.2.0-rc.8/recipes/carplay-identification-fc7.recipe").read_text()
        if "branch 0x1aa04 " not in recipe:
            raise ValueError("wireless identity recipe changed; re-review legacy compatibility")
        contents = work / "contents"
        stages = [("01-update-hmgapi", args.assets / "hmgapi-1.1.0-rc.7-update.zip"),
                  ("02-update-wireless", args.assets / "wireless-carplay-aa-2.2.0-rc.8.zip"),
                  ("03-update-carplay", args.mod_package), ("optional-adb-update", args.assets / "adb-1.0.1-rc.1.zip")]
        for stage, source in stages:
            folder = contents / stage / "HMGAPI/updates"
            folder.mkdir(parents=True)
            shutil.copy2(source, folder / source.name)
        logs = contents / "03-update-carplay/CarPlayUltraLite-Logs"
        logs.mkdir()
        (logs / "README.txt").write_text("Filtered EV6 climate/charging/video diagnostics export here while this folder is on the USB root.\n")
        for source, target in (("docs/HMG-RC7-UPGRADE.md", "README.md"), ("docs/INSTALL-EV6.md", "INSTALL-EV6.md"),
                               ("docs/EV6-CONTROLS-CHECKLIST.md", "EV6-CONTROLS-CHECKLIST.md"), ("LICENSE", "LICENSE"), ("NOTICE", "NOTICE")):
            shutil.copy2(ROOT / source, contents / target)
        (contents / "SOURCES.txt").write_text("Official unmodified update packages:\nhttps://github.com/Open-HMG/HMG-API/tree/v1.1.0-rc.7\n"
                "https://github.com/Open-HMG/hmgapi-wireless-cp-aa/tree/v2.2.0-rc.8\nhttps://github.com/Open-HMG/hmgapi-adb/tree/v1.0.1-rc.1\n"
                "Private EV6 source:\nhttps://github.com/lths/carplay-ultra-lite-ev6/tree/v" + own["version"] + "\n")
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        plan = {"source_commit": commit, "mod_version": own["version"], "target": "CVEV USA 260507",
                "official_packages": ASSETS, "mod_sha256": digest(args.mod_package.read_bytes()),
                "mandatory_legacy_compatibility": {"wireless-carplay-aa.shared_identity": False},
                "shared_serializer_hook_address": "0x1aa04", "staged_install": True, "on_car_validation": "pending"}
        (contents / "UPGRADE.json").write_text(json.dumps(plan, indent=2) + "\n")
        files = sorted(p for p in contents.rglob("*") if p.is_file())
        (contents / "SHA256SUMS").write_text("".join(digest(p.read_bytes()) + "  " + p.relative_to(contents).as_posix() + "\n" for p in files))
        destination = output / ("carplay-ultra-lite-ev6-" + own["version"] + "-hmg-rc7-upgrade-usb.zip")
        with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as z:
            for p in sorted(contents.rglob("*")):
                if p.is_file():
                    z.write(p, p.relative_to(contents).as_posix())
        with zipfile.ZipFile(destination) as z:
            for stage, source in stages:
                if z.read(stage + "/HMGAPI/updates/" + source.name) != source.read_bytes():
                    raise ValueError("stage changed a signed package")
        print("Verified staged HMG update:", destination)
        print("SHA256:", digest(destination.read_bytes()))


if __name__ == "__main__":
    main()
