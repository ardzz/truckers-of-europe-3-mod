"""Host checks for the in-game menu's Android-free rules (com.toemod.Diagnostics, MenuModel; plain JDK).

    python3 -m unittest discover -s ci -p 'test_*.py' -v

Compiles both helpers with the app's -Xlint:all -Werror flags against the bare Java 8 API (which
also proves they have no Android dependency), then runs tests/menu/com/toemod/MenuModelTest:
missing or stale diagnostics never read as live or zero, the observed forced-weather state
(queued, transitioning through Clear/After rain, active, suppressed, off), the screens that may
read the native snapshot, and exact value entry that refuses instead of clamping. Layout, touch
and the live game data are covered by the on-device run.
"""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = [
    ROOT / "work/mod/java/com/toemod/Diagnostics.java",
    ROOT / "work/mod/java/com/toemod/MenuModel.java",
    ROOT / "tests/menu/com/toemod/MenuModelTest.java",
]


class MenuModelTest(unittest.TestCase):
    def test_menu_rules(self):
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
                [java, "-cp", str(classes), "com.toemod.MenuModelTest"],
                capture_output=True, text=True, timeout=120, check=False)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)


if __name__ == "__main__":
    unittest.main()
