"""Exercise USB state transitions against ordinary temporary files, never a phone.

The fake filesystem cannot emulate kernel or SELinux behavior. It verifies shell
control flow, argument handling, refusal of occupied LUNs, and rollback. All test
artifacts are retained beneath build/usb-script-tests; nothing is deleted.
"""

import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest


PROJECT = Path(__file__).resolve().parents[1]
SCRIPT = (PROJECT / "app/src/main/assets/usb.sh").read_text(encoding="utf-8")
BASH = shutil.which("bash")
if os.name == "nt":
    BASH = r"C:\Program Files\Git\bin\bash.exe"


class UsbScriptTest(unittest.TestCase):
    def setUp(self):
        artifacts = PROJECT / "build/usb-script-tests"
        artifacts.mkdir(parents=True, exist_ok=True)
        self.root = Path(tempfile.mkdtemp(prefix="case-", dir=artifacts))
        self.base = self.root / "usb_gadget"
        self.own = self.base / "dd_reborn"
        self.original = self.base / "g1"
        self.original.mkdir(parents=True)
        (self.original / "UDC").write_text("controller.0\n")
        self.image = self.root / "it's a disk;$(printf injected).img"
        self.image.write_bytes(b"\x00" * 512)
        self.lun = self.own / "functions/mass_storage.0/lun.0"
        self.lun.mkdir(parents=True)
        for name in ("file", "ro", "cdrom", "removable"):
            (self.lun / name).write_text("")
        self.values = {
            "BASE": self.base.as_posix(),
            "ORIGINAL": self.original.as_posix(),
            "UDC": "controller.0",
            "IMAGE": self.image.as_posix(),
            "READONLY": "1",
            "CDROM": "0",
            "OLD_RO": "0",
            "OLD_CDROM": "0",
        }

    def run_action(self, action, script=SCRIPT, extra_stubs=""):
        values = dict(self.values, ACTION=action)
        assignments = "\n".join(f"{key}={shlex.quote(value)}" for key, value in values.items())
        # ConfigFS creates attribute files itself. The test seeds them manually.
        # The ln shim avoids Windows symlink privileges; no real gadget is touched.
        stubs = """
id() {
    printf '0\\n'
}
sleep() {
    :
}
ln() {
    printf 'test-link\\n' > "$3"
}
"""
        result = subprocess.run(
            [BASH, "--noprofile", "--norc", "-s"],
            input=assignments + "\n" + stubs + extra_stubs + script,
            text=True,
            encoding="utf-8",
            capture_output=True,
            timeout=10,
            cwd=PROJECT,
        )
        (self.root / f"{action}.log").write_text(result.stdout + result.stderr, encoding="utf-8")
        return result

    def test_mount_and_eject_restore_original_controller(self):
        result = self.run_action("mount_configfs")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", (self.original / "UDC").read_text().strip())
        self.assertEqual("controller.0", (self.own / "UDC").read_text().strip())
        self.assertEqual(self.image.as_posix(), (self.lun / "file").read_text().strip())
        self.assertEqual("1", (self.lun / "ro").read_text().strip())
        result = self.run_action("unmount_configfs")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("controller.0", (self.original / "UDC").read_text().strip())
        self.assertEqual("", (self.own / "UDC").read_text().strip())
        self.assertEqual("", (self.lun / "file").read_text().strip())

    def test_binding_failure_rolls_back_controller_and_image(self):
        target = "    printf '%s\\n' \"$UDC\" > \"$own/UDC\""
        self.assertIn(target, SCRIPT)
        failing = SCRIPT.replace(target, "    false # injected bind failure", 1)
        result = self.run_action("mount_configfs", failing)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("controller.0", (self.original / "UDC").read_text().strip())
        self.assertEqual("", (self.lun / "file").read_text().strip())

    def test_preparation_failure_releases_image_without_disconnecting_usb(self):
        target = '        ln -s "$own/functions/mass_storage.0" "$own/configs/c.1/mass_storage.0"'
        self.assertIn(target, SCRIPT)
        result = self.run_action("mount_configfs", SCRIPT.replace(target, "        false", 1))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("controller.0", (self.original / "UDC").read_text().strip())
        self.assertEqual("", (self.lun / "file").read_text().strip())

    def test_unbound_but_occupied_lun_is_not_overwritten(self):
        (self.lun / "file").write_text("/other/image.img")
        result = self.run_action("mount_configfs")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("/other/image.img", (self.lun / "file").read_text())
        self.assertEqual("controller.0", (self.original / "UDC").read_text().strip())

    def test_eject_does_not_disconnect_a_different_controller(self):
        (self.own / "UDC").write_text("other-controller")
        result = self.run_action("unmount_configfs")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("other-controller", (self.own / "UDC").read_text())

    def test_missing_image_does_not_disconnect_original_usb(self):
        self.values["IMAGE"] = (self.root / "missing.img").as_posix()
        result = self.run_action("mount_configfs")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("controller.0", (self.original / "UDC").read_text().strip())

    def test_eject_refuses_someone_elses_image(self):
        foreign = self.root / "foreign.img"
        foreign.write_bytes(b"foreign")
        (self.lun / "file").write_text(foreign.as_posix())
        (self.own / "UDC").write_text("controller.0")
        result = self.run_action("unmount_configfs")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(foreign.as_posix(), (self.lun / "file").read_text())
        self.assertEqual("controller.0", (self.own / "UDC").read_text())

    def test_cdrom_sets_readonly_and_optical_flags(self):
        self.values["CDROM"] = "1"
        result = self.run_action("mount_configfs")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("1", (self.lun / "cdrom").read_text().strip())
        self.assertEqual("1", (self.lun / "ro").read_text().strip())

    def test_writable_mode_clears_previous_cdrom_and_preserves_image_bytes(self):
        before = self.image.read_bytes()
        self.values["READONLY"] = "0"
        (self.lun / "ro").write_text("1")
        (self.lun / "cdrom").write_text("1")
        result = self.run_action("mount_configfs")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("0", (self.lun / "ro").read_text().strip())
        self.assertEqual("0", (self.lun / "cdrom").read_text().strip())
        self.assertIn("actual_ro=0", result.stdout)
        self.assertIn("actual_cdrom=0", result.stdout)
        self.assertEqual(before, self.image.read_bytes())

    def test_readonly_fallback_is_reported_and_rolled_back_before_usb_switch(self):
        self.values["READONLY"] = "0"
        target = '    printf \'%s\\n\' "$IMAGE" > "$lun/file"'
        self.assertIn(target, SCRIPT)
        fallback = SCRIPT.replace(target, target + '\n    printf "1\\n" > "$lun/ro"', 1)
        result = self.run_action("mount_configfs", fallback)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("actual_ro=1", result.stdout)
        self.assertNotIn("mounted=configfs", result.stdout)
        self.assertEqual("controller.0", (self.original / "UDC").read_text().strip())
        self.assertEqual("", (self.lun / "file").read_text().strip())

    def test_readonly_fallback_after_binding_restores_original_usb(self):
        self.values["READONLY"] = "0"
        mutate_after_bind = """
sleep() {
    printf '1\\n' > "$BASE/dd_reborn/functions/mass_storage.0/lun.0/ro"
}
"""
        result = self.run_action("mount_configfs", extra_stubs=mutate_after_bind)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("mounted=configfs", result.stdout)
        self.assertEqual("controller.0", (self.original / "UDC").read_text().strip())
        self.assertEqual("", (self.own / "UDC").read_text().strip())
        self.assertEqual("", (self.lun / "file").read_text().strip())

    def test_requested_readonly_does_not_silently_accept_writable_mode(self):
        target = '    printf \'%s\\n\' "$IMAGE" > "$lun/file"'
        wrong_mode = SCRIPT.replace(target, target + '\n    printf "0\\n" > "$lun/ro"', 1)
        result = self.run_action("mount_configfs", wrong_mode)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("mounted=configfs", result.stdout)

    def prepare_legacy(self):
        legacy = self.root / "android0"
        legacy.mkdir()
        (legacy / "enable").write_text("1\n")
        (legacy / "functions").write_text("mtp,adb\n")
        self.values.update({
            "LEGACY": legacy.as_posix(),
            "LUN": self.lun.as_posix(),
            "FUNCTIONS": "mtp,adb",
            "ENABLED": "1",
        })
        return legacy

    def test_legacy_mount_and_eject_restore_functions(self):
        legacy = self.prepare_legacy()
        result = self.run_action("mount_legacy")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("mass_storage", (legacy / "functions").read_text().strip())
        result = self.run_action("unmount_legacy")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("mtp,adb", (legacy / "functions").read_text().strip())
        self.assertEqual("1", (legacy / "enable").read_text().strip())
        self.assertEqual("0", (self.lun / "ro").read_text().strip())

    def test_legacy_occupied_lun_is_preserved(self):
        legacy = self.prepare_legacy()
        (self.lun / "file").write_text("/some/other.img")
        result = self.run_action("mount_legacy")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("mtp,adb", (legacy / "functions").read_text().strip())
        self.assertEqual("1", (legacy / "enable").read_text().strip())
        self.assertEqual("/some/other.img", (self.lun / "file").read_text())

    def test_legacy_writable_mode_is_verified(self):
        self.prepare_legacy()
        self.values["READONLY"] = "0"
        result = self.run_action("mount_legacy")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("actual_ro=0", result.stdout)

    def test_legacy_readonly_fallback_restores_previous_configuration(self):
        legacy = self.prepare_legacy()
        self.values["READONLY"] = "0"
        target = '    printf \'%s\\n\' "$IMAGE" > "$LUN/file"'
        fallback = SCRIPT.replace(target, target + '\n    printf "1\\n" > "$LUN/ro"', 1)
        result = self.run_action("mount_legacy", fallback)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("mounted=legacy", result.stdout)
        self.assertEqual("mtp,adb", (legacy / "functions").read_text().strip())
        self.assertEqual("", (self.lun / "file").read_text().strip())

    def test_write_diagnostics_reports_kernel_error_without_changing_usb_or_image(self):
        before = self.image.read_bytes()
        no_real_probe = SCRIPT.replace("probe() {", "probe() {\n    printf 'root=0\\n'\n    return", 1)
        kernel_stub = """
dmesg() {
    printf 'mass_storage: error in file write: -5\\n'
}
"""
        result = self.run_action("diagnose_write", no_real_probe, kernel_stub)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("error in file write: -5", result.stdout)
        self.assertIn("image_bytes=", result.stdout)
        self.assertEqual(before, self.image.read_bytes())
        self.assertEqual("controller.0", (self.original / "UDC").read_text().strip())


if __name__ == "__main__":
    unittest.main(verbosity=2)
