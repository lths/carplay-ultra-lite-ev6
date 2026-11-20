#!/usr/bin/env python3
"""Host checks without manufacturer files. Optional --android-jar and --sdk compile every Java source."""
import argparse
import json
import os
import plistlib
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "mod/src/ioniq6/cpclimate"


def run(label, args):
    result = subprocess.run([str(x) for x in args], cwd=ROOT, text=True, capture_output=True, timeout=60)
    lines = (result.stdout + result.stderr).splitlines()
    checks = sum(line.startswith("ok ") for line in lines)
    print(f"{label}: {'PASS' if result.returncode == 0 else 'FAIL'} ({checks} checks)", flush=True)
    if result.returncode:
        print(result.stdout + result.stderr)
        raise SystemExit(result.returncode)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-jar", type=Path)
    parser.add_argument("--sdk", type=Path)
    args = parser.parse_args()
    run("Filtered USB log export", ["python3", ROOT / "tests/log_export_test.py"])
    run("Offline preconditioning diagnostic", ["python3", ROOT / "tests/preconditioning_diagnostic_test.py"])
    with tempfile.TemporaryDirectory(prefix="ev6-tests-") as temporary:
        work = Path(temporary)
        native = work / "native-test"
        run("Compile native hooks", ["clang", "-DCPC_HOST_TEST", f'-DDIR="{work}/"',
                '-DVSPI="data/vspi.bplist"', "-I", ROOT / "native", ROOT / "tests/host_test.c", ROOT / "native/hooks.c", "-o", native])
        run("Native hooks", [native])
        classes = work / "classes"
        classes.mkdir()
        names = ["Bplist", "ChaChaPoly", "ClimateMap", "Opack", "OpackCodec", "OpackSummary", "VehiclePage", "VehicleDescription", "VehicleState", "VsChannel", "VsData", "CarApi", "CarPlayMedia", "VideoContext", "ParkedState", "VideoStreamSink"]
        sources = [SRC / (name + ".java") for name in names]
        tests = ["ClimateMapTest", "SessionPairTest", "CompatibilityTest", "CarPlayMediaTest", "DiagnosticsTest", "VideoContextTest", "VsChannelTest", "ForkImprovementsTest", "ParkedStateTest", "VideoStreamSinkTest"]
        sources += [ROOT / "tests" / (name + ".java") for name in tests]
        sources += [ROOT / "check/src/ioniq6/cpclimate/check/Main.java", ROOT / "tests/CheckReportTest.java"]
        run("Compile host Java and checker", ["javac", "--release", "8", "-d", classes, *sources])
        run("Climate map", ["java", "-cp", classes, "ioniq6.cpclimate.ClimateMapTest"])
        run("Passive connection and manual media controls", ["java", "-cp", classes, "ioniq6.cpclimate.CarPlayMediaTest"])
        run("OPACK diagnostics and route privacy", ["java", "-cp", classes, "ioniq6.cpclimate.DiagnosticsTest"])
        run("Video event context", ["java", "-cp", classes, "ioniq6.cpclimate.VideoContextTest"])
        run("Front comfort and full description", ["java", "-cp", classes, "ioniq6.cpclimate.ForkImprovementsTest"])
        run("Park feedback guard", ["java", "-cp", classes, "ioniq6.cpclimate.ParkedStateTest"])
        run("Encrypted video side streams", ["java", "-cp", classes, "ioniq6.cpclimate.VideoStreamSinkTest", work])
        run("Encrypted vehicle-state transport", ["java", "-cp", classes, "ioniq6.cpclimate.VsChannelTest", work, sys.executable, ROOT / "tests/phone_sim.py"])
        run("Session pairing", ["java", "-cp", classes, "ioniq6.cpclimate.SessionPairTest", work])
        run("Compatibility verdicts", ["java", "-cp", classes, "ioniq6.cpclimate.check.CheckReportTest"])

        # Independent car framework fixtures. The method's transaction number deliberately differs.
        for variant, expected in [("matching", 0), ("changed", 1), ("missing", 14)]:
            fixture = work / variant
            package = fixture / "com/hkmc/datc"
            package.mkdir(parents=True)
            iface = package / "IDATCService.java"
            iface.write_text("package com.hkmc.datc; public interface IDATCService { float getVRClimateStatus(int flag); "
                    "class Stub { public static final int TRANSACTION_getVRClimateStatus = 917; } }")
            files = [iface]
            if variant != "missing":
                flags = {"TEMP": 2, "BLOWER_LEVEL": 5, "BLOWER_MODE": 6, "AUTO": 8, "AC": 9,
                        "FRONT_DEFOG": 10, "INTAKE": 11, "OP_STATE": 12, "SYNC": 22, "TEMP_PS_C": 29}
                if variant == "changed":
                    flags["FRONT_DEFOG"] = 88
                decl = lambda values: " ".join(f"public static final int {k}={v};" for k, v in values.items())
                constants = package / "DATCFlag.java"
                constants.write_text("package com.hkmc.datc; public class DATCFlag { " + decl(flags)
                        + " public static class DATCControl { " + decl({"DRIVER_ONLY": 24, "TEMP_DR_C": 28, "TEMP_PS_C": 29, "TEMP_PS_F": 31}) + " } }")
                files.append(constants)
            run("Compile " + variant + " firmware fixture", ["javac", "--release", "8", "-d", fixture, *files])
            run("Firmware " + variant, ["java", "-cp", str(fixture) + os.pathsep + str(classes), "ioniq6.cpclimate.CompatibilityTest", expected])

        if args.android_jar and args.sdk:
            full = work / "full"
            full.mkdir()
            sdk = args.sdk.resolve() / "lib"
            cp = os.pathsep.join(str(p.resolve()) for p in (args.android_jar, sdk / "hmgapi-runtime.jar", sdk / "hmgapi-runtime-stubs.jar"))
            extra = [ROOT / "tests/VehiclePageTest.java", ROOT / "tests/AudioNoticeTest.java", ROOT / "tests/VideoMessagesTest.java", ROOT / "tools/MkVspi.java"]
            run("Compile all mod sources", ["javac", "--release", "8", "-cp", cp, "-d", full, *SRC.glob("*.java"), *extra])
            runtime = str(full) + os.pathsep + cp
            run("Vehicle page", ["java", "-cp", runtime, "ioniq6.cpclimate.VehiclePageTest"])
            run("Audio and notices", ["java", "-cp", runtime, "ioniq6.cpclimate.AudioNoticeTest"])
            run("Video command protocol and privacy", ["java", "-cp", runtime, "ioniq6.cpclimate.VideoMessagesTest"])
            generated = subprocess.run(["java", "-cp", runtime, "ioniq6.cpclimate.MkVspi", "EV6"],
                    cwd=ROOT, capture_output=True, check=True, timeout=60)
            blob = (ROOT / "data/vspi.bplist").read_bytes()
            description = plistlib.loads(blob)
            if description != plistlib.loads(generated.stdout):
                raise SystemExit("native description differs from runtime; run tools/build_description.py and rebuild the native segment")
            recipe = (ROOT / "mod/recipes/carplay-climate-m1.recipe").read_text()
            segments = [bytes.fromhex(line.split()[2]) for line in recipe.splitlines() if line.startswith("segment ")]
            if len(segments) != 1 or segments[0].count(blob) != 1:
                raise SystemExit("native recipe does not contain the current Vehicle page exactly once; rebuild the native segment")
            print("Complete native/runtime description and embedded recipe: PASS", flush=True)
        else:
            print("Full Android compilation skipped (provide --android-jar and --sdk)")


if __name__ == "__main__":
    main()
