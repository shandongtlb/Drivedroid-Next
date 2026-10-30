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

    def run_action(self, action, script=SCRIPT):
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
            input=assignments + "\n" + stubs + SCRIPT if script is SCRIPT
            else assignments + "\n" + stubs + script,
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


if __name__ == "__main__":
    unittest.main(verbosity=2)
