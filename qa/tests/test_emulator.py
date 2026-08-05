from __future__ import annotations

import os
import tempfile
import textwrap
import unittest
from pathlib import Path

from senpqa.common import QaError
from senpqa.emulator import EmulatorConfig, run_emulator_harness


FAKE_ADB = r'''#!/bin/sh
set -eu
args="$*"
mode="${FAKE_ADB_MODE:-pass}"
case "$args" in
  *"get-state"*)
    if [ "$mode" = "unavailable" ]; then exit 1; fi
    echo device
    ;;
  *"sys.boot_completed"*) echo 1 ;;
  *"ro.build.version.sdk"*) echo 35 ;;
  *"ro.build.version.release"*) echo 15 ;;
  *"ro.product.cpu.abi"*) echo x86_64 ;;
  *"emu avd name"*) printf 'senp_api35\nOK\n' ;;
  *"logcat -c"*) exit 0 ;;
  *"install"*)
    if [ "$mode" = "apk_fail" ]; then echo 'Failure [INSTALL_FAILED_TEST_ONLY]' >&2; exit 1; fi
    if [ "$mode" = "test_apk_fail" ] && echo "$args" | grep -q androidTest; then echo 'Failure [INSTALL_FAILED_INVALID_APK]' >&2; exit 1; fi
    echo Success
    ;;
  *"am instrument"*)
    if [ "$mode" = "instrumentation_fail" ]; then
      printf 'INSTRUMENTATION_FAILED: test failure\nINSTRUMENTATION_CODE: 0\n'
      exit 0
    fi
    printf 'INSTRUMENTATION_RESULT: stream=passed\nINSTRUMENTATION_CODE: -1\n'
    ;;
  *"logcat -d"*)
    if [ "$mode" = "crash" ]; then
      printf 'AndroidRuntime: FATAL EXCEPTION: main\nAndroidRuntime: Process: com.senp.qa.smoke\n'
    elif [ "$mode" = "unrelated_crash" ]; then
      printf 'AndroidRuntime: FATAL EXCEPTION: main\nAndroidRuntime: Process: com.example.unrelated\n'
    elif [ "$mode" = "anr" ]; then
      printf 'ActivityManager: ANR in com.senp.qa.smoke\n'
    else
      printf 'SENP_QA_SMOKE: ok=true\n'
    fi
    ;;
  *"dumpsys meminfo"*) printf 'TOTAL PSS: 1,234\n' ;;
  *"pull"*)
    last=""
    for value in "$@"; do last="$value"; done
    if [ "$mode" = "bad_artifact_memory" ]; then
      printf '{"schema_version":1,"ok":true,"peak_pss_bytes":"invalid"}\n' > "$last"
    else
      printf '{"schema_version":1,"ok":true,"peak_pss_bytes":2097152}\n' > "$last"
    fi
    printf '1 file pulled\n'
    ;;
  *) exit 0 ;;
esac
'''


class EmulatorHarnessTests(unittest.TestCase):
    def setUp(self) -> None:
        self.original_mode = os.environ.get("FAKE_ADB_MODE")

    def tearDown(self) -> None:
        if self.original_mode is None:
            os.environ.pop("FAKE_ADB_MODE", None)
        else:
            os.environ["FAKE_ADB_MODE"] = self.original_mode

    def make_config(self, root: Path, mode: str) -> EmulatorConfig:
        adb = root / "adb"
        adb.write_text(FAKE_ADB, encoding="utf-8")
        adb.chmod(0o755)
        apk = root / "app-debug.apk"
        test_apk = root / "app-debug-androidTest.apk"
        apk.write_bytes(b"apk")
        test_apk.write_bytes(b"test-apk")
        os.environ["FAKE_ADB_MODE"] = mode
        return EmulatorConfig(
            apk=apk,
            test_apk=test_apk,
            package="com.senp.qa.smoke",
            runner="com.senp.qa.smoke.test.SmokeInstrumentation",
            output_dir=root / "artifacts",
            adb=str(adb),
            start_emulator=False,
            remote_artifact="/sdcard/Android/data/com.senp.qa.smoke/files/senp-qa-smoke.json",
            boot_timeout_sec=1,
        )

    def test_success_report_contains_stages_memory_and_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            report = run_emulator_harness(self.make_config(Path(temp), "pass"))
            self.assertTrue(report["ok"])
            self.assertEqual(report["instrumentation_code"], -1)
            self.assertEqual(report["process_peak_rss_bytes"], 2097152)
            self.assertTrue(report["test_result"]["ok"])

    def assert_failure_code(self, mode: str, code: str) -> None:
        with tempfile.TemporaryDirectory() as temp:
            config = self.make_config(Path(temp), mode)
            with self.assertRaises(QaError) as caught:
                run_emulator_harness(config)
            self.assertEqual(caught.exception.code, code)
            self.assertTrue((config.output_dir / "emulator-report.json").is_file())

    def test_emulator_unavailable(self) -> None:
        self.assert_failure_code("unavailable", "emulator_unavailable")

    def test_apk_install_failure(self) -> None:
        self.assert_failure_code("apk_fail", "apk_install_failed")

    def test_test_apk_install_failure(self) -> None:
        self.assert_failure_code("test_apk_fail", "test_apk_install_failed")

    def test_instrumentation_failure(self) -> None:
        self.assert_failure_code("instrumentation_fail", "instrumentation_failed")

    def test_bad_instrumentation_memory_artifact(self) -> None:
        self.assert_failure_code("bad_artifact_memory", "bad_instrumentation_artifact")

    def test_crash_detection(self) -> None:
        self.assert_failure_code("crash", "crash_or_anr_detected")

    def test_anr_detection(self) -> None:
        self.assert_failure_code("anr", "crash_or_anr_detected")

    def test_unrelated_android_runtime_crash_is_ignored(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            report = run_emulator_harness(self.make_config(Path(temp), "unrelated_crash"))
            self.assertTrue(report["ok"])


if __name__ == "__main__":
    unittest.main()
