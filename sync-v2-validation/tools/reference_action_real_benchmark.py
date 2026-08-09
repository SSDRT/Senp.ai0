#!/usr/bin/env python3
"""Run the generic JVM reference-action adapter over saved API35 pose evidence.

Fixture names and relations are used only to select/report validation material. Runtime action
classification remains generic and reference-relative inside the JVM adapter.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import reference_action_validation as validation


@dataclass(frozen=True)
class Case:
    case_id: str
    relation: str
    candidate: Path
    reference: Path
    gating: bool


def parse_args() -> argparse.Namespace:
    tool_dir = Path(__file__).resolve().parent
    module_root = tool_dir.parent
    repo_root = module_root.parent
    parser = argparse.ArgumentParser(description="Run saved-pose real-video reference-action validation")
    parser.add_argument(
        "--adapter-executable",
        type=Path,
        default=repo_root / "scripts" / "reference-action-adapter",
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=module_root / "fixtures" / "reference-action-real-video-manifest.json",
    )
    parser.add_argument(
        "--exercise-root",
        type=Path,
        default=Path(
            "/home/coder/mcp_workspace/Senp-ai0-product-release/test-artifacts/"
            "product-release/real-extended-final"
        ),
    )
    parser.add_argument(
        "--cricket-root",
        type=Path,
        default=Path(
            "/home/coder/mcp_workspace/Senp-ai0-reference-action-validation/test-artifacts/"
            "reference-action-validation/api35-cricket/jofra-vs-net-batting"
        ),
    )
    parser.add_argument(
        "--cricket-crease-root",
        type=Path,
        default=Path(
            "/home/coder/mcp_workspace/Senp-ai0-reference-action-validation/test-artifacts/"
            "reference-action-validation/api35-cricket/jofra-vs-crease-batting"
        ),
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=repo_root / "test-artifacts" / "reference-action-real-adapter",
    )
    return parser.parse_args()


def discover_cases(
    manifest_path: Path,
    exercise_root: Path,
    cricket_root: Path,
    cricket_crease_root: Path,
) -> list[Case]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    by_id = {str(item["id"]): item for item in manifest["cases"]}
    cases: list[Case] = []

    if exercise_root.is_dir():
        for case_dir in sorted(path for path in exercise_root.iterdir() if path.is_dir()):
            case_id = case_dir.name
            descriptor = by_id.get(case_id)
            if descriptor is None:
                continue
            candidate = case_dir / "source_pose_extraction.json"
            reference = case_dir / "reference_pose_extraction.json"
            if candidate.is_file() and reference.is_file():
                cases.append(
                    Case(
                        case_id=case_id,
                        relation=str(descriptor["relation"]),
                        candidate=candidate,
                        reference=reference,
                        gating=bool(descriptor.get("gating", False)),
                    )
                )

    cricket_reference = cricket_root / "reference_pose_extraction.json"
    cricket_candidate = cricket_root / "source_pose_extraction.json"
    if cricket_reference.is_file():
        descriptor = by_id["cricket-jofra-self-control"]
        cases.append(
            Case(
                case_id="cricket-jofra-self-control",
                relation=str(descriptor["relation"]),
                candidate=cricket_reference,
                reference=cricket_reference,
                gating=bool(descriptor.get("gating", False)),
            )
        )
    if cricket_reference.is_file() and cricket_candidate.is_file():
        descriptor = by_id["cricket-jofra-vs-net-batting-negative"]
        cases.append(
            Case(
                case_id="cricket-jofra-vs-net-batting-negative",
                relation=str(descriptor["relation"]),
                candidate=cricket_candidate,
                reference=cricket_reference,
                gating=bool(descriptor.get("gating", False)),
            )
        )

    crease_reference = cricket_crease_root / "reference_pose_extraction.json"
    crease_candidate = cricket_crease_root / "source_pose_extraction.json"
    if crease_reference.is_file() and crease_candidate.is_file():
        descriptor = by_id["cricket-jofra-vs-crease-batting-negative"]
        cases.append(
            Case(
                case_id="cricket-jofra-vs-crease-batting-negative",
                relation=str(descriptor["relation"]),
                candidate=crease_candidate,
                reference=crease_reference,
                gating=bool(descriptor.get("gating", False)),
            )
        )
    return cases


def run_case(executable: Path, case: Case, output_dir: Path) -> dict[str, Any]:
    case_dir = output_dir / case.case_id
    case_dir.mkdir(parents=True, exist_ok=True)
    result_path = case_dir / "result.json"
    request_path = case_dir / "request.json"
    request = {
        "schema_version": 1,
        "protocol": validation.PROTOCOL,
        "mode": "reference_action_pose_compare",
        "case_id": case.case_id,
        "reference_pose_extraction_json": str(case.reference.resolve()),
        "candidate_pose_extraction_json": str(case.candidate.resolve()),
        "result_output": str(result_path.resolve()),
        "required_result_schema": validation.RESULT_SCHEMA,
    }
    request_path.write_text(json.dumps(request, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    started = time.perf_counter()
    completed = subprocess.run(
        [str(executable), str(request_path)],
        check=False,
        text=True,
        capture_output=True,
    )
    wall_ms = (time.perf_counter() - started) * 1000.0
    if completed.returncode != 0 or not result_path.is_file():
        return {
            "case_id": case.case_id,
            "relation": case.relation,
            "gating": case.gating,
            "adapter_exit_code": completed.returncode,
            "harness_wall_ms": wall_ms,
            "error": (completed.stderr or completed.stdout).strip()[-2000:],
            "result": None,
            "metrics": None,
            "gate_passed": False,
            "gate_failures": ["ADAPTER_EXECUTION_FAILED"],
        }

    result = json.loads(result_path.read_text(encoding="utf-8"))
    validation.validate_normalized_result(result, case.case_id)
    metrics = validation.result_metrics(result)
    failures = gate_failures(case, metrics)
    return {
        "case_id": case.case_id,
        "relation": case.relation,
        "gating": case.gating,
        "candidate": str(case.candidate),
        "reference": str(case.reference),
        "adapter_exit_code": completed.returncode,
        "harness_wall_ms": wall_ms,
        "result": result,
        "metrics": metrics,
        "gate_passed": not failures,
        "gate_failures": failures,
    }


def gate_failures(case: Case, metrics: dict[str, Any]) -> list[str]:
    if not case.gating:
        return []
    failures: list[str] = []
    if case.relation == "self_control":
        if metrics["classification"] != "ACTION":
            failures.append("SELF_NOT_ACTION")
        if float(metrics["state_coverage"]) < 0.90:
            failures.append("SELF_STATE_COVERAGE_LT_0_90")
        if float(metrics["legal_transition_fraction"]) < 0.999:
            failures.append("SELF_LEGAL_TRANSITIONS_LT_0_999")
        if int(metrics["deviation_count"]) != 0:
            failures.append("SELF_DEVIATIONS_NONZERO")
    elif case.relation == "unrelated_negative":
        false_positive = metrics["classification"] == "ACTION" and float(metrics["confidence"]) > 0.30
        if false_positive:
            failures.append("UNRELATED_FALSE_POSITIVE")
    return failures


def compact_row(row: dict[str, Any]) -> dict[str, Any]:
    result = row.get("result") or {}
    metrics = row.get("metrics") or {}
    compile_result = result.get("compile") or {}
    profile = result.get("profile") or {}
    recognition = result.get("recognition") or {}
    runtime = result.get("runtime") or {}
    validation_result = compile_result.get("validation") or profile.get("validation") or {}
    return {
        "case_id": row["case_id"],
        "relation": row["relation"],
        "gating": row["gating"],
        "gate_passed": row["gate_passed"],
        "gate_failures": row["gate_failures"],
        "classification": metrics.get("classification"),
        "confidence": metrics.get("confidence"),
        "profile_usable": profile.get("usable"),
        "profile_confidence": profile.get("confidence"),
        "self_reconstruction_accuracy": validation_result.get("reconstruction_accuracy"),
        "self_transition_coverage": validation_result.get("transition_coverage"),
        "self_mean_recognition_confidence": validation_result.get("mean_recognition_confidence"),
        "final_tracking_status": recognition.get("final_status"),
        "tracked_fraction": recognition.get("tracked_fraction"),
        "state_coverage": metrics.get("state_coverage"),
        "legal_transition_fraction": metrics.get("legal_transition_fraction"),
        "mean_action_confidence": recognition.get("mean_action_confidence"),
        "repetition_count": metrics.get("repetition_count"),
        "repetition_delta": result.get("repetition_delta_from_reference"),
        "deviation_count": metrics.get("deviation_count"),
        "runtime_total_ms": runtime.get("total_ms"),
        "compile_ms": runtime.get("compile_ms"),
        "recognition_and_deviation_ms": runtime.get("recognition_and_deviation_ms"),
        "harness_wall_ms": row["harness_wall_ms"],
    }


def main() -> int:
    args = parse_args()
    executable = args.adapter_executable.resolve()
    if not executable.is_file():
        raise SystemExit(f"adapter executable not found: {executable}")
    manifest_path = args.manifest.resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    cases = discover_cases(
        manifest_path,
        args.exercise_root,
        args.cricket_root,
        args.cricket_crease_root,
    )
    if not cases:
        raise SystemExit("no saved pose cases found")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    rows = [run_case(executable, case, args.output_dir) for case in cases]
    compact = [compact_row(row) for row in rows]
    gating_rows = [row for row in rows if row["gating"]]
    failed = [row["case_id"] for row in gating_rows if not row["gate_passed"]]
    available_negative_count = sum(row["relation"] == "unrelated_negative" for row in rows)
    manifest_negative_count = sum(
        str(case.get("relation")) == "unrelated_negative"
        for case in manifest.get("cases", [])
    )
    normalized_results = {
        row["case_id"]: row["result"]
        for row in rows
        if isinstance(row.get("result"), dict)
    }
    manifest_evaluation = validation.evaluate_real_results(manifest, normalized_results)
    summary = {
        "schema": "reference-action-real-benchmark/1",
        "case_count": len(rows),
        "gating_case_count": len(gating_rows),
        "gating_passed_count": len(gating_rows) - len(failed),
        "gating_failed_count": len(failed),
        "failed_gating_cases": failed,
        "available_unrelated_negative_count": available_negative_count,
        "manifest_unrelated_negative_count": manifest_negative_count,
        "negative_coverage_complete": available_negative_count == manifest_negative_count,
        "manifest_evaluation": manifest_evaluation,
        "note": (
            "Wrong-vs-reference exercise cases are report-only. Negative coverage is complete only when every "
            "unrelated-negative case in the checked-in manifest has saved pose extraction and a normalized result."
        ),
        "rows": compact,
    }
    (args.output_dir / "summary.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
