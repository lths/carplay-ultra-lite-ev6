#!/usr/bin/env python3
"""Collect targeted EV6/Launchpad diagnostics locally, including while the Mac is offline.

No installation, settings changes, service starts, vehicle commands, or log clearing.
Reports stay in ignored out/diagnostics. Network connection is attempted only when the
Mac has a direct route to the specified peer. Verify the car before any app/log reads.
"""
import argparse
import datetime
import ipaddress
import json
import re
import shlex
import shutil
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGES = ("com.paytondev.launchpad", "com.paytondev.precondition")
LOG_LINE = re.compile(r"\[cp-climate\] (?:VEH |CAR |VS phone set precondition|VS phone may not write )")
PACKAGE_LINE = re.compile(r"versionName=|versionCode=|userId=|PreconditioningService|MainActivity|User 0:")
TARGET = re.compile(r"CVEV\.USA\..*\.260507\Z")


def run(args, timeout=15):
    try:
        p = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
        return p.returncode, p.stdout.strip(), p.stderr.strip()
    except subprocess.TimeoutExpired:
        return 124, "", "command timed out"


def shell(adb, serial, *args, timeout=15):
    return run([adb, "-s", serial, "shell", shlex.join(args)], timeout)


def direct_route(host):
    if not Path("/sbin/route").exists():
        return True
    code, route, _ = run(["/sbin/route", "-n", "get", host], 5)
    if code:
        return False
    fields = dict(re.findall(r"^\s*(gateway|destination|interface):\s*(\S+)", route, re.M))
    # A home-router default route must not be mistaken for the car's hotspot.
    if fields.get("gateway") == host:
        return True
    flags = re.search(r"^\s*flags:\s*(.*)$", route, re.M)
    return bool(fields.get("interface") and flags and "GATEWAY" not in flags.group(1))


def filtered_log(text):
    return "\n".join(line for line in text.splitlines() if LOG_LINE.search(line))


def collect(adb, serial, folder, phase):
    report = []

    def record(label, args, transform=None, root=False):
        remote = ("hmgapid", "sh", shlex.join(args)) if root else args
        code, stdout, stderr = shell(adb, serial, *remote)
        value = transform(stdout) if transform else stdout
        report.extend(["\n" + label, "exit=" + str(code), value or "(no matching output)"])
        if stderr:
            report.append(stderr[:1000])
        return code, stdout

    record("ADB identity", ("id",))
    record("Head-unit software", ("getprop", "ro.build.product"))
    record("HMG-API version", ("hmgapid", "version"))
    package_code, packages = record("Installed Launchpad/Precondition packages", ("pm", "list", "packages", "com.paytondev"))
    present = set(re.findall(r"^package:(\S+)$", packages, re.M))
    if package_code == 0 and not present.intersection(PACKAGES):
        report.append("FINDING: Neither supported Launchpad package is listed. The CarPlay preconditioning switch requires this separate app.")
    for package in PACKAGES:
        if package not in present:
            continue
        record(package + " version/components", ("dumpsys", "package", package),
            lambda s: "\n".join(line for line in s.splitlines() if PACKAGE_LINE.search(line)))
    record("Running preconditioning service", ("dumpsys", "activity", "services", "com.paytondev"),
        lambda s: "\n".join(line for line in s.splitlines()
            if re.search(r"ServiceRecord|PreconditioningService|processName=|startRequested=|isForeground=", line)))
    record("ADB mod settings", ("hmgapid", "setting-get", "adb"))
    record("CarPlay helper services", ("hmgapid", "services", "cp-climate"))
    record("CarPlay helper log", ("hmgapid", "service-log", "cp-climate", "helper"),
        lambda s: "\n".join(line for line in s.splitlines() if re.search(r"launchpad|precond", line, re.I)))
    record("CarPlay preconditioning helper state", ("cat", "/data/data/com.daudio.app.dipo/files/cpc-precond-state"), root=True)
    for package in PACKAGES:
        if package not in present:
            continue
        record(package + " request ownership", ("cat", "/data/data/" + package + "/shared_prefs/launchpad_preconditioning.xml"),
            lambda s: "requested=" + (re.search(r'<boolean\s+name="requested"\s+value="(true|false)"', s).group(1)
                if re.search(r'<boolean\s+name="requested"\s+value="(true|false)"', s) else "unavailable"), root=True)
    record("Filtered CarPlay vehicle/climate log", ("hmgapid", "log"), filtered_log)
    record("App/service errors mentioning Launchpad", ("logcat", "-d", "-v", "time", "-s", "ActivityManager:I", "AndroidRuntime:E"),
        lambda s: "\n".join(line for line in s.splitlines() if re.search(r"com\.paytondev\.|PreconditioningService", line)))
    destination = folder / (phase + ".txt")
    destination.write_text("EV6 preconditioning diagnostic; read-only; " + phase + "\n" + "\n".join(report) + "\n")
    print("Saved", destination, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--connect", default="192.168.0.1:5555", help="head-unit ADB endpoint")
    parser.add_argument("--wait", type=int, default=600, help="seconds to wait for the car hotspot")
    parser.add_argument("--observe", type=int, default=30, help="seconds between the two snapshots")
    args = parser.parse_args()
    host, port = args.connect.rsplit(":", 1)
    address = ipaddress.ip_address(host)
    if address.version != 4 or not address.is_private or not (1 <= int(port) <= 65535) or args.wait < 0 or args.observe < 0:
        parser.error("use the car's private IPv4 address, a valid port, and nonnegative wait times")
    adb = shutil.which("adb")
    if not adb:
        parser.error("adb is not installed or is not on PATH")
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    folder = ROOT / "out/diagnostics" / ("preconditioning-" + now)
    folder.mkdir(parents=True, exist_ok=True)
    status = {"state": "waiting", "endpoint": args.connect, "read_only": True}

    def save_status(state, detail):
        status.update(state=state, detail=detail)
        (folder / "status.json").write_text(json.dumps(status, indent=2) + "\n")
        print(detail, flush=True)

    save_status("waiting", "Collector started locally. Join the car hotspot with HMG-API Wi-Fi ADB enabled. Chat internet is not required.")
    deadline = time.monotonic() + args.wait
    verified = False
    while time.monotonic() <= deadline:
        if direct_route(host):
            run([adb, "connect", args.connect], 8)
            code, product, _ = shell(adb, args.connect, "getprop", "ro.build.product", timeout=5)
            if code == 0 and product:
                if not TARGET.fullmatch(product):
                    save_status("wrong_target", "Connected peer is outside EV6 USA 260507; no app or log inspection performed.")
                    return 2
                verified = True
                save_status("connected", "Verified " + product + ". Collecting Launchpad and CarPlay diagnostics.")
                break
        time.sleep(2)
    if not verified:
        save_status("not_connected", "No verified EV6 connection before timeout. Ensure Wi-Fi ADB is ON and its applied TCP port is 5555.")
        return 1
    collect(adb, args.connect, folder, "initial")
    print("You may now tap preconditioning once in CarPlay. Collecting another snapshot in " + str(args.observe) + " seconds.", flush=True)
    time.sleep(args.observe)
    code, product, _ = shell(adb, args.connect, "getprop", "ro.build.product", timeout=5)
    if code != 0 or not TARGET.fullmatch(product):
        save_status("partial", "Initial report saved; connection ended before the second snapshot. Rejoin internet and return to chat.")
        return 0
    collect(adb, args.connect, folder, "after-tap")
    save_status("complete", "Diagnostics saved. Rejoin your internet Wi-Fi and return to chat; no vehicle command was sent by this collector.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
