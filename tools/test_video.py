#!/usr/bin/env python3
"""Run imported video synchronization and local TLS/HTTP tests without Android or external test servers."""
import os
import subprocess
import tempfile
from pathlib import Path

from test import ROOT, run


def main():
    subprocess.run(["sh", str(ROOT / "video/fetch-libs.sh")], cwd=ROOT, check=True)
    libraries = sorted((ROOT / ".cache/video/libs").glob("bc*.jar"))
    with tempfile.TemporaryDirectory(prefix="ev6-video-tests-") as temporary:
        work = Path(temporary)
        # Networking helpers refer only to these logger methods; never initialize an Android player on the host.
        stub = work / "Video.java"
        stub.write_text('package ioniq6.cpclimate.video; final class Video { static void log(String s) { } static void trace(String s) { } }')
        src = ROOT / "video/src/ioniq6/cpclimate/video"
        sources = [src / (name + ".java") for name in ("Sync", "ExtraRoots", "PhoneProxy", "TunnelHttp", "ParallelStream", "VideoLog")]
        sources += [stub, ROOT / "video/tests/PhoneProxyTest.java", ROOT / "video/tests/ioniq6/cpclimate/video/SyncTest.java", ROOT / "video/tests/ioniq6/cpclimate/video/VideoHostTest.java"]
        cp = os.pathsep.join(map(str, libraries))
        run("Compile video host fixtures", ["javac", "--release", "8", "-cp", cp, "-d", work, *sources])
        runtime = os.pathsep.join([str(work), cp])
        run("Video clock synchronization", ["java", "-cp", runtime, "ioniq6.cpclimate.video.SyncTest"])
        run("Local TLS 1.3 phone proxy", ["java", "-cp", runtime, "PhoneProxyTest"])
        run("Video privacy, trust roots and local HTTP ranges", ["java", "-cp", runtime, "ioniq6.cpclimate.video.VideoHostTest"])


if __name__ == "__main__":
    main()
