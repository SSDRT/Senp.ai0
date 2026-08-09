from __future__ import annotations

import argparse
import importlib.util
import json
import math
import tempfile
import unittest
from pathlib import Path

TOOL = Path(__file__).resolve().parents[1] / "reference_action_validation.py"
spec = importlib.util.spec_from_file_location("reference_action_validation", TOOL)
module = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(module)


class ReferenceActionValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = module.generate_fixture(frame_count=61, step_ms=100)

    def test_fixture_is_deterministic_and_complete_mediapipe33(self) -> None:
        again = module.generate_fixture(frame_count=61, step_ms=100)
        self.assertEqual(self.fixture, again)
        summary = module.validate_pose_extraction(self.fixture)
        self.assertEqual(summary["frame_count"], 61)
        self.assertEqual(summary["duration_ms"], 6000)
        self.assertEqual(
            {item["id"] for item in self.fixture["poses"]["frames"][0]["landmarks"]},
            set(module.LANDMARK_IDS),
        )

    def test_absolute_start_offset_changes_only_timestamps(self) -> None:
        shifted, metadata = module.apply_transform(self.fixture, {"kind": "start_offset", "offset_ms": 30000})
        original_frames = self.fixture["poses"]["frames"]
        shifted_frames = shifted["poses"]["frames"]
        self.assertEqual(shifted_frames[0]["timestamp"], 30000)
        self.assertEqual(shifted_frames[-1]["timestamp"], 36000)
        self.assertEqual(metadata["offset_ms"], 30000)
        for original, candidate in zip(original_frames, shifted_frames):
            self.assertEqual(original["landmarks"], candidate["landmarks"])

    def test_tempo_scaling_preserves_pose_order_and_timestamp_truth(self) -> None:
        slow, _ = module.apply_transform(self.fixture, {"kind": "tempo", "speed": 0.5})
        fast, _ = module.apply_transform(self.fixture, {"kind": "tempo", "speed": 2.0})
        self.assertEqual(slow["poses"]["frames"][-1]["timestamp"], 12000)
        self.assertEqual(fast["poses"]["frames"][-1]["timestamp"], 3000)
        self.assertEqual(
            [frame["landmarks"] for frame in slow["poses"]["frames"]],
            [frame["landmarks"] for frame in self.fixture["poses"]["frames"]],
        )
        module.validate_pose_extraction(slow)
        module.validate_pose_extraction(fast)

    def test_reverse_direction_reverses_pose_content_not_clock(self) -> None:
        reversed_payload, _ = module.apply_transform(self.fixture, {"kind": "reverse_direction"})
        original_frames = self.fixture["poses"]["frames"]
        candidate_frames = reversed_payload["poses"]["frames"]
        self.assertEqual(
            [frame["timestamp"] for frame in candidate_frames],
            [frame["timestamp"] for frame in original_frames],
        )
        self.assertEqual(candidate_frames[0]["landmarks"], original_frames[-1]["landmarks"])
        self.assertEqual(candidate_frames[-1]["landmarks"], original_frames[0]["landmarks"])

    def test_mirror_swaps_semantics_and_reflects_image_and_world_x(self) -> None:
        mirrored, _ = module.apply_transform(self.fixture, {"kind": "mirror"})
        source = {item["id"]: item for item in self.fixture["poses"]["frames"][10]["landmarks"]}
        candidate = {item["id"]: item for item in mirrored["poses"]["frames"][10]["landmarks"]}
        self.assertAlmostEqual(candidate["LEFT_WRIST"]["image"]["x"], 1.0 - source["RIGHT_WRIST"]["image"]["x"])
        self.assertAlmostEqual(candidate["LEFT_WRIST"]["world"]["xMeters"], -source["RIGHT_WRIST"]["world"]["xMeters"])
        double, _ = module.apply_transform(mirrored, {"kind": "mirror"})
        for original, restored in zip(self.fixture["poses"]["frames"], double["poses"]["frames"]):
            by_id_original = {item["id"]: item for item in original["landmarks"]}
            by_id_restored = {item["id"]: item for item in restored["landmarks"]}
            for landmark_id in module.LANDMARK_IDS:
                self.assertAlmostEqual(by_id_original[landmark_id]["image"]["x"], by_id_restored[landmark_id]["image"]["x"])
                self.assertAlmostEqual(by_id_original[landmark_id]["world"]["xMeters"], by_id_restored[landmark_id]["world"]["xMeters"])

    def test_yaw_preserves_world_radius_about_hip_center(self) -> None:
        yawed, _ = module.apply_transform(self.fixture, {"kind": "yaw", "degrees": 15})
        for before, after in zip(self.fixture["poses"]["frames"][::15], yawed["poses"]["frames"][::15]):
            before_by_id = {item["id"]: item for item in before["landmarks"]}
            after_by_id = {item["id"]: item for item in after["landmarks"]}
            for frame_map in (before_by_id, after_by_id):
                self.assertIn("LEFT_HIP", frame_map)
                self.assertIn("RIGHT_HIP", frame_map)
            before_cx = (before_by_id["LEFT_HIP"]["world"]["xMeters"] + before_by_id["RIGHT_HIP"]["world"]["xMeters"]) / 2
            before_cz = (before_by_id["LEFT_HIP"]["world"]["zMeters"] + before_by_id["RIGHT_HIP"]["world"]["zMeters"]) / 2
            after_cx = (after_by_id["LEFT_HIP"]["world"]["xMeters"] + after_by_id["RIGHT_HIP"]["world"]["xMeters"]) / 2
            after_cz = (after_by_id["LEFT_HIP"]["world"]["zMeters"] + after_by_id["RIGHT_HIP"]["world"]["zMeters"]) / 2
            for landmark_id in ("NOSE", "RIGHT_WRIST", "LEFT_ANKLE"):
                bx = before_by_id[landmark_id]["world"]["xMeters"] - before_cx
                bz = before_by_id[landmark_id]["world"]["zMeters"] - before_cz
                ax = after_by_id[landmark_id]["world"]["xMeters"] - after_cx
                az = after_by_id[landmark_id]["world"]["zMeters"] - after_cz
                self.assertAlmostEqual(math.hypot(bx, bz), math.hypot(ax, az), places=8)

    def test_geometry_deviation_is_localized(self) -> None:
        changed, metadata = module.apply_transform(
            self.fixture,
            {
                "kind": "geometry_deviation",
                "window_fraction": [0.4, 0.6],
                "landmarks": ["RIGHT_HIP"],
                "world_delta": {"zMeters": 0.12},
                "image_delta": {"y": 0.06},
            },
        )
        self.assertEqual(metadata["window_ms"], [2400, 3600])
        for before, after in zip(self.fixture["poses"]["frames"], changed["poses"]["frames"]):
            before_by_id = {item["id"]: item for item in before["landmarks"]}
            after_by_id = {item["id"]: item for item in after["landmarks"]}
            delta = after_by_id["RIGHT_HIP"]["world"]["zMeters"] - before_by_id["RIGHT_HIP"]["world"]["zMeters"]
            if 2400 <= before["timestamp"] <= 3600:
                self.assertAlmostEqual(delta, 0.12)
            else:
                self.assertAlmostEqual(delta, 0.0)
            self.assertEqual(before_by_id["LEFT_HIP"], after_by_id["LEFT_HIP"])

    def test_occlusion_sets_visibility_and_presence_only_in_window(self) -> None:
        changed, metadata = module.apply_transform(
            self.fixture,
            {"kind": "occlusion", "window_fraction": [0.4, 0.6], "landmarks": ["RIGHT_WRIST"], "confidence": 0.02},
        )
        self.assertEqual(metadata["window_ms"], [2400, 3600])
        for frame in changed["poses"]["frames"]:
            wrist = next(item for item in frame["landmarks"] if item["id"] == "RIGHT_WRIST")
            expected = 0.02 if 2400 <= frame["timestamp"] <= 3600 else 0.99
            self.assertAlmostEqual(wrist["visibility"], expected)
            self.assertAlmostEqual(wrist["presence"], expected)

    def test_hold_inserts_stationary_frames_and_delays_following_timestamps(self) -> None:
        held, metadata = module.apply_transform(self.fixture, {"kind": "hold", "anchor_fraction": 0.5, "hold_ms": 1200})
        self.assertEqual(metadata["anchor_ms"], 3000)
        self.assertEqual(held["duration"], 7200)
        self.assertEqual(len(held["poses"]["frames"]), 73)
        module.validate_pose_extraction(held)

    def test_drop_and_duplicate_known_rep_window(self) -> None:
        dropped, drop_meta = module.apply_transform(self.fixture, {"kind": "drop_window", "window_ms": [0, 2000]})
        duplicated, dup_meta = module.apply_transform(self.fixture, {"kind": "duplicate_window", "window_ms": [0, 2000]})
        self.assertEqual(drop_meta["removed_duration_ms"], 2000)
        self.assertEqual(dropped["duration"], 4000)
        self.assertEqual(len(dropped["poses"]["frames"]), 41)
        self.assertEqual(dup_meta["inserted_duration_ms"], 2000)
        self.assertEqual(duplicated["duration"], 8000)
        self.assertEqual(len(duplicated["poses"]["frames"]), 81)
        module.validate_pose_extraction(dropped)
        module.validate_pose_extraction(duplicated)

    def test_materialize_stages_rep_and_unrelated_cases_until_inputs_supplied(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            temp_root = Path(temp)
            pose = temp_root / "base.json"
            module.write_json(pose, self.fixture)
            report = module.materialize_cases(pose, module.DEFAULT_PLAN, temp_root / "out")
            skipped = {item["case_id"]: item["reason"] for item in report["skipped_cases"]}
            self.assertEqual(skipped["missing-repetition"], "rep_window_not_supplied")
            self.assertEqual(skipped["extra-repetition"], "rep_window_not_supplied")
            self.assertEqual(skipped["unrelated-motion"], "unrelated_pose_not_supplied")
            self.assertTrue((temp_root / "out" / "transform-manifest.json").is_file())
            candidate = module.load_json(temp_root / "out" / "self-reconstruction.pose.json")
            self.assertEqual(candidate["role"], "SOURCE")
            self.assertEqual(candidate["poses"]["role"], "SOURCE")

    def test_normalized_result_validator_rejects_unknown_state(self) -> None:
        result = self.make_result("self-reconstruction", self.fixture)
        result["observations"][0]["state_id"] = "UNKNOWN"
        with self.assertRaises(module.ValidationError):
            module.validate_normalized_result(result, "self-reconstruction")

    def test_normalized_result_allows_explicit_unusable_empty_profile_for_safe_suppression(self) -> None:
        result = self.make_result("weak-reference", self.fixture)
        result["classification"] = "SUPPRESSED"
        result["confidence"] = 0.95
        result["profile"] = {"usable": False, "state_ids": [], "legal_transitions": []}
        for observation in result["observations"]:
            observation["state_id"] = None
            observation["classification"] = "SUPPRESSED"
            observation["confidence"] = 0.0
        result["repetition_count"] = None
        summary = module.validate_normalized_result(result, "weak-reference")
        metrics = module.result_metrics(result)
        self.assertEqual(summary["states"], 0)
        self.assertEqual(metrics["state_coverage"], 0.0)

    def test_result_metrics_measure_state_coverage_and_legal_order(self) -> None:
        result = self.make_result("self-reconstruction", self.fixture)
        metrics = module.result_metrics(result)
        self.assertEqual(metrics["state_coverage"], 1.0)
        self.assertEqual(metrics["legal_transition_fraction"], 1.0)
        self.assertEqual(metrics["distinct_state_path"], ["S0", "S1", "S2"])

    def test_result_metrics_count_observed_entry_state_but_only_confirmed_transition_order(self) -> None:
        result = self.make_result("confirmed-only", self.fixture)
        result["profile"]["legal_transitions"] = [["S0", "S2"]]
        result["observations"] = [
            {
                "timestamp_ms": 0,
                "state_id": "S1",
                "tracking_status": "POSSIBLE_ENTRY",
                "classification": "UNCERTAIN",
                "confidence": 0.60,
            },
            {
                "timestamp_ms": 100,
                "state_id": "S0",
                "tracking_status": "TRACKING",
                "classification": "ACTION",
                "confidence": 0.90,
            },
            {
                "timestamp_ms": 200,
                "state_id": "S2",
                "tracking_status": "TRACKING",
                "classification": "ACTION",
                "confidence": 0.90,
            },
        ]
        metrics = module.result_metrics(result)
        self.assertEqual(metrics["distinct_state_path"], ["S0", "S2"])
        self.assertEqual(metrics["state_coverage"], 1.0)
        self.assertEqual(metrics["legal_transition_fraction"], 1.0)

    def test_evaluator_covers_required_metric_families_without_fabricated_scores(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            temp_root = Path(temp)
            base_path = temp_root / "base.json"
            unrelated_path = temp_root / "unrelated.json"
            module.write_json(base_path, self.fixture)
            module.write_json(unrelated_path, module.generate_fixture(frame_count=31, step_ms=120))
            transforms = module.materialize_cases(
                base_path,
                module.DEFAULT_PLAN,
                temp_root / "poses",
                rep_window_ms=(0, 2000),
                unrelated_pose_path=unrelated_path,
            )
            results = {}
            for item in transforms["materialized_cases"]:
                case_id = item["case_id"]
                pose_payload = module.load_json(Path(item["path"]))
                result = self.make_result(case_id, pose_payload)
                if case_id == "reverse-direction":
                    result["classification"] = "NO_ACTION"
                    result["confidence"] = 0.20
                    result["observations"] = list(reversed(result["observations"]))
                    timestamps = sorted(item["timestamp_ms"] for item in result["observations"])
                    for index, observation in enumerate(result["observations"]):
                        observation["timestamp_ms"] = timestamps[index]
                elif case_id == "geometry-deviation":
                    start_ms, end_ms = item["transform"]["window_ms"]
                    result["deviations"] = [{"start_ms": start_ms, "end_ms": end_ms, "kind": "geometry", "confidence": 0.92, "landmarks": ["RIGHT_HIP"]}]
                elif case_id == "occlusion-suppression":
                    start_ms, end_ms = item["transform"]["window_ms"]
                    for observation in result["observations"]:
                        if start_ms <= observation["timestamp_ms"] <= end_ms:
                            observation["classification"] = "SUPPRESSED"
                            observation["confidence"] = 0.10
                elif case_id == "missing-repetition":
                    result["repetition_count"] = 2
                elif case_id == "extra-repetition":
                    result["repetition_count"] = 4
                elif case_id == "unrelated-motion":
                    result["classification"] = "NO_ACTION"
                    result["confidence"] = 0.10
                results[case_id] = result
            summary = module.evaluate_results(transforms, results)
        statuses = {item["metric"]: item["status"] for item in summary["gates"]}
        self.assertTrue(summary["passed"])
        self.assertEqual(statuses["reference_self_state_coverage"], "PASS")
        self.assertEqual(statuses["absolute_start_offset_state_path_equal"], "PASS")
        self.assertEqual(statuses["tempo_invariance_0.5x_to_2x_pass_rate"], "PASS")
        self.assertEqual(statuses["reverse_direction_discrimination"], "PASS")
        self.assertEqual(statuses["injected_geometry_deviation_detection"], "PASS")
        self.assertEqual(statuses["missing_repetition_delta"], "PASS")
        self.assertEqual(statuses["extra_repetition_delta"], "PASS")
        self.assertEqual(statuses["pause_hold_invariance"], "PASS")
        self.assertEqual(statuses["confidence_occlusion_suppression"], "PASS")
        self.assertEqual(statuses["unrelated_motion_false_positive_rate"], "PASS")
        self.assertEqual(statuses["mirror_invariance"], "SKIPPED_UNSUPPORTED")
        self.assertEqual(statuses["live_cue_key_switch_rate"], "STAGED")

    def test_real_manifest_has_all_exercise_pairs_controls_and_generic_cases(self) -> None:
        report = module.validate_real_manifest(module.DEFAULT_REAL_MANIFEST, verify_hash=False)
        self.assertTrue(report["ok"])
        self.assertEqual(report["exercise_family_count"], 8)
        self.assertEqual(report["case_count"], 21)
        self.assertEqual(report["generic_case_count"], 5)
        self.assertEqual(report["locked_video_count"], 21)
        self.assertEqual(report["represented_video_count"], 21)
        manifest = module.load_json(module.DEFAULT_REAL_MANIFEST)
        wrong = [case for case in manifest["cases"] if case["relation"] == "wrong_vs_reference"]
        controls = [case for case in manifest["cases"] if case["relation"] == "self_control"]
        self.assertTrue(wrong and all(case["gating"] is False for case in wrong))
        self.assertTrue(controls and all(case["gating"] is True for case in controls))

    def test_real_result_evaluator_gates_controls_and_aggregates_negative_rate(self) -> None:
        manifest = module.load_json(module.DEFAULT_REAL_MANIFEST)
        results = {}
        for case in manifest["cases"]:
            result = self.make_result(case["id"], self.fixture)
            if case["relation"] == "unrelated_negative":
                result["classification"] = "NO_ACTION"
                result["confidence"] = 0.10
            results[case["id"]] = result
        summary = module.evaluate_real_results(manifest, results)
        self.assertTrue(summary["complete"])
        self.assertTrue(summary["passed"])
        fpr = next(gate for gate in summary["gates"] if gate["metric"] == "real_unrelated_motion_false_positive_rate")
        self.assertEqual(fpr["status"], "PASS")
        self.assertEqual(fpr["value"]["false_positive_rate"], 0.0)
        self.assertEqual(fpr["value"]["negative_cases"], 2)
        self.assertEqual(len(summary["report_only_cases"]), 10)

    def test_real_result_evaluator_marks_missing_gating_case_incomplete(self) -> None:
        manifest = module.load_json(module.DEFAULT_REAL_MANIFEST)
        results = {}
        for case in manifest["cases"]:
            if case["relation"] != "self_control":
                continue
            result = self.make_result(case["id"], self.fixture)
            results[case["id"]] = result
        summary = module.evaluate_real_results(manifest, results)
        self.assertFalse(summary["complete"])
        self.assertFalse(summary["passed"])
        self.assertEqual(len(summary["missing_gating_cases"]), 2)
        fpr = next(gate for gate in summary["gates"] if gate["metric"] == "real_unrelated_motion_false_positive_rate")
        self.assertEqual(fpr["status"], "NOT_AVAILABLE")

    def test_execute_run_invokes_normalized_executable_adapter_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            pose = root / "reference.json"
            plan = root / "plan.json"
            adapter = root / "adapter.py"
            module.write_json(pose, self.fixture)
            module.write_json(
                plan,
                {
                    "schema_version": 1,
                    "cases": [
                        {
                            "id": "self-reconstruction",
                            "transform": {"kind": "identity"},
                            "metrics": ["reference_self_action"],
                        }
                    ],
                },
            )
            adapter.write_text(
                """#!/usr/bin/env python3
import json, sys
request = json.load(open(sys.argv[1]))
result = {
    'schema': 'reference-action-normalized-result/1',
    'case_id': request['case_id'],
    'classification': 'ACTION',
    'confidence': 0.95,
    'profile': {'state_ids': ['S0', 'S1', 'S2'], 'legal_transitions': [['S0', 'S1'], ['S1', 'S2']]},
    'observations': [
        {'timestamp_ms': 0, 'state_id': 'S0', 'classification': 'ACTION', 'confidence': 0.95},
        {'timestamp_ms': 1, 'state_id': 'S1', 'classification': 'ACTION', 'confidence': 0.95},
        {'timestamp_ms': 2, 'state_id': 'S2', 'classification': 'ACTION', 'confidence': 0.95},
    ],
    'repetition_count': 1,
    'deviations': [],
    'cues': [],
    'capabilities': {'mirror_invariant': False, 'viewpoint_invariant': False, 'live_cues': False},
}
with open(request['result_output'], 'w') as handle:
    json.dump(result, handle)
""",
                encoding="utf-8",
            )
            adapter.chmod(0o755)
            args = argparse.Namespace(
                output_dir=str(root / "run"),
                pose_json=str(pose),
                plan=str(plan),
                rep_window_ms=None,
                unrelated_pose_json=None,
                adapter_executable=str(adapter),
            )
            summary = module.execute_run(args)
            self.assertEqual(summary["integration_status"], "EXECUTED")
            self.assertTrue(summary["passed"])
            self.assertTrue((root / "run" / "requests" / "self-reconstruction.json").is_file())
            self.assertTrue((root / "run" / "results" / "self-reconstruction.json").is_file())

    def test_pose_coverage_summary_reads_existing_extraction_shape(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            temp_root = Path(temp)
            real_manifest = {
                "schema_version": 1,
                "cases": [
                    {"id": "case", "candidate": "candidate.mp4", "reference": "reference.mp4"}
                ],
            }
            manifest_path = temp_root / "manifest.json"
            module.write_json(manifest_path, real_manifest)
            case_dir = temp_root / "evidence" / "case"
            case_dir.mkdir(parents=True)
            module.write_json(case_dir / "source_pose_extraction.json", self.fixture)
            module.write_json(case_dir / "reference_pose_extraction.json", self.fixture)
            report = module.summarize_pose_evidence(manifest_path, temp_root / "evidence")
        self.assertEqual(report["available_case_count"], 1)
        self.assertEqual(report["minimum_tracked_fraction_across_available_cases"], 1.0)

    @staticmethod
    def make_result(case_id: str, pose_payload: dict) -> dict:
        frames = pose_payload["poses"]["frames"]
        count = len(frames)
        observations = []
        for index, frame in enumerate(frames):
            bucket = min(2, (index * 3) // count)
            observations.append(
                {
                    "timestamp_ms": frame["timestamp"],
                    "state_id": f"S{bucket}",
                    "classification": "ACTION",
                    "confidence": 0.95,
                }
            )
        return {
            "schema": module.RESULT_SCHEMA,
            "case_id": case_id,
            "classification": "ACTION",
            "confidence": 0.95,
            "profile": {
                "state_ids": ["S0", "S1", "S2"],
                "legal_transitions": [["S0", "S1"], ["S1", "S2"]],
            },
            "observations": observations,
            "repetition_count": 3,
            "deviations": [],
            "cues": [],
            "capabilities": {
                "mirror_invariant": False,
                "viewpoint_invariant": False,
                "live_cues": False,
            },
        }


if __name__ == "__main__":
    unittest.main()
