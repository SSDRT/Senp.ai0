from __future__ import annotations

import math
import statistics
from pathlib import Path
from typing import Any

from .common import QaError, load_json, require, utc_now, write_json

BENCHMARK_SCHEMA_VERSION = 1


def validate_stage_report(report: Any) -> dict[str, Any]:
    require(isinstance(report, dict), "bad_stage_report", "Stage report must contain an object")
    require(int(report.get("schema_version", 0)) == BENCHMARK_SCHEMA_VERSION, "bad_stage_report", "Unsupported stage report schema_version")
    require(isinstance(report.get("run_id"), str) and report["run_id"], "bad_stage_report", "Stage report lacks run_id")
    stages = report.get("stages")
    require(isinstance(stages, list) and stages, "bad_stage_report", "Stage report requires stages[]")
    total = 0.0
    for index, stage in enumerate(stages):
        require(isinstance(stage, dict), "bad_stage_report", f"Stage {index} must be an object")
        require(isinstance(stage.get("name"), str) and stage["name"], "bad_stage_report", f"Stage {index} lacks name")
        duration = float(stage.get("duration_ms", -1))
        peak = int(stage.get("peak_rss_bytes", -1))
        require(duration >= 0 and peak >= 0, "bad_stage_report", f"Stage {stage.get('name')} has invalid duration or memory")
        require(stage.get("status") in {"passed", "failed", "skipped"}, "bad_stage_report", f"Stage {stage.get('name')} has invalid status")
        total += duration
    declared_total = float(report.get("total_duration_ms", -1))
    require(declared_total >= 0, "bad_stage_report", "total_duration_ms must be non-negative")
    require(abs(declared_total - total) <= max(2.0, total * 0.02), "bad_stage_report", "total_duration_ms does not match stage durations")
    require(int(report.get("process_peak_rss_bytes", -1)) >= 0, "bad_stage_report", "process_peak_rss_bytes must be non-negative")
    return report


def validate_benchmark_report(report: Any) -> dict[str, Any]:
    require(isinstance(report, dict), "bad_benchmark_report", "Benchmark report must contain an object")
    require(int(report.get("schema_version", 0)) == BENCHMARK_SCHEMA_VERSION, "bad_benchmark_report", "Unsupported benchmark schema_version")
    require(isinstance(report.get("suite"), str) and report["suite"], "bad_benchmark_report", "Benchmark report lacks suite")
    cases = report.get("cases")
    require(isinstance(cases, list) and cases, "bad_benchmark_report", "Benchmark report requires cases[]")
    identifiers: set[str] = set()
    for case in cases:
        require(isinstance(case, dict), "bad_benchmark_report", "Each benchmark case must be an object")
        case_id = str(case.get("id", ""))
        require(case_id and case_id not in identifiers, "bad_benchmark_report", f"Duplicate or empty benchmark case id: {case_id!r}")
        identifiers.add(case_id)
        for key in ("median_ms", "p95_ms"):
            metric = float(case.get(key, -1))
            require(math.isfinite(metric) and metric >= 0, "bad_benchmark_report", f"Case {case_id} has invalid {key}")
        require(int(case.get("peak_rss_bytes", -1)) >= 0, "bad_benchmark_report", f"Case {case_id} has invalid peak_rss_bytes")
        require(int(case.get("repetitions", 0)) > 0, "bad_benchmark_report", f"Case {case_id} must have repetitions")
    return report


def summarize_samples(
    *,
    suite: str,
    case_samples: dict[str, dict[str, Any]],
    metadata: dict[str, Any] | None = None,
) -> dict[str, Any]:
    cases: list[dict[str, Any]] = []
    for case_id, value in sorted(case_samples.items()):
        durations = [float(item) for item in value.get("durations_ms", [])]
        memory = [int(item) for item in value.get("peak_rss_bytes", [])]
        require(durations and len(durations) == len(memory), "bad_benchmark_samples", f"Case {case_id} samples are empty or mismatched")
        ordered = sorted(durations)
        p95_position = min(len(ordered) - 1, max(0, int(round(0.95 * (len(ordered) - 1)))))
        cases.append(
            {
                "id": case_id,
                "fps": int(value.get("fps", 0)),
                "input_duration_ms": int(value.get("input_duration_ms", 0)),
                "repetitions": len(durations),
                "median_ms": round(statistics.median(durations), 6),
                "p95_ms": round(ordered[p95_position], 6),
                "peak_rss_bytes": max(memory),
                "raw": {"durations_ms": durations, "peak_rss_bytes": memory},
            }
        )
    report = {
        "schema_version": BENCHMARK_SCHEMA_VERSION,
        "generated_at": utc_now(),
        "suite": suite,
        "metadata": metadata or {},
        "cases": cases,
    }
    return validate_benchmark_report(report)


def compare_reports(baseline: Any, candidate: Any, gates: Any) -> dict[str, Any]:
    baseline = validate_benchmark_report(baseline)
    candidate = validate_benchmark_report(candidate)
    require(baseline["suite"] == candidate["suite"], "benchmark_suite_mismatch", "Baseline and candidate suites differ")
    require(isinstance(gates, dict), "bad_benchmark_gates", "Benchmark gates must contain an object")
    maximum_regression = float(gates.get("max_median_regression_percent", 15.0))
    maximum_absolute = float(gates.get("max_median_absolute_increase_ms", 20.0))
    maximum_memory = float(gates.get("max_peak_rss_regression_percent", 15.0))
    required_cases = set(str(item) for item in gates.get("required_cases", []))
    baseline_by_id = {case["id"]: case for case in baseline["cases"]}
    candidate_by_id = {case["id"]: case for case in candidate["cases"]}
    missing = sorted((set(baseline_by_id) | required_cases) - set(candidate_by_id))
    require(not missing, "benchmark_case_missing", f"Candidate report lacks required cases: {missing}")
    comparisons: list[dict[str, Any]] = []
    overall_ok = True
    for case_id in sorted(set(baseline_by_id) & set(candidate_by_id)):
        before = baseline_by_id[case_id]
        after = candidate_by_id[case_id]
        before_median = float(before["median_ms"])
        after_median = float(after["median_ms"])
        absolute_delta = after_median - before_median
        if before_median == 0:
            regression_percent: float | None = 0.0 if after_median == 0 else None
            timing_ok = absolute_delta <= maximum_absolute
        else:
            regression_percent = absolute_delta / before_median * 100.0
            timing_ok = regression_percent <= maximum_regression or absolute_delta <= maximum_absolute
        before_memory = int(before["peak_rss_bytes"])
        after_memory = int(after["peak_rss_bytes"])
        if before_memory == 0:
            memory_percent: float | None = 0.0 if after_memory == 0 else None
            memory_ok = after_memory == 0
        else:
            memory_percent = (after_memory - before_memory) / before_memory * 100.0
            memory_ok = memory_percent <= maximum_memory
        case_ok = timing_ok and memory_ok
        overall_ok = overall_ok and case_ok
        comparisons.append(
            {
                "id": case_id,
                "ok": case_ok,
                "baseline_median_ms": before_median,
                "candidate_median_ms": after_median,
                "median_delta_ms": round(absolute_delta, 6),
                "median_regression_percent": round(regression_percent, 6) if regression_percent is not None else None,
                "baseline_peak_rss_bytes": before_memory,
                "candidate_peak_rss_bytes": after_memory,
                "peak_rss_regression_percent": round(memory_percent, 6) if memory_percent is not None else None,
                "timing_ok": timing_ok,
                "memory_ok": memory_ok,
            }
        )
    return {
        "schema_version": 1,
        "generated_at": utc_now(),
        "ok": overall_ok,
        "suite": baseline["suite"],
        "gates": {
            "max_median_regression_percent": maximum_regression,
            "max_median_absolute_increase_ms": maximum_absolute,
            "max_peak_rss_regression_percent": maximum_memory,
            "required_cases": sorted(required_cases),
        },
        "comparisons": comparisons,
    }


def compare_files(baseline_path: Path, candidate_path: Path, gates_path: Path, output_path: Path | None = None) -> dict[str, Any]:
    report = compare_reports(load_json(baseline_path), load_json(candidate_path), load_json(gates_path))
    if output_path:
        write_json(output_path, report)
    if not report["ok"]:
        raise QaError("benchmark_gate_failed", "Candidate benchmark exceeded one or more gates", details=report)
    return report
