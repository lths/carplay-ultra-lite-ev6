import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("collector", ROOT / "tools/collect_preconditioning.py")
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


class DiagnosticTest(unittest.TestCase):
    def test_log_filter(self):
        lines = ["[cp-climate] VEH preconditioning start asked of Launchpad",
            "[cp-climate] CAR seat feedback BCM 2/2", "[cp-climate] VS phone set precondition to true"]
        log = "\n".join(lines + ["[cp-climate] VS1 handshake secret-placeholder", "[another] CAR private-placeholder"])
        self.assertEqual(collector.filtered_log(log), "\n".join(lines))

    def test_network_route(self):
        for route, expected in [
            ("destination: default\ngateway: 192.168.1.1\ninterface: en0\nflags: <UP,GATEWAY,DONE>", False),
            ("destination: default\ngateway: 192.168.0.1\ninterface: en0\nflags: <UP,GATEWAY,DONE>", True),
            ("destination: 192.168.0.1\ngateway: 00:11:22:33:44:55\ninterface: en0\nflags: <UP,HOST,DONE,LLINFO>", True),
        ]:
            with patch.object(collector, "run", return_value=(0, route, "")):
                self.assertEqual(collector.direct_route("192.168.0.1"), expected)

    def test_app_inventory_and_privacy(self):
        commands = []

        def shell(adb, serial, *args, **kwargs):
            commands.append(args)
            if args[:3] == ("pm", "list", "packages"):
                return 0, "package:com.paytondev.precondition", ""
            if args[:2] == ("dumpsys", "package"):
                return 0, "versionName=2.0.0\ncom.paytondev.launchpad.PreconditioningService\nsecret-placeholder", ""
            if args[:2] == ("hmgapid", "sh") and "shared_prefs" in args[-1]:
                return 0, '<map><boolean name="requested" value="true"/><string name="other">secret-placeholder</string></map>', ""
            if args == ("hmgapid", "log"):
                return 0, "[cp-climate] VEH preconditioning start asked of Launchpad\n[cp-climate] VS1 secret-placeholder", ""
            return 0, "", ""

        with tempfile.TemporaryDirectory() as temporary, patch.object(collector, "shell", side_effect=shell):
            folder = Path(temporary)
            collector.collect("adb", "192.168.0.1:5555", folder, "fixture")
            report = (folder / "fixture.txt").read_text()
            self.assertIn("versionName=2.0.0", report)
            self.assertIn("requested=true", report)
            self.assertNotIn("secret-placeholder", report)
            self.assertNotIn("Neither supported Launchpad package", report)
            self.assertFalse(any(args[0] in ("am", "reboot", "setprop", "rm") for args in commands))

    def test_failed_package_read_does_not_claim_app_missing(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(collector, "shell", return_value=(1, "", "permission denied")):
            folder = Path(temporary)
            collector.collect("adb", "192.168.0.1:5555", folder, "fixture")
            self.assertNotIn("Neither supported Launchpad package", (folder / "fixture.txt").read_text())


if __name__ == "__main__":
    unittest.main()
