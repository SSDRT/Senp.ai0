from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any

from .common import QaError, load_json, require, write_json

CONTRACT_VERSION = "1.0"
SCENARIOS = (
    "clean",
    "speed_shifted",
    "deliberate_error",
    "short_gap",
    "long_blind",
    "different_rep_count",
    "single_rep",
)


def _track(duration_ms: int, step_ms: int, reps: float, *, phase: float = 0.0) -> list[dict[str, Any]]:
    values: list[dict[str, Any]] = []
    for timestamp_ms in range(0, duration_ms + 1, step_ms):
        unit = timestamp_ms / duration_ms
        angle = 2.0 * math.pi * reps * unit + phase
        values.append(
            {
                "timestamp_ms": timestamp_ms,
                "valid": True,
                "features": {
                    "primary": round(0.5 + 0.45 * math.sin(angle), 8),
                    "secondary": round(0.5 + 0.35 * math.cos(angle), 8),
                },
            }
        )
    return values


def _invalid_between(track: list[dict[str, Any]], start_ms: int, end_ms: int) -> None:
    for sample in track:
        if start_ms <= int(sample["timestamp_ms"]) <= end_ms:
            sample["valid"] = False
            sample["features"] = {}


def _error_between(track: list[dict[str, Any]], start_ms: int, end_ms: int, delta: float) -> None:
    for sample in track:
        if start_ms <= int(sample["timestamp_ms"]) <= end_ms:
            sample["features"]["primary"] = round(float(sample["features"]["primary"]) + delta, 8)


def build_fixture(scenario_id: str) -> dict[str, Any]:
    require(scenario_id in SCENARIOS, "unknown_golden_scenario", f"Unknown golden scenario: {scenario_id}")
    reference = _track(10_000, 100, 3.0)
    candidate = _track(10_000, 100, 3.0)
    expectations: dict[str, Any] = {
        "valid_input": True,
        "allowed_alignment_modes": ["rep_normalized", "anchor_dtw", "global_dtw"],
        "mapping": {"monotonic": True, "bounded": True},
        "genuine_windows": {"forbidden": []},
        "uncertain_windows": {"required_overlap": []},
    }
    description = "Identical three-repetition motion tracks."

    if scenario_id == "speed_shifted":
        candidate = _track(7_500, 75, 3.0)
        description = "Equivalent motion completed 25 percent faster."
        expectations["genuine_windows"]["max_count"] = 0
        expectations["invariants"] = ["speed_only_change_is_not_genuine"]
    elif scenario_id == "deliberate_error":
        _error_between(candidate, 4_000, 5_200, 0.55)
        description = "Localized deliberate form deviation during the middle repetition."
        expectations["genuine_windows"]["required_overlap"] = [{"start_ms": 4_000, "end_ms": 5_200}]
    elif scenario_id == "short_gap":
        _invalid_between(candidate, 3_000, 3_300)
        description = "A 300 ms missing-pose gap intended for short-gap repair upstream."
        expectations["genuine_windows"]["forbidden"] = [{"start_ms": 3_000, "end_ms": 3_300}]
        expectations["metadata"] = {"gap_duration_ms": 300, "gap_class": "short"}
    elif scenario_id == "long_blind":
        _invalid_between(candidate, 3_500, 5_800)
        description = "A 2300 ms blind span that must never produce a confident genuine error."
        expectations["genuine_windows"]["forbidden"] = [{"start_ms": 3_500, "end_ms": 5_800}]
        expectations["uncertain_windows"]["required_overlap"] = [{"start_ms": 3_500, "end_ms": 5_800}]
        expectations["invariants"] = ["no_confident_error_in_blind_region"]
    elif scenario_id == "different_rep_count":
        candidate = _track(10_000, 100, 2.0)
        description = "Reference has three repetitions while candidate has two."
        expectations["allowed_alignment_modes"] = ["rep_normalized", "anchor_dtw", "global_dtw", "linear_insufficient_motion"]
        expectations["metadata"] = {"reference_reps": 3, "candidate_reps": 2}
    elif scenario_id == "single_rep":
        reference = _track(5_000, 100, 1.0)
        candidate = _track(4_200, 100, 1.0, phase=0.08)
        description = "Single-repetition tracks with a small phase offset."
        expectations["allowed_alignment_modes"] = ["anchor_dtw", "global_dtw", "linear_insufficient_motion"]
        expectations["metadata"] = {"reference_reps": 1, "candidate_reps": 1}

    return {
        "contract_version": CONTRACT_VERSION,
        "scenario_id": scenario_id,
        "description": description,
        "inputs": {
            "reference": {"time_unit": "ms", "samples": reference},
            "candidate": {"time_unit": "ms", "samples": candidate},
        },
        "expectations": expectations,
    }


def corrupt_fixture() -> dict[str, Any]:
    return {
        "contract_version": CONTRACT_VERSION,
        "scenario_id": "corrupt_input",
        "description": "Intentionally invalid fixture with duplicate timestamps and a non-numeric feature.",
        "inputs": {
            "reference": {
                "time_unit": "ms",
                "samples": [
                    {"timestamp_ms": 0, "valid": True, "features": {"primary": 0.0}},
                    {"timestamp_ms": 0, "valid": True, "features": {"primary": "not-a-number"}},
                ],
            },
            "candidate": {"time_unit": "ms", "samples": []},
        },
        "expectations": {"valid_input": False, "error_code": "invalid_track"},
    }


def generate_fixtures(output_dir: Path) -> list[Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    paths: list[Path] = []
    for scenario_id in SCENARIOS:
        path = output_dir / f"{scenario_id}.json"
        write_json(path, build_fixture(scenario_id))
        paths.append(path)
    corrupt = output_dir / "corrupt_input.json"
    write_json(corrupt, corrupt_fixture())
    paths.append(corrupt)
    return paths


def _validate_track(track: Any, label: str) -> None:
    require(isinstance(track, dict), "invalid_track", f"{label} track must be an object")
    require(track.get("time_unit") == "ms", "invalid_track", f"{label} time_unit must be ms")
    samples = track.get("samples")
    require(isinstance(samples, list) and samples, "invalid_track", f"{label} must contain samples")
    previous = -1
    for index, sample in enumerate(samples):
        require(isinstance(sample, dict), "invalid_track", f"{label} sample {index} must be an object")
        timestamp = int(sample.get("timestamp_ms", -1))
        require(timestamp > previous, "invalid_track", f"{label} timestamps must be strictly increasing")
        previous = timestamp
        require(isinstance(sample.get("valid"), bool), "invalid_track", f"{label} sample {index} lacks boolean valid")
        features = sample.get("features")
        require(isinstance(features, dict), "invalid_track", f"{label} sample {index} features must be an object")
        for name, value in features.items():
            require(isinstance(name, str) and name, "invalid_track", f"{label} feature name is invalid")
            require(isinstance(value, (int, float)) and math.isfinite(float(value)), "invalid_track", f"{label} feature {name} is not finite")


def validate_fixture(value: Any, *, expect_corrupt: bool = False) -> dict[str, Any]:
    require(isinstance(value, dict), "bad_golden_fixture", "Golden fixture must contain an object")
    require(value.get("contract_version") == CONTRACT_VERSION, "bad_golden_fixture", "Unsupported golden contract version")
    scenario_id = str(value.get("scenario_id", ""))
    inputs = value.get("inputs")
    require(isinstance(inputs, dict), "bad_golden_fixture", "Golden fixture lacks inputs")
    try:
        _validate_track(inputs.get("reference"), "reference")
        _validate_track(inputs.get("candidate"), "candidate")
    except QaError:
        if expect_corrupt:
            return {"scenario_id": scenario_id, "valid": False, "expected_invalid": True}
        raise
    require(scenario_id in SCENARIOS, "bad_golden_fixture", f"Unexpected valid scenario id: {scenario_id}")
    expectations = value.get("expectations")
    require(isinstance(expectations, dict), "bad_golden_fixture", "Golden fixture lacks expectations")
    require(expectations.get("valid_input") is True, "bad_golden_fixture", "Valid fixture must expect valid input")
    return {"scenario_id": scenario_id, "valid": True, "expected_invalid": False}


def validate_fixture_directory(directory: Path, *, verify_determinism: bool = True) -> dict[str, Any]:
    results: list[dict[str, Any]] = []
    expected_names = {f"{scenario_id}.json" for scenario_id in SCENARIOS} | {"corrupt_input.json"}
    actual_names = {path.name for path in directory.glob("*.json")}
    require(actual_names == expected_names, "golden_set_mismatch", f"Golden fixture set mismatch: expected {sorted(expected_names)}, found {sorted(actual_names)}")
    for name in sorted(expected_names):
        path = directory / name
        value = load_json(path)
        result = validate_fixture(value, expect_corrupt=name == "corrupt_input.json")
        if verify_determinism:
            expected = corrupt_fixture() if name == "corrupt_input.json" else build_fixture(name.removesuffix(".json"))
            require(value == expected, "golden_not_deterministic", f"Fixture differs from deterministic generator: {name}")
        results.append({"path": str(path), **result})
    return {"ok": True, "contract_version": CONTRACT_VERSION, "fixtures": results}


def _overlaps(left: dict[str, Any], right: dict[str, Any]) -> bool:
    return int(left["start_ms"]) <= int(right["end_ms"]) and int(right["start_ms"]) <= int(left["end_ms"])


def evaluate_result(fixture: dict[str, Any], result: Any) -> dict[str, Any]:
    validate_fixture(fixture)
    require(isinstance(result, dict), "bad_lane_result", "Lane result must contain an object")
    require(result.get("contract_version") == CONTRACT_VERSION, "bad_lane_result", "Lane result contract_version must be 1.0")
    require(result.get("scenario_id") == fixture.get("scenario_id"), "bad_lane_result", "Result scenario_id does not match fixture")
    mode = str(result.get("alignment_mode", ""))
    allowed_modes = fixture["expectations"].get("allowed_alignment_modes", [])
    require(mode in allowed_modes, "golden_expectation_failed", f"Alignment mode {mode!r} is not allowed for this fixture")
    mapping = result.get("mapping")
    require(isinstance(mapping, list) and mapping, "bad_lane_result", "Result mapping must be non-empty")
    previous_reference = -1
    previous_candidate = -1
    reference_end = fixture["inputs"]["reference"]["samples"][-1]["timestamp_ms"]
    candidate_end = fixture["inputs"]["candidate"]["samples"][-1]["timestamp_ms"]
    for point in mapping:
        require(isinstance(point, dict), "bad_lane_result", "Each mapping point must be an object")
        reference_ms = int(point.get("reference_ms", -1))
        candidate_ms = int(point.get("candidate_ms", -1))
        require(reference_ms >= previous_reference and candidate_ms >= previous_candidate, "golden_expectation_failed", "Mapping must be monotonic")
        require(0 <= reference_ms <= reference_end and 0 <= candidate_ms <= candidate_end, "golden_expectation_failed", "Mapping must stay within track bounds")
        previous_reference, previous_candidate = reference_ms, candidate_ms
    genuine = result.get("genuine_windows", [])
    uncertain = result.get("uncertain_windows", [])
    require(isinstance(genuine, list) and isinstance(uncertain, list), "bad_lane_result", "Window lists must be arrays")
    window_expectations = fixture["expectations"].get("genuine_windows", {})
    max_count = window_expectations.get("max_count")
    if max_count is not None:
        require(len(genuine) <= int(max_count), "golden_expectation_failed", f"Expected at most {max_count} genuine windows")
    for required_window in window_expectations.get("required_overlap", []):
        require(any(_overlaps(window, required_window) for window in genuine), "golden_expectation_failed", f"No genuine window overlaps {required_window}")
    for forbidden_window in window_expectations.get("forbidden", []):
        require(not any(_overlaps(window, forbidden_window) for window in genuine), "golden_expectation_failed", f"Genuine window overlaps forbidden region {forbidden_window}")
    for required_window in fixture["expectations"].get("uncertain_windows", {}).get("required_overlap", []):
        require(any(_overlaps(window, required_window) for window in uncertain), "golden_expectation_failed", f"No uncertain window overlaps {required_window}")
    return {"ok": True, "scenario_id": fixture["scenario_id"], "alignment_mode": mode, "mapping_points": len(mapping), "genuine_windows": len(genuine), "uncertain_windows": len(uncertain)}
