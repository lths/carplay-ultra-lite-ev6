"""Exercise the real export script with a USB path containing spaces and mixed log contents."""
import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

with tempfile.TemporaryDirectory(prefix="ev6-log-export-") as temporary:
    work = Path(temporary)
    usb = work / "USB stick/CarPlayUltraLite-Logs"
    usb.mkdir(parents=True)
    state = work / "state"
    state.mkdir()
    binary = work / "bin"
    binary.mkdir()
    mock = binary / "hmgapid"
    mock.write_text('''#!/bin/sh
case "$1" in
  usb) [ "$2" = cp-climate ] || exit 1; printf '%s\\n' "$EV6_TEST_USB" ;;
  log) cat "$EV6_TEST_INPUT" ;;
  *) exit 1 ;;
esac
''')
    mock.chmod(0o755)
    inputs = work / "input.log"
    inputs.write_text("\n".join([
        "[cp-climate] VS1 handshake secret-placeholder",
        "[other-mod] CAR unrelated-placeholder",
        "[cp-climate] CAR seat feedback BCM 2/2, USM -1/-1, options 6/6",
        "[cp-climate] VS phone set recirculationOn to false",
        "[cp-climate] STOCK com.android.vehiclesettings setFrontClimateControl[11, 0]",
        "[cp-climate] VIDEO dipo.carplay.screen_timeout []",
        "[cp-climate] VEH soc 61.0 range 190 km",
        "[cp-climate] MEDIA Play down: Success",
    ]) + "\n")
    env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ["PATH"], HMGAPI_DATA=str(state),
        EV6_TEST_USB=str(usb), EV6_TEST_INPUT=str(inputs))

    def export():
        subprocess.run(["sh", str(ROOT / "mod/svc/export-log.sh")], env=env, check=True, timeout=10)

    export()
    output = (usb / "ev6-carplay.log").read_text()
    assert "BCM 2/2" in output and "recirculationOn to false" in output and "setFrontClimateControl[11, 0]" in output
    assert "VIDEO" in output and "VEH soc" in output
    assert "MEDIA Play down: Success" in output
    assert "secret-placeholder" not in output and "unrelated-placeholder" not in output
    inputs.write_text("unrelated log lines only\n")
    export()
    assert (usb / "ev6-carplay.log").read_text() == output, "retain useful diagnostics when the current snapshot has none"
    print("ok   USB export handles spaces, filters handshakes/unrelated logs, and retains the last useful snapshot")
