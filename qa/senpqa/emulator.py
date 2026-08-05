from __future__ import annotations

import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

from .common import QaError, load_json, machine_metadata, run_command, utc_now, write_json

CRASH_PATTERNS = (
    re.compile(r"FATAL EXCEPTION", re.IGNORECASE),
    re.compile(r"\bANR in\b", re.IGNORECASE),
    re.compile(r"Application Not Responding", re.IGNORECASE),
    re.compile(r"INSTRUMENTATION_FAILED", re.IGNORECASE),
    re.compile(r"Process .* has died", re.IGNORECASE),
)
INSTRUMENTATION_FAILURE_PATTERNS = (
    re.compile(r"FAILURES!!!", re.IGNORECASE),
    re.compile(r"INSTRUMENTATION_FAILED", re.IGNORECASE),
    re.compile(r"shortMsg=Process crashed", re.IGNORECASE),
)


@dataclass(frozen=True)
class EmulatorConfig:
    apk: Path
    test_apk: Path
    package: str
    runner: str
    output_dir: Path
    test_package: str | None = None
    adb: str = "adb"
    emulator_helper: str = "senp-emulator"
    avd: str = "senp_api35"
    expected_api: int = 35
    boot_timeout_sec: int = 480
    start_emulator: bool = True
    remote_artifact: str | None = None


class _Recorder:
    def __init__(self) -> None:
        self.stages: list[dict[str, Any]] = []

    def run(self, name: str, operation: Callable[[], Any]) -> Any:
        started = time.perf_counter()
        try:
            value = operation()
        except Exception:
            elapsed = (time.perf_counter() - started) * 1000.0
            self.stages.append({"name": name, "duration_ms": round(elapsed, 3), "peak_rss_bytes": 0, "status": "failed"})
            raise
        elapsed = (time.perf_counter() - started) * 1000.0
        self.stages.append({"name": name, "duration_ms": round(elapsed, 3), "peak_rss_bytes": 0, "status": "passed"})
        return value


def _adb(config: EmulatorConfig, *args: str, timeout: float = 120.0, check: bool = True):
    return run_command([config.adb, "-e", *args], timeout=timeout, check=check)


def _device_state(config: EmulatorConfig) -> str:
    result = _adb(config, "get-state", timeout=15.0, check=False)
    return result.stdout.strip() if result.returncode == 0 else ""


def _wait_for_boot(config: EmulatorConfig) -> dict[str, Any]:
    deadline = time.monotonic() + config.boot_timeout_sec
    last_state = ""
    while time.monotonic() < deadline:
        last_state = _device_state(config)
        if last_state == "device":
            booted = _adb(config, "shell", "getprop", "sys.boot_completed", timeout=15.0, check=False).stdout.strip()
            if booted == "1":
                api_text = _adb(config, "shell", "getprop", "ro.build.version.sdk", timeout=15.0).stdout.strip()
                release = _adb(config, "shell", "getprop", "ro.build.version.release", timeout=15.0).stdout.strip()
                abi = _adb(config, "shell", "getprop", "ro.product.cpu.abi", timeout=15.0).stdout.strip()
                avd_output = _adb(config, "emu", "avd", "name", timeout=15.0, check=False).stdout
                actual_avd = next((line.strip() for line in avd_output.splitlines() if line.strip() and line.strip() != "OK"), "")
                try:
                    api = int(api_text)
                except ValueError as exc:
                    raise QaError("emulator_health_failed", f"Emulator returned invalid API level: {api_text!r}") from exc
                if api != config.expected_api:
                    raise QaError("emulator_api_mismatch", f"Expected API {config.expected_api}, emulator reports API {api}")
                if actual_avd and actual_avd != config.avd:
                    raise QaError("emulator_avd_mismatch", f"Expected AVD {config.avd}, connected emulator is {actual_avd}")
                return {"state": last_state, "boot_completed": True, "api": api, "release": release, "abi": abi, "avd": actual_avd or config.avd}
        time.sleep(2.0)
    raise QaError("emulator_unavailable", f"No booted emulator became available within {config.boot_timeout_sec}s", details={"last_state": last_state, "avd": config.avd})


def _ensure_emulator(config: EmulatorConfig) -> dict[str, Any]:
    if _device_state(config) != "device":
        if not config.start_emulator:
            raise QaError("emulator_unavailable", "No emulator is connected and automatic startup is disabled")
        start = run_command([config.emulator_helper, "start"], timeout=float(config.boot_timeout_sec), check=False)
        if start.returncode != 0:
            raise QaError("emulator_start_failed", f"Failed to start AVD {config.avd}", details={"stdout": start.stdout, "stderr": start.stderr})
    return _wait_for_boot(config)


def _parse_total_pss(meminfo: str) -> int:
    patterns = (
        re.compile(r"TOTAL PSS:\s*([0-9,]+)", re.IGNORECASE),
        re.compile(r"^\s*TOTAL\s+([0-9,]+)", re.MULTILINE),
    )
    for pattern in patterns:
        match = pattern.search(meminfo)
        if match:
            return int(match.group(1).replace(",", "")) * 1024
    return 0


def _detect_crash(logcat: str, package: str) -> list[str]:
    lines = logcat.splitlines()
    findings: list[str] = []
    for index, line in enumerate(lines):
        context = "\n".join(lines[max(0, index - 2):min(len(lines), index + 4)])
        if any(pattern.search(line) for pattern in CRASH_PATTERNS):
            # FATAL/ANR entries are global. Fail when the package is in nearby context,
            # or when the instrumentation framework itself reports the failure.
            if package in context or "INSTRUMENTATION" in context:
                findings.append(context)
    return findings


def run_emulator_harness(config: EmulatorConfig) -> dict[str, Any]:
    output_dir = config.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    report_path = output_dir / "emulator-report.json"
    recorder = _Recorder()
    report: dict[str, Any] = {
        "schema_version": 1,
        "generated_at": utc_now(),
        "run_id": f"emulator-{int(time.time() * 1000)}",
        "avd": config.avd,
        "expected_api": config.expected_api,
        "package": config.package,
        "test_package": config.test_package or f"{config.package}.test",
        "runner": config.runner,
        "apk": str(config.apk.resolve()),
        "test_apk": str(config.test_apk.resolve()),
        "machine": machine_metadata(),
        "ok": False,
        "failure": None,
        "stages": recorder.stages,
        "artifacts": {},
    }
    started = time.perf_counter()
    try:
        if not config.apk.is_file():
            raise QaError("apk_missing", f"Engine test APK is missing: {config.apk}")
        if not config.test_apk.is_file():
            raise QaError("test_apk_missing", f"Instrumentation APK is missing: {config.test_apk}")

        health = recorder.run("emulator_health", lambda: _ensure_emulator(config))
        report["device"] = health

        recorder.run("clear_logcat", lambda: _adb(config, "logcat", "-c", timeout=30.0))
        recorder.run("install_apk", lambda: _install(config, config.apk, "apk_install_failed"))
        recorder.run("install_test_apk", lambda: _install(config, config.test_apk, "test_apk_install_failed"))

        instrument = recorder.run(
            "instrumentation",
            lambda: _adb(
                config,
                "shell",
                "am",
                "instrument",
                "-w",
                "-r",
                f"{config.test_package or f'{config.package}.test'}/{config.runner}",
                timeout=300.0,
                check=False,
            ),
        )
        (output_dir / "instrumentation.txt").write_text(instrument.stdout + instrument.stderr, encoding="utf-8")
        report["artifacts"]["instrumentation"] = str((output_dir / "instrumentation.txt").resolve())
        instrument_text = instrument.stdout + instrument.stderr
        code_matches = re.findall(r"INSTRUMENTATION_CODE:\s*(-?\d+)", instrument_text)
        instrumentation_code = int(code_matches[-1]) if code_matches else None
        if (
            instrument.returncode != 0
            or any(pattern.search(instrument_text) for pattern in INSTRUMENTATION_FAILURE_PATTERNS)
            or instrumentation_code not in {-1, 0}
        ):
            raise QaError(
                "instrumentation_failed",
                f"Instrumentation failed with shell exit {instrument.returncode} and code {instrumentation_code}",
                details={"stdout": instrument.stdout, "stderr": instrument.stderr},
            )
        report["instrumentation_code"] = instrumentation_code

        logcat = recorder.run("collect_logcat", lambda: _adb(config, "logcat", "-d", "-v", "threadtime", timeout=60.0, check=False))
        logcat_path = output_dir / "logcat.txt"
        logcat_path.write_text(logcat.stdout + logcat.stderr, encoding="utf-8")
        report["artifacts"]["logcat"] = str(logcat_path.resolve())
        crashes = _detect_crash(logcat.stdout + logcat.stderr, config.package)
        if crashes:
            raise QaError("crash_or_anr_detected", f"Detected {len(crashes)} crash/ANR signature(s) in logcat", details=crashes)

        meminfo = recorder.run("collect_memory", lambda: _adb(config, "shell", "dumpsys", "meminfo", config.package, timeout=60.0, check=False))
        meminfo_path = output_dir / "meminfo.txt"
        meminfo_path.write_text(meminfo.stdout + meminfo.stderr, encoding="utf-8")
        report["artifacts"]["meminfo"] = str(meminfo_path.resolve())
        peak_rss = _parse_total_pss(meminfo.stdout)
        if recorder.stages:
            recorder.stages[-1]["peak_rss_bytes"] = peak_rss

        if config.remote_artifact:
            local_artifact = output_dir / Path(config.remote_artifact).name
            pull = recorder.run(
                "pull_test_artifact",
                lambda: _adb(config, "pull", config.remote_artifact, str(local_artifact), timeout=60.0, check=False),
            )
            if pull.returncode != 0 or not local_artifact.is_file():
                raise QaError("artifact_pull_failed", f"Failed to pull instrumentation artifact {config.remote_artifact}", details={"stdout": pull.stdout, "stderr": pull.stderr})
            artifact_payload = load_json(local_artifact)
            if not isinstance(artifact_payload, dict) or artifact_payload.get("ok") is not True:
                raise QaError("bad_instrumentation_artifact", "Instrumentation artifact does not report ok=true", details=artifact_payload)
            report["artifacts"]["test_json"] = str(local_artifact.resolve())
            report["test_result"] = artifact_payload
            try:
                artifact_peak = int(artifact_payload.get("peak_pss_bytes", 0))
            except (TypeError, ValueError) as exc:
                raise QaError(
                    "bad_instrumentation_artifact",
                    "Instrumentation artifact peak_pss_bytes must be an integer",
                    details=artifact_payload,
                ) from exc
            if artifact_peak < 0:
                raise QaError(
                    "bad_instrumentation_artifact",
                    "Instrumentation artifact peak_pss_bytes must be non-negative",
                    details=artifact_payload,
                )
            if artifact_peak > 0:
                recorder.stages[-1]["peak_rss_bytes"] = artifact_peak

        report["ok"] = True
    except QaError as exc:
        report["failure"] = exc.as_dict()
        write_json(report_path, _finalize_report(report, recorder, started))
        raise QaError(exc.code, exc.message, details=report) from exc
    finalized = _finalize_report(report, recorder, started)
    write_json(report_path, finalized)
    return finalized


def _install(config: EmulatorConfig, apk: Path, error_code: str) -> None:
    result = _adb(config, "install", "-r", "-t", str(apk), timeout=180.0, check=False)
    if result.returncode != 0 or "Success" not in result.stdout:
        raise QaError(error_code, f"adb install failed for {apk.name}", details={"stdout": result.stdout, "stderr": result.stderr})


def _finalize_report(report: dict[str, Any], recorder: _Recorder, started: float) -> dict[str, Any]:
    report["stages"] = recorder.stages
    report["total_duration_ms"] = round((time.perf_counter() - started) * 1000.0, 3)
    report["process_peak_rss_bytes"] = max((int(stage.get("peak_rss_bytes", 0)) for stage in recorder.stages), default=0)
    report["completed_at"] = utc_now()
    return report
