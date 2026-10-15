"""Host checks for the native weather controller and the diagnostics snapshot cache (no NDK needed).

    python3 -m unittest discover -s ci -p 'test_*.py' -v

Builds and runs two C++17 programs with the host compiler:
  tests/native/weather_policy_test.cpp  weather::steer (work/mod/src/weather_policy.h, the logic world.cpp
      runs on the Unity thread) against a model of the vanilla SkyValues weather machine: changes during
      ramps, fades and AfterRain drying, routes between two non-clear weathers, rapid latest-wins
      changes, intensity restoration, StopRain areas, switching the override off/on, new scene instances.
  tests/native/debug_snapshot_test.cpp  work/mod/src/debug.cpp: Native.debugSnapshot index mapping,
      NaN for unsampled blocks, sample ages, and no torn reads with concurrent writers.
In-game behaviour (hook installation, actual SkyValues/TruckController values) is the on-device check.
"""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "work/mod/src"
TESTS = ROOT / "tests/native"
FLAGS = ["-std=c++17", "-O2", "-Wall", "-Werror", "-pthread", f"-I{SRC}"]


class NativeHostTest(unittest.TestCase):
    def build_and_run(self, name, *sources):
        cxx = shutil.which("c++") or shutil.which("g++") or shutil.which("clang++")
        self.assertTrue(cxx, "a host C++17 compiler (c++, g++ or clang++) must be on PATH")
        with tempfile.TemporaryDirectory() as tmp:
            exe = Path(tmp, name)
            build = subprocess.run([cxx, *FLAGS, *map(str, sources), "-o", str(exe)],
                                   capture_output=True, text=True, check=False)
            self.assertEqual(build.returncode, 0, build.stdout + build.stderr)
            run = subprocess.run([str(exe)], capture_output=True, text=True, timeout=120, check=False)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

    def test_weather_policy(self):
        self.build_and_run("weather_policy_test", TESTS / "weather_policy_test.cpp")

    def test_debug_snapshot(self):
        self.build_and_run("debug_snapshot_test", TESTS / "debug_snapshot_test.cpp", SRC / "debug.cpp")


if __name__ == "__main__":
    unittest.main()
