"""Host checks for the cloud-save rules (com.toemod.SnapshotFormat and BackupPolicy; plain JDK, no Android SDK).

    python3 -m unittest discover -s ci -p 'test_*.py' -v

Compiles the snapshot format and backup policy with the app's -Xlint:all -Werror flags against the
bare Java 8 API (which also proves they have no Android dependency), then runs
tests/cloud/com/toemod/CloudPolicyTest: exact toe3-prefs-snapshot v1 bytes, lossless round trips of
every SharedPreferences type, refusal of foreign, malformed, newer-format and newer-build snapshots,
gzip size limits, backup names, automatic-backup rotation and its 10-minute interval.
SharedPreferences, MediaStore, the document picker and the restart are covered by the on-device smoke test.
"""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = [
    ROOT / "work/mod/java/com/toemod/SnapshotFormat.java",
    ROOT / "work/mod/java/com/toemod/SnapshotException.java",
    ROOT / "work/mod/java/com/toemod/BackupPolicy.java",
    ROOT / "tests/cloud/com/toemod/MiniJson.java",
    ROOT / "tests/cloud/com/toemod/CloudPolicyTest.java",
]


class CloudClientPolicyTest(unittest.TestCase):
    def test_policy_boundaries(self):
        javac, java = shutil.which("javac"), shutil.which("java")
        self.assertTrue(javac and java, "a JDK (javac and java) must be on PATH")
        with tempfile.TemporaryDirectory() as tmp:
            classes = Path(tmp, "classes")
            build = subprocess.run(
                [javac, "--release", "8", "-encoding", "UTF-8", "-Xlint:all,-options", "-Werror",
                 "-d", str(classes), *map(str, SOURCES)],
                capture_output=True, text=True, check=False)
            self.assertEqual(build.returncode, 0, build.stdout + build.stderr)
            run = subprocess.run(
                [java, "-cp", str(classes), "com.toemod.CloudPolicyTest"],
                capture_output=True, text=True, timeout=120, check=False)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)


if __name__ == "__main__":
    unittest.main()
