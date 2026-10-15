"""Host checks for the in-app OTA trust policy (com.toemod.UpdatePolicy; plain JDK, no Android SDK).

    python3 -m unittest discover -s ci -p 'test_*.py' -v

Compiles UpdatePolicy with the app's -Xlint:all -Werror flags against the bare Java 8 API (which
also proves the policy has no Android dependency), then runs tests/ota/com/toemod/UpdatePolicyTest:
malformed/hostile update.json and build.json, rollback and re-offer rules, asset URLs and redirect
hosts, size/SHA-256 enforcement, cache corruption, the signed identity inside every APK and the
bounded plain-text "Changes in this build" list the in-game Changelog dialog shows.
PackageManager, PackageInstaller and HTTPS behaviour are covered by the on-device smoke test.
"""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = [
    ROOT / "work/mod/java/com/toemod/UpdatePolicy.java",
    ROOT / "work/mod/java/com/toemod/UpdateException.java",
    ROOT / "tests/ota/com/toemod/UpdatePolicyTest.java",
]


class OtaClientPolicyTest(unittest.TestCase):
    def test_policy_boundaries(self):
        javac, java = shutil.which("javac"), shutil.which("java")
        self.assertTrue(javac and java, "a JDK (javac and java) must be on PATH")
        with tempfile.TemporaryDirectory() as tmp:
            classes = Path(tmp, "classes")
            scratch = Path(tmp, "scratch")
            scratch.mkdir()
            build = subprocess.run(
                [javac, "--release", "8", "-encoding", "UTF-8", "-Xlint:all,-options", "-Werror",
                 "-d", str(classes), *map(str, SOURCES)],
                capture_output=True, text=True, check=False)
            self.assertEqual(build.returncode, 0, build.stdout + build.stderr)
            run = subprocess.run(
                [java, "-cp", str(classes), "com.toemod.UpdatePolicyTest", str(scratch)],
                capture_output=True, text=True, timeout=120, check=False)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)


if __name__ == "__main__":
    unittest.main()
