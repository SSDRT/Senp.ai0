from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from senpqa.benchmark import compare_reports, validate_stage_report
from senpqa.common import QaError, load_json
from senpqa.golden import build_fixture, evaluate_result, validate_fixture, validate_fixture_directory

QA_ROOT = Path(__file__).resolve().parents[1]


class GoldenBenchmarkTests(unittest.TestCase):
    def test_committed_golden_set_is_deterministic(self) -> None:
        report = validate_fixture_directory(QA_ROOT / "fixtures" / "golden")
        self.assertTrue(report["ok"])
        self.assertEqual(len(report["fixtures"]), 8)

    def test_corrupt_track_rejected_when_not_expected(self) -> None:
        fixture = build_fixture("clean")
        fixture["inputs"]["candidate"]["samples"][1]["timestamp_ms"] = 0
        with self.assertRaises(QaError) as caught:
            validate_fixture(fixture)
        self.assertEqual(caught.exception.code, "invalid_track")

    def test_corrupt_fixture_cannot_be_evaluated_as_valid(self) -> None:
        fixture = load_json(QA_ROOT / "fixtures" / "golden" / "corrupt_input.json")
        result = {
            "contract_version": "1.0",
            "scenario_id": "corrupt_input",
            "alignment_mode": "global_dtw",
            "mapping": [{"reference_ms": 0, "candidate_ms": 0}],
            "genuine_windows": [],
            "uncertain_windows": [],
        }
        with self.assertRaises(QaError) as caught:
            evaluate_result(fixture, result)
        self.assertEqual(caught.exception.code, "invalid_track")

    def test_speed_only_genuine_error_fails_expectation(self) -> None:
        fixture = build_fixture("speed_shifted")
        result = {
            "contract_version": "1.0",
            "scenario_id": "speed_shifted",
            "alignment_mode": "global_dtw",
            "mapping": [{"reference_ms": 0, "candidate_ms": 0}, {"reference_ms": 10000, "candidate_ms": 7500}],
            "genuine_windows": [{"start_ms": 1000, "end_ms": 2000}],
            "uncertain_windows": [],
        }
        with self.assertRaises(QaError) as caught:
            evaluate_result(fixture, result)
        self.assertEqual(caught.exception.code, "golden_expectation_failed")

    def test_long_blind_requires_uncertain_not_genuine(self) -> None:
        fixture = build_fixture("long_blind")
        result = {
            "contract_version": "1.0",
            "scenario_id": "long_blind",
            "alignment_mode": "anchor_dtw",
            "mapping": [{"reference_ms": 0, "candidate_ms": 0}, {"reference_ms": 10000, "candidate_ms": 10000}],
            "genuine_windows": [],
            "uncertain_windows": [{"start_ms": 3400, "end_ms": 5900}],
        }
        self.assertTrue(evaluate_result(fixture, result)["ok"])

    def test_benchmark_pass_and_fail_gates(self) -> None:
        baseline = load_json(QA_ROOT / "fixtures" / "benchmark" / "baseline.json")
        passing = load_json(QA_ROOT / "fixtures" / "benchmark" / "candidate-pass.json")
        failing = load_json(QA_ROOT / "fixtures" / "benchmark" / "candidate-fail.json")
        gates = load_json(QA_ROOT / "benchmark-gates.json")
        self.assertTrue(compare_reports(baseline, passing, gates)["ok"])
        self.assertFalse(compare_reports(baseline, failing, gates)["ok"])

    def test_zero_baseline_does_not_waive_large_regression(self) -> None:
        baseline = {
            "schema_version": 1,
            "suite": "zero-baseline",
            "cases": [
                {"id": "case", "repetitions": 1, "median_ms": 0.0, "p95_ms": 0.0, "peak_rss_bytes": 0}
            ],
        }
        candidate = {
            "schema_version": 1,
            "suite": "zero-baseline",
            "cases": [
                {"id": "case", "repetitions": 1, "median_ms": 100.0, "p95_ms": 100.0, "peak_rss_bytes": 1024}
            ],
        }
        gates = {
            "max_median_regression_percent": 10.0,
            "max_median_absolute_increase_ms": 5.0,
            "max_peak_rss_regression_percent": 10.0,
            "required_cases": ["case"],
        }
        comparison = compare_reports(baseline, candidate, gates)
        self.assertFalse(comparison["ok"])
        self.assertIsNone(comparison["comparisons"][0]["median_regression_percent"])
        self.assertIsNone(comparison["comparisons"][0]["peak_rss_regression_percent"])

    def test_stage_report_total_must_match(self) -> None:
        report = {
            "schema_version": 1,
            "run_id": "test",
            "stages": [
                {"name": "decode", "duration_ms": 10.0, "peak_rss_bytes": 100, "status": "passed"},
                {"name": "pose", "duration_ms": 20.0, "peak_rss_bytes": 200, "status": "passed"},
            ],
            "total_duration_ms": 30.0,
            "process_peak_rss_bytes": 200,
        }
        self.assertEqual(validate_stage_report(report)["run_id"], "test")
        report["total_duration_ms"] = 99.0
        with self.assertRaises(QaError):
            validate_stage_report(report)


if __name__ == "__main__":
    unittest.main()
