"""Build the optional video engine and core into one Android 4.4 DEX, using pinned upstream libraries."""
import os
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def build(classes, output, android_jar, sdk):
    from prepare_video_platform import prepare
    platform = prepare()
    subprocess.run(["sh", str(ROOT / "video/fetch-libs.sh")], cwd=ROOT, check=True)
    cache = ROOT / ".cache/video"
    libraries = sorted((cache / "libs").glob("*.jar"))
    cp = os.pathsep.join(map(str, [android_jar, classes, *libraries]))
    subprocess.run(["javac", "--release", "8", "-Xlint:-options", "-cp", cp, "-d", str(classes),
                    *map(str, sorted((ROOT / "video/src").rglob("*.java")))], cwd=ROOT, check=True)
    with tempfile.TemporaryDirectory(prefix="ev6-video-r8-") as temporary:
        work = Path(temporary)
        program = work / "program.jar"
        with zipfile.ZipFile(program, "w", zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(classes.rglob("*.class")):
                archive.write(path, path.relative_to(classes).as_posix())
        args = ["java", "-cp", str(cache / "r8.jar"), "com.android.tools.r8.R8", "--release", "--min-api", "19",
                "--lib", str(platform), "--output", str(work / "engine.zip")]
        for p in sorted((ROOT / "video/rules").glob("*.pro")): args += ["--pg-conf", str(p)]
        for p in [sdk / "lib/hmgapi-runtime.jar", sdk / "lib/hmgapi-runtime-stubs.jar", cache / "libs/annotation-1.3.0.jar"]:
            args += ["--classpath", str(p)]
        args += [str(program)] + [str(p) for p in libraries if not p.name.startswith("annotation-")]
        subprocess.run(args, cwd=ROOT, check=True)
        with zipfile.ZipFile(work / "engine.zip") as archive:
            dexes = [n for n in archive.namelist() if n.endswith(".dex")]
            if dexes != ["classes.dex"]: raise ValueError("the Android 4.4 hook loader requires a single DEX")
            dex = archive.read("classes.dex")
        if not dex.startswith(b"dex\n035\0"): raise ValueError("expected DEX 035")
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive: archive.writestr("classes.dex", dex)
        print(f"Video engine: one DEX 035, {len(dex)} bytes", flush=True)
