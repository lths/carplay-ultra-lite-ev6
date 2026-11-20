"""Verified Android library stubs for R8's guarded ExoPlayer platform references (device min API stays 19)."""
import hashlib
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
URL = "https://dl.google.com/android/repository/platform-34-ext7_r03.zip"
ARCHIVE_SHA = "16fdb74c55e59ae3ef52def135aec713508467bd56d7dabcd8c9be31fa8b20f3"
JAR_SHA = "6cea1df3efb77103ac3e2beb9bf4718964b0e0869ab16d39d29d5cbae1c147ad"


def prepare():
    cache = ROOT / ".cache"
    cache.mkdir(exist_ok=True)
    target = cache / "android-34.jar"
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == JAR_SHA: return target
    archive = cache / "android-34.zip"
    if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != ARCHIVE_SHA:
        subprocess.run(["curl", "-fsSL", URL, "-o", str(archive)], check=True)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != ARCHIVE_SHA: raise ValueError("Android stub archive checksum mismatch")
    with zipfile.ZipFile(archive) as z:
        data = z.read(next(n for n in z.namelist() if n.endswith("/android.jar")))
    if hashlib.sha256(data).hexdigest() != JAR_SHA: raise ValueError("Android stub jar checksum mismatch")
    target.write_bytes(data)
    return target
