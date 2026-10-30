"""Independent read/write check using PyFatFS, without mounting a disk or phone.

Install pyfatfs==1.1.0 into .tools/fatcheck to run. All fixtures remain in build/.
"""

from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / ".tools/fatcheck"))

from pyfatfs.PyFatFS import PyFatFS


def main():
    artifacts = PROJECT / "build/fat32-interop"
    artifacts.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix="run-", dir=artifacts))
    java = shutil.which("java")
    javac = shutil.which("javac")
    if not java or not javac:
        raise RuntimeError("JDK 17+ is required on PATH")
    subprocess.run([
        javac, "-encoding", "UTF-8", "-d", str(run),
        str(PROJECT / "app/src/main/java/io/github/drivedroidreborn/Fat32Image.java"),
        str(PROJECT / "tests/GenerateFat32Fixture.java"),
    ], check=True)
    payload = bytes(range(256)) * 8193
    for mib in (64, 1024):
        image = run / f"fat32-{mib}MiB.img"
        subprocess.run([java, "-cp", str(run), "GenerateFat32Fixture", str(image), str(mib)], check=True)
        with image.open("rb") as disk:
            offset = struct.unpack_from("<I", disk.read(512), 454)[0] * 512
        with PyFatFS(str(image), offset=offset, read_only=False) as filesystem:
            filesystem.makedir("RWTEST")
            filesystem.writebytes("RWTEST/PAYLOAD.BIN", payload)
        with PyFatFS(str(image), offset=offset, read_only=True) as filesystem:
            assert filesystem.readbytes("RWTEST/PAYLOAD.BIN") == payload
        print(f"PASS: {mib} MiB FAT32, directory creation, write, close, reopen and byte verification")
    print(f"Artifacts retained: {run}")


if __name__ == "__main__":
    main()
