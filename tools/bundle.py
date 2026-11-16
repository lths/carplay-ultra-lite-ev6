#!/usr/bin/env python3
"""Assemble the first EV6 CarPlay test: verified private mod plus original signed theme dependency."""
import argparse
import hashlib
import io
import json
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM_SHA = "a4632aa4a55e57dd6cd8f205fccc9962240bc0ec6569301ead24709e7fdb03d3"
ROUTING_SHA = "3d21f1e039df642e56dd3d7320aa90a8632d89bc1eb67a987092f37534815b85"
SOURCE_SHA = "a4ecdb3086fe9cd6dca2a10a2f1146c3ca3cbf3461b7bbd70e83841925dfda82"
PRIVATE_AUTHOR = "51951394-274a-8d4b-8396-c831913a9a44"
ROUTING_AUTHOR = "4bec7579-9d68-8e3c-b667-2804d05a20b9"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def inspect_package(data, folder, engine, expected_id, expected_version, author):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in archive.namelist():
            path = PurePosixPath(name)
            if path.is_absolute() or ".." in path.parts or "\\" in name:
                raise ValueError("unsafe archive path")
        archive.extractall(folder)
    subprocess.run(["java", "-cp", str(engine), "hmgapi.engine.Signing", "verify", str(folder), author], check=True)
    metadata = json.loads((folder / "mod.json").read_text())
    if metadata["id"] != expected_id or metadata["version"] != expected_version:
        raise ValueError("unexpected package identity or version")
    if not any("CVEV" in s["model"] and s["region"] == "USA" and s["firmware"] == "260507"
            for s in metadata["system"]):
        raise ValueError("package does not list the EV6 target")
    if "wireless-carplay-aa" not in metadata.get("requires", []):
        raise ValueError("wireless CarPlay dependency missing")
    settings = {s["key"]: s["default"] for s in json.loads((folder / "settings.json").read_text())["settings"]}
    return metadata, settings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream-bundle", type=Path, required=True)
    parser.add_argument("--mod-package", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    args = parser.parse_args()
    upstream = args.upstream_bundle.read_bytes()
    if digest(upstream) != UPSTREAM_SHA:
        raise ValueError("original upstream bundle checksum mismatch")
    engine = args.sdk.resolve() / "lib/hmgapi-engine.jar"
    mod = args.mod_package.read_bytes()
    with zipfile.ZipFile(io.BytesIO(upstream)) as archive:
        routing_path = next(n for n in archive.namelist() if "/usb-2-install-ev6-ioniq5/" in n
                and n.endswith("/egmp-carplay-ev-routing-0.4.3-ioniq6.15.zip"))
        source_path = next(n for n in archive.namelist() if "/source/egmp-" in n and n.endswith(".zip"))
        routing, source = archive.read(routing_path), archive.read(source_path)
    if digest(routing) != ROUTING_SHA or digest(source) != SOURCE_SHA:
        raise ValueError("routing package or source checksum mismatch")
    output = ROOT / "out/install"
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="ev6-install-") as temporary:
        work = Path(temporary)
        cm, cs = inspect_package(mod, work / "climate", engine, "cp-climate", "1.3.3-ev6.1", PRIVATE_AUTHOR)
        rm, rs = inspect_package(routing, work / "routing", engine, "egmp-carplay-ev-routing", "0.4.3-ioniq6.15", ROUTING_AUTHOR)
        if not cs["carplay_ultra"] or rs["carplay_theme"] != "44C9FA31EC:985:19.0" or not rs["carplay_start_session"]:
            raise ValueError("theme and Ultra defaults do not match")
        if rs["carplay_engine"] != "electric":
            raise ValueError("EV engine identity must be retained")
        if any(cs[k] for k in ("carplay_volume_slider", "adb_keepalive", "drive_log")):
            raise ValueError("testing aids must be off by default")
        if any(rs[k] for k in ("carplay_iap_bridge", "carplay_iap_ft_v2", "carplay_link_ft_v2")) or rs["carplay_theme_alt"] != "off":
            raise ValueError("routing transport experiments must be off")
        if set(cm["hooks"]["native"]) & set(rm["hooks"]["native"]):
            raise ValueError("routing and climate packages unexpectedly patch the same native library")
        contents = work / "contents"
        updates = contents / "HMGAPI/updates"
        updates.mkdir(parents=True)
        (updates / "egmp-carplay-ev-routing-0.4.3-ioniq6.15.zip").write_bytes(routing)
        (updates / "cp-climate-1.3.3-ev6.1.zip").write_bytes(mod)
        sources = contents / "source"
        sources.mkdir()
        (sources / PurePosixPath(source_path).name).write_bytes(source)
        shutil.copy2(ROOT / "docs/INSTALL-EV6.md", contents / "README.md")
        shutil.copy2(ROOT / "docs/EV6-VALIDATION.md", contents / "EV6-VALIDATION.md")
        shutil.copy2(ROOT / "LICENSE", contents / "LICENSE")
        shutil.copy2(ROOT / "NOTICE", contents / "NOTICE")
        shutil.copy2(work / "routing/NOTICE.md", contents / "ROUTING-NOTICE.md")
        report_folder = contents / "CarPlayUltraLite-Check"
        report_folder.mkdir()
        (report_folder / "README.txt").write_text("Optional checker export folder. The installed checker may copy cp-climate-check.md and .json here on its next run.\n")
        build = {
            "mod_source_commit": "5b2ec88414c636b4832516443a09e72feab06369",
            "bundle_source_commit": subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip(),
            "target": "2024 Kia EV6 CVEV USA 260507",
            "validation": {"source": "owner-provided service-log photo, 2026-11-15", "checker": "1.1.0-ev6.1",
                "feature_verdicts": "8 YES", "detailed_reports": "not retrieved", "functional_control_test": "pending"},
            "packages": [
                {"id": cm["id"], "version": cm["version"], "author_uuid": PRIVATE_AUTHOR, "sha256": digest(mod)},
                {"id": rm["id"], "version": rm["version"], "author_uuid": ROUTING_AUTHOR, "sha256": digest(routing)}],
            "upstream_bundle_sha256": UPSTREAM_SHA,
            "routing_source_sha256": SOURCE_SHA,
            "wireless_replacement_included": False
        }
        (contents / "BUILD.json").write_text(json.dumps(build, indent=2) + "\n")
        files = sorted(p for p in contents.rglob("*") if p.is_file())
        (contents / "SHA256SUMS").write_text("".join(digest(p.read_bytes()) + "  " + p.relative_to(contents).as_posix() + "\n" for p in files))
        destination = output / "carplay-ultra-lite-ev6-1.3.3-ev6.1-install-usb.zip"
        with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(contents.rglob("*")):
                if path.is_file():
                    archive.write(path, path.relative_to(contents).as_posix())
        with zipfile.ZipFile(destination) as archive:
            packages = [p for p in archive.namelist() if p.startswith("HMGAPI/updates/")]
            if len(packages) != 2 or any("wireless" in p for p in packages):
                raise ValueError("bundle must contain exactly the theme dependency and private EV6 mod")
            if archive.read("HMGAPI/updates/cp-climate-1.3.3-ev6.1.zip") != mod:
                raise ValueError("bundle changed the signed private mod")
            if archive.read("HMGAPI/updates/egmp-carplay-ev-routing-0.4.3-ioniq6.15.zip") != routing:
                raise ValueError("bundle changed the signed routing dependency")
        (output / "SHA256SUMS").write_text(digest(destination.read_bytes()) + "  " + destination.name + "\n")
        shutil.copy2(contents / "BUILD.json", output / "BUILD.json")
        print("Verified USB bundle:", destination)
        print("SHA256:", digest(destination.read_bytes()))


if __name__ == "__main__":
    main()
