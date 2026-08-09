#!/usr/bin/env python3
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import statistics
import subprocess
import sys
from pathlib import Path
from typing import Any

TOOL_DIR = Path(__file__).resolve().parent
MODULE_ROOT = TOOL_DIR.parent
DEFAULT_REAL_MANIFEST = MODULE_ROOT / "fixtures" / "reference-action-real-video-manifest.json"
DEFAULT_PLAN = MODULE_ROOT / "fixtures" / "reference-action-synthetic-plan.json"

PROTOCOL = "senp-reference-action-validation-adapter/1"
RESULT_SCHEMA = "reference-action-normalized-result/1"
CLASSIFICATIONS = {"ACTION", "NO_ACTION", "SUPPRESSED", "UNCERTAIN"}

LANDMARK_IDS = [
    "NOSE",
    "LEFT_EYE_INNER", "LEFT_EYE", "LEFT_EYE_OUTER",
    "RIGHT_EYE_INNER", "RIGHT_EYE", "RIGHT_EYE_OUTER",
    "LEFT_EAR", "RIGHT_EAR", "MOUTH_LEFT", "MOUTH_RIGHT",
    "LEFT_SHOULDER", "RIGHT_SHOULDER", "LEFT_ELBOW", "RIGHT_ELBOW",
    "LEFT_WRIST", "RIGHT_WRIST", "LEFT_PINKY", "RIGHT_PINKY",
    "LEFT_INDEX", "RIGHT_INDEX", "LEFT_THUMB", "RIGHT_THUMB",
    "LEFT_HIP", "RIGHT_HIP", "LEFT_KNEE", "RIGHT_KNEE",
    "LEFT_ANKLE", "RIGHT_ANKLE", "LEFT_HEEL", "RIGHT_HEEL",
    "LEFT_FOOT_INDEX", "RIGHT_FOOT_INDEX",
]


class ValidationError(RuntimeError):
    pass


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _finite(value: Any) -> bool:
    return isinstance(value, (int, float)) and math.isfinite(float(value))


def _probability(value: Any, field: str) -> float:
    if not _finite(value) or not 0.0 <= float(value) <= 1.0:
        raise ValidationError(f"{field} must be a finite probability")
    return float(value)


def _frames(payload: dict[str, Any]) -> list[dict[str, Any]]:
    try:
        frames = payload["poses"]["frames"]
    except (KeyError, TypeError) as exc:
        raise ValidationError("pose extraction must contain poses.frames") from exc
    if not isinstance(frames, list) or not frames:
        raise ValidationError("pose extraction requires at least one pose frame")
    return frames


def validate_pose_extraction(payload: dict[str, Any]) -> dict[str, Any]:
    frames = _frames(payload)
    previous = -1
    expected_ids = set(LANDMARK_IDS)
    for index, frame in enumerate(frames):
        timestamp = frame.get("timestamp")
        if not isinstance(timestamp, int) or timestamp < 0 or timestamp <= previous:
            raise ValidationError(f"pose frame {index} timestamp must be a strictly increasing non-negative integer ms")
        previous = timestamp
        landmarks = frame.get("landmarks")
        if not isinstance(landmarks, list) or len(landmarks) != 33:
            raise ValidationError(f"pose frame {index} must contain exactly 33 landmarks")
        ids = {str(item.get("id")) for item in landmarks if isinstance(item, dict)}
        if ids != expected_ids:
            raise ValidationError(f"pose frame {index} landmark ids do not match MediaPipe-33")
        for landmark in landmarks:
            image = landmark.get("image", {})
            world = landmark.get("world", {})
            for field in ("x", "y", "z"):
                if not _finite(image.get(field)):
                    raise ValidationError(f"pose frame {index} {landmark.get('id')} image.{field} must be finite")
            for field in ("xMeters", "yMeters", "zMeters"):
                if not _finite(world.get(field)):
                    raise ValidationError(f"pose frame {index} {landmark.get('id')} world.{field} must be finite")
            _probability(landmark.get("visibility"), "visibility")
            _probability(landmark.get("presence"), "presence")
    return {
        "frame_count": len(frames),
        "start_timestamp_ms": frames[0]["timestamp"],
        "end_timestamp_ms": frames[-1]["timestamp"],
        "duration_ms": int(payload.get("duration", frames[-1]["timestamp"] - frames[0]["timestamp"])),
    }


def _median_step(frames: list[dict[str, Any]]) -> int:
    steps = [b["timestamp"] - a["timestamp"] for a, b in zip(frames, frames[1:])]
    return max(1, int(round(statistics.median(steps)))) if steps else 67


def _reset_diagnostic_indexes(frames: list[dict[str, Any]]) -> None:
    for index, frame in enumerate(frames):
        if "diagnosticFrameIndex" in frame:
            frame["diagnosticFrameIndex"] = index


def _set_duration(payload: dict[str, Any]) -> None:
    frames = _frames(payload)
    if len(frames) == 1:
        payload["duration"] = 0
    else:
        payload["duration"] = frames[-1]["timestamp"] - frames[0]["timestamp"]


def _resolve_window(frames: list[dict[str, Any]], spec: dict[str, Any]) -> tuple[int, int]:
    start = frames[0]["timestamp"]
    end = frames[-1]["timestamp"]
    span = max(1, end - start)
    if "window_ms" in spec:
        raw = spec["window_ms"]
        if not isinstance(raw, list) or len(raw) != 2:
            raise ValidationError("window_ms must be [start_ms, end_ms]")
        left, right = int(raw[0]), int(raw[1])
    elif "window_fraction" in spec:
        raw = spec["window_fraction"]
        if not isinstance(raw, list) or len(raw) != 2:
            raise ValidationError("window_fraction must be [start_fraction, end_fraction]")
        left = start + round(span * float(raw[0]))
        right = start + round(span * float(raw[1]))
    else:
        raise ValidationError("transform requires window_ms or window_fraction")
    if left < start or right > end or left >= right:
        raise ValidationError(f"invalid transform window [{left}, {right}] for pose range [{start}, {end}]")
    return left, right


def _counterpart(landmark_id: str) -> str:
    if landmark_id.startswith("LEFT_"):
        return "RIGHT_" + landmark_id[5:]
    if landmark_id.startswith("RIGHT_"):
        return "LEFT_" + landmark_id[6:]
    if landmark_id == "MOUTH_LEFT":
        return "MOUTH_RIGHT"
    if landmark_id == "MOUTH_RIGHT":
        return "MOUTH_LEFT"
    return landmark_id


def _mirror_frame(frame: dict[str, Any]) -> None:
    by_id = {str(item["id"]): copy.deepcopy(item) for item in frame["landmarks"]}
    mirrored: list[dict[str, Any]] = []
    for target_id in LANDMARK_IDS:
        source = copy.deepcopy(by_id[_counterpart(target_id)])
        source["id"] = target_id
        source["image"]["x"] = 1.0 - float(source["image"]["x"])
        source["world"]["xMeters"] = -float(source["world"]["xMeters"])
        mirrored.append(source)
    frame["landmarks"] = mirrored


def _yaw_frame(frame: dict[str, Any], degrees: float) -> None:
    by_id = {str(item["id"]): item for item in frame["landmarks"]}
    hips = [by_id.get("LEFT_HIP"), by_id.get("RIGHT_HIP")]
    hips = [item for item in hips if item is not None]
    cx = statistics.mean(float(item["world"]["xMeters"]) for item in hips) if hips else 0.0
    cz = statistics.mean(float(item["world"]["zMeters"]) for item in hips) if hips else 0.0
    angle = math.radians(degrees)
    cosine = math.cos(angle)
    sine = math.sin(angle)
    for landmark in frame["landmarks"]:
        world = landmark["world"]
        x = float(world["xMeters"]) - cx
        z = float(world["zMeters"]) - cz
        world["xMeters"] = cx + x * cosine + z * sine
        world["zMeters"] = cz - x * sine + z * cosine


def apply_transform(payload: dict[str, Any], spec: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    result = copy.deepcopy(payload)
    validate_pose_extraction(result)
    frames = _frames(result)
    kind = str(spec.get("kind", ""))
    metadata: dict[str, Any] = {"kind": kind}

    if kind == "identity":
        pass
    elif kind == "start_offset":
        offset = int(spec["offset_ms"])
        if offset < 0:
            raise ValidationError("start_offset must be non-negative")
        for frame in frames:
            frame["timestamp"] += offset
        metadata["offset_ms"] = offset
    elif kind == "tempo":
        speed = float(spec["speed"])
        if not math.isfinite(speed) or speed <= 0:
            raise ValidationError("tempo speed must be finite and > 0")
        origin = frames[0]["timestamp"]
        previous = origin - 1
        for frame in frames:
            scaled = origin + round((frame["timestamp"] - origin) / speed)
            frame["timestamp"] = max(previous + 1, scaled)
            previous = frame["timestamp"]
        _set_duration(result)
        metadata["speed"] = speed
    elif kind == "reverse_direction":
        timestamps = [frame["timestamp"] for frame in frames]
        content = [copy.deepcopy(frame) for frame in reversed(frames)]
        for timestamp, frame in zip(timestamps, content):
            frame["timestamp"] = timestamp
        result["poses"]["frames"] = content
        frames = content
    elif kind == "mirror":
        for frame in frames:
            _mirror_frame(frame)
        metadata["transform_space"] = "image_x_and_world_x_with_left_right_semantic_swap"
    elif kind == "yaw":
        degrees = float(spec["degrees"])
        if not math.isfinite(degrees) or abs(degrees) > 45:
            raise ValidationError("synthetic yaw must be finite and within +/-45 degrees")
        for frame in frames:
            _yaw_frame(frame, degrees)
        metadata.update({"degrees": degrees, "transform_space": "world_coordinates_only"})
    elif kind == "geometry_deviation":
        left, right = _resolve_window(frames, spec)
        targets = {str(item) for item in spec["landmarks"]}
        if not targets or not targets.issubset(set(LANDMARK_IDS)):
            raise ValidationError("geometry deviation landmarks must be a non-empty MediaPipe-33 subset")
        world_delta = {str(k): float(v) for k, v in spec.get("world_delta", {}).items()}
        image_delta = {str(k): float(v) for k, v in spec.get("image_delta", {}).items()}
        world_fields = {"xMeters", "yMeters", "zMeters"}
        image_fields = {"x", "y", "z"}
        if not set(world_delta).issubset(world_fields) or not set(image_delta).issubset(image_fields):
            raise ValidationError("geometry deviation contains unknown coordinate field")
        for frame in frames:
            if left <= frame["timestamp"] <= right:
                for landmark in frame["landmarks"]:
                    if landmark["id"] in targets:
                        for field, delta in world_delta.items():
                            landmark["world"][field] = float(landmark["world"][field]) + delta
                        for field, delta in image_delta.items():
                            landmark["image"][field] = float(landmark["image"][field]) + delta
        metadata.update({"window_ms": [left, right], "landmarks": sorted(targets), "world_delta": world_delta, "image_delta": image_delta})
    elif kind == "occlusion":
        left, right = _resolve_window(frames, spec)
        targets = {str(item) for item in spec["landmarks"]}
        value = float(spec.get("confidence", 0.02))
        if not targets or not targets.issubset(set(LANDMARK_IDS)):
            raise ValidationError("occlusion landmarks must be a non-empty MediaPipe-33 subset")
        if not 0.0 <= value <= 1.0:
            raise ValidationError("occlusion confidence must be in [0, 1]")
        for frame in frames:
            if left <= frame["timestamp"] <= right:
                for landmark in frame["landmarks"]:
                    if landmark["id"] in targets:
                        landmark["visibility"] = value
                        landmark["presence"] = value
        metadata.update({"window_ms": [left, right], "landmarks": sorted(targets), "confidence": value})
    elif kind == "hold":
        anchor_fraction = float(spec.get("anchor_fraction", 0.5))
        hold_ms = int(spec["hold_ms"])
        if not 0.0 <= anchor_fraction <= 1.0 or hold_ms <= 0:
            raise ValidationError("hold requires anchor_fraction in [0,1] and positive hold_ms")
        start, end = frames[0]["timestamp"], frames[-1]["timestamp"]
        target = start + round((end - start) * anchor_fraction)
        anchor = min(frames, key=lambda frame: abs(frame["timestamp"] - target))
        anchor_ms = anchor["timestamp"]
        step = _median_step(frames)
        before = [copy.deepcopy(frame) for frame in frames if frame["timestamp"] <= anchor_ms]
        after = [copy.deepcopy(frame) for frame in frames if frame["timestamp"] > anchor_ms]
        inserted: list[dict[str, Any]] = []
        timestamp = anchor_ms + step
        while timestamp <= anchor_ms + hold_ms:
            duplicate = copy.deepcopy(anchor)
            duplicate["timestamp"] = timestamp
            inserted.append(duplicate)
            timestamp += step
        for frame in after:
            frame["timestamp"] += hold_ms
        combined = before + inserted + after
        combined.sort(key=lambda frame: frame["timestamp"])
        result["poses"]["frames"] = combined
        frames = combined
        _set_duration(result)
        metadata.update({"window_ms": [anchor_ms, anchor_ms + hold_ms], "anchor_ms": anchor_ms, "hold_ms": hold_ms})
    elif kind == "drop_window":
        left, right = _resolve_window(frames, spec)
        delta = right - left
        kept: list[dict[str, Any]] = []
        for frame in frames:
            if left <= frame["timestamp"] < right:
                continue
            copied = copy.deepcopy(frame)
            if copied["timestamp"] >= right:
                copied["timestamp"] -= delta
            kept.append(copied)
        if len(kept) < 2:
            raise ValidationError("drop_window removed too much of the sequence")
        result["poses"]["frames"] = kept
        frames = kept
        _set_duration(result)
        metadata.update({"source_window_ms": [left, right], "removed_duration_ms": delta})
    elif kind == "duplicate_window":
        left, right = _resolve_window(frames, spec)
        delta = right - left
        selected = [copy.deepcopy(frame) for frame in frames if left <= frame["timestamp"] < right]
        if not selected:
            raise ValidationError("duplicate_window selected no frames")
        combined: list[dict[str, Any]] = []
        for frame in frames:
            if frame["timestamp"] < right:
                combined.append(copy.deepcopy(frame))
            else:
                shifted = copy.deepcopy(frame)
                shifted["timestamp"] += delta
                combined.append(shifted)
        for frame in selected:
            frame["timestamp"] += delta
            combined.append(frame)
        combined.sort(key=lambda frame: frame["timestamp"])
        result["poses"]["frames"] = combined
        frames = combined
        _set_duration(result)
        metadata.update({"source_window_ms": [left, right], "inserted_window_ms": [right, right + delta], "inserted_duration_ms": delta})
    else:
        raise ValidationError(f"unknown synthetic transform kind: {kind}")

    _reset_diagnostic_indexes(frames)
    summary = validate_pose_extraction(result)
    metadata["result"] = summary
    return result, metadata


def _base_landmarks(progress: float) -> list[dict[str, Any]]:
    # Deterministic contract fixture only: a plausible 33-landmark body with an
    # asymmetric arm path. It is not a learned or biomechanical reference model.
    base: dict[str, tuple[float, float, float]] = {}
    for index, landmark_id in enumerate(LANDMARK_IDS):
        side = -1.0 if "LEFT" in landmark_id else 1.0 if "RIGHT" in landmark_id else 0.0
        tier = index / max(1, len(LANDMARK_IDS) - 1)
        base[landmark_id] = (0.5 + side * (0.05 + 0.08 * tier), 0.12 + 0.76 * tier, side * 0.03)
    arm = math.sin(progress * math.pi * 2.0)
    bend = (1.0 - math.cos(progress * math.pi * 2.0)) * 0.5
    for landmark_id, amount in (("RIGHT_ELBOW", 0.10), ("RIGHT_WRIST", 0.18), ("RIGHT_INDEX", 0.20), ("RIGHT_PINKY", 0.20), ("RIGHT_THUMB", 0.18)):
        x, y, z = base[landmark_id]
        base[landmark_id] = (x - amount * bend, y - amount * 0.8 * bend, z + 0.05 * arm)
    landmarks: list[dict[str, Any]] = []
    for landmark_id in LANDMARK_IDS:
        x, y, z = base[landmark_id]
        landmarks.append({
            "id": landmark_id,
            "image": {"x": x, "y": y, "z": z},
            "world": {"xMeters": (x - 0.5) * 1.4, "yMeters": (y - 0.5) * 1.8, "zMeters": z * 1.5},
            "visibility": 0.99,
            "presence": 0.99,
        })
    return landmarks


def generate_fixture(frame_count: int = 61, step_ms: int = 100) -> dict[str, Any]:
    if frame_count < 9 or step_ms <= 0:
        raise ValidationError("fixture requires at least 9 frames and positive step_ms")
    frames = []
    for index in range(frame_count):
        # Three complete cycles provide a deterministic rep window for mutation tests.
        progress = (index / (frame_count - 1)) * 3.0
        frames.append({
            "timestamp": index * step_ms,
            "diagnosticFrameIndex": index,
            "landmarks": _base_landmarks(progress),
        })
    payload = {
        "role": "REFERENCE",
        "duration": (frame_count - 1) * step_ms,
        "poses": {"role": "REFERENCE", "frames": frames},
        "diagnostics": {
            "decodedFrameCount": frame_count,
            "sampledFrameCount": frame_count,
            "detectedFrameCount": frame_count,
            "noPersonFrameCount": 0,
            "unusableTrackingFrameCount": 0,
            "synthetic": True,
        },
    }
    validate_pose_extraction(payload)
    return payload


def _load_plan(path: Path) -> dict[str, Any]:
    plan = load_json(path)
    if int(plan.get("schema_version", 0)) != 1 or not isinstance(plan.get("cases"), list):
        raise ValidationError("reference-action synthetic plan must be schema_version 1 with cases[]")
    ids = [str(item.get("id")) for item in plan["cases"]]
    if len(ids) != len(set(ids)):
        raise ValidationError("synthetic plan case ids must be unique")
    return plan


def materialize_cases(
    pose_path: Path,
    plan_path: Path,
    output_dir: Path,
    rep_window_ms: tuple[int, int] | None = None,
    unrelated_pose_path: Path | None = None,
) -> dict[str, Any]:
    base = load_json(pose_path)
    base_summary = validate_pose_extraction(base)
    plan = _load_plan(plan_path)
    output_dir.mkdir(parents=True, exist_ok=True)
    materialized: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    for case in plan["cases"]:
        case_id = str(case["id"])
        source = str(case.get("source", "transform"))
        if source == "unrelated":
            if unrelated_pose_path is None:
                skipped.append({"case_id": case_id, "reason": "unrelated_pose_not_supplied"})
                continue
            transformed = load_json(unrelated_pose_path)
            metadata = {"kind": "external_unrelated", "result": validate_pose_extraction(transformed)}
        else:
            spec = copy.deepcopy(case["transform"])
            if spec.get("window") == "rep_window":
                if rep_window_ms is None:
                    skipped.append({"case_id": case_id, "reason": "rep_window_not_supplied"})
                    continue
                spec.pop("window", None)
                spec["window_ms"] = [rep_window_ms[0], rep_window_ms[1]]
            transformed, metadata = apply_transform(base, spec)
        # The base sequence is a reference, while every materialized sequence is
        # presented to the future adapter as the candidate/source side.
        transformed["role"] = "SOURCE"
        transformed["poses"]["role"] = "SOURCE"
        destination = output_dir / f"{case_id}.pose.json"
        write_json(destination, transformed)
        materialized.append({
            "case_id": case_id,
            "path": str(destination.resolve()),
            "sha256": sha256(destination),
            "transform": metadata,
            "expectation": case.get("expectation", {}),
            "metrics": case.get("metrics", []),
        })
    manifest = {
        "schema_version": 1,
        "protocol": PROTOCOL,
        "base_pose": {"path": str(pose_path.resolve()), "sha256": sha256(pose_path), **base_summary},
        "rep_window_ms": list(rep_window_ms) if rep_window_ms else None,
        "unrelated_pose": str(unrelated_pose_path.resolve()) if unrelated_pose_path else None,
        "materialized_cases": materialized,
        "skipped_cases": skipped,
    }
    write_json(output_dir / "transform-manifest.json", manifest)
    return manifest


def validate_normalized_result(result: dict[str, Any], case_id: str | None = None) -> dict[str, Any]:
    if result.get("schema") != RESULT_SCHEMA:
        raise ValidationError(f"normalized result schema must be {RESULT_SCHEMA}")
    if case_id is not None and result.get("case_id") != case_id:
        raise ValidationError(f"normalized result case_id mismatch: expected {case_id}, got {result.get('case_id')}")
    classification = str(result.get("classification"))
    if classification not in CLASSIFICATIONS:
        raise ValidationError(f"invalid classification: {classification}")
    confidence = _probability(result.get("confidence"), "confidence")
    profile = result.get("profile")
    if not isinstance(profile, dict):
        raise ValidationError("normalized result requires profile object")
    state_ids = profile.get("state_ids")
    legal = profile.get("legal_transitions")
    if not isinstance(state_ids, list) or not state_ids or any(not isinstance(item, str) or not item for item in state_ids):
        raise ValidationError("profile.state_ids must be a non-empty string list")
    if len(state_ids) != len(set(state_ids)):
        raise ValidationError("profile.state_ids must be unique")
    if not isinstance(legal, list):
        raise ValidationError("profile.legal_transitions must be a list")
    for edge in legal:
        if not isinstance(edge, list) or len(edge) != 2 or any(item not in state_ids for item in edge):
            raise ValidationError("every legal transition must be a two-state edge using profile.state_ids")
    observations = result.get("observations")
    if not isinstance(observations, list):
        raise ValidationError("normalized result requires observations[]")
    previous = -1
    for index, observation in enumerate(observations):
        if not isinstance(observation, dict):
            raise ValidationError("each observation must be an object")
        timestamp = observation.get("timestamp_ms")
        if not isinstance(timestamp, int) or timestamp < 0 or timestamp <= previous:
            raise ValidationError(f"observation {index} timestamps must be strictly increasing non-negative ms")
        previous = timestamp
        state_id = observation.get("state_id")
        if state_id is not None and state_id not in state_ids:
            raise ValidationError(f"observation {index} uses unknown state_id {state_id}")
        if "confidence" in observation:
            _probability(observation["confidence"], f"observation {index} confidence")
        if "classification" in observation and observation["classification"] not in CLASSIFICATIONS:
            raise ValidationError(f"observation {index} has invalid classification")
    repetition_count = result.get("repetition_count")
    if repetition_count is not None and (not isinstance(repetition_count, int) or repetition_count < 0):
        raise ValidationError("repetition_count must be null or a non-negative integer")
    deviations = result.get("deviations", [])
    if not isinstance(deviations, list):
        raise ValidationError("deviations must be a list")
    for index, deviation in enumerate(deviations):
        if not isinstance(deviation, dict):
            raise ValidationError("each deviation must be an object")
        start_ms = deviation.get("start_ms")
        end_ms = deviation.get("end_ms")
        if not isinstance(start_ms, int) or not isinstance(end_ms, int) or start_ms < 0 or end_ms < start_ms:
            raise ValidationError(f"deviation {index} requires valid start_ms/end_ms")
        _probability(deviation.get("confidence"), f"deviation {index} confidence")
    cues = result.get("cues", [])
    if not isinstance(cues, list):
        raise ValidationError("cues must be a list")
    for index, cue in enumerate(cues):
        if not isinstance(cue, dict) or not isinstance(cue.get("timestamp_ms"), int) or not isinstance(cue.get("key"), str):
            raise ValidationError(f"cue {index} requires integer timestamp_ms and string key")
    capabilities = result.get("capabilities", {})
    if not isinstance(capabilities, dict) or any(not isinstance(value, bool) for value in capabilities.values()):
        raise ValidationError("capabilities must be a string->boolean object")
    return {
        "classification": classification,
        "confidence": confidence,
        "states": len(state_ids),
        "observations": len(observations),
        "repetition_count": repetition_count,
        "deviations": len(deviations),
        "cues": len(cues),
    }


def _distinct_state_path(result: dict[str, Any]) -> list[str]:
    path: list[str] = []
    for observation in result.get("observations", []):
        state = observation.get("state_id")
        if state is not None and (not path or state != path[-1]):
            path.append(state)
    return path


def result_metrics(result: dict[str, Any]) -> dict[str, Any]:
    validate_normalized_result(result)
    state_ids = set(result["profile"]["state_ids"])
    observed = [item.get("state_id") for item in result["observations"] if item.get("state_id") is not None]
    unique = set(observed)
    path = _distinct_state_path(result)
    legal = {tuple(edge) for edge in result["profile"]["legal_transitions"]}
    edges = list(zip(path, path[1:]))
    legal_fraction = sum(edge in legal for edge in edges) / len(edges) if edges else (1.0 if path else 0.0)
    classes = [str(item.get("classification", result["classification"])) for item in result["observations"]]
    suppressed = sum(item in {"SUPPRESSED", "UNCERTAIN"} for item in classes)
    return {
        "classification": result["classification"],
        "confidence": float(result["confidence"]),
        "state_coverage": len(unique) / len(state_ids),
        "legal_transition_fraction": legal_fraction,
        "distinct_state_path": path,
        "observation_count": len(result["observations"]),
        "suppressed_or_uncertain_fraction": suppressed / len(classes) if classes else 0.0,
        "repetition_count": result.get("repetition_count"),
        "deviation_count": len(result.get("deviations", [])),
    }


def _overlap_fraction(window: tuple[int, int], other: tuple[int, int]) -> float:
    left = max(window[0], other[0])
    right = min(window[1], other[1])
    overlap = max(0, right - left)
    span = max(1, window[1] - window[0])
    return overlap / span


def _window_observations(result: dict[str, Any], window: tuple[int, int]) -> list[dict[str, Any]]:
    return [item for item in result["observations"] if window[0] <= item["timestamp_ms"] <= window[1]]


def _cue_metrics(result: dict[str, Any]) -> dict[str, Any]:
    cues = sorted(result.get("cues", []), key=lambda item: item["timestamp_ms"])
    if not cues:
        return {"available": False}
    distinct = []
    for cue in cues:
        if not distinct or cue["key"] != distinct[-1]["key"]:
            distinct.append(cue)
    elapsed_ms = max(1, cues[-1]["timestamp_ms"] - cues[0]["timestamp_ms"])
    intervals = [b["timestamp_ms"] - a["timestamp_ms"] for a, b in zip(distinct, distinct[1:])]
    return {
        "available": True,
        "emission_count": len(cues),
        "distinct_key_segments": len(distinct),
        "key_switches_per_second": max(0, len(distinct) - 1) / (elapsed_ms / 1000.0),
        "median_key_segment_interval_ms": statistics.median(intervals) if intervals else elapsed_ms,
    }


def evaluate_results(transform_manifest: dict[str, Any], results: dict[str, dict[str, Any]]) -> dict[str, Any]:
    cases = {item["case_id"]: item for item in transform_manifest["materialized_cases"]}
    missing = sorted(set(cases) - set(results))
    if missing:
        raise ValidationError(f"missing normalized results for cases: {missing}")
    per_case: dict[str, Any] = {}
    for case_id in sorted(cases):
        validate_normalized_result(results[case_id], case_id)
        per_case[case_id] = result_metrics(results[case_id])
        per_case[case_id]["cue_stability"] = _cue_metrics(results[case_id])

    gates: list[dict[str, Any]] = []

    def gate(metric: str, value: Any, passed: bool | None, requirement: str, status: str | None = None) -> None:
        gates.append({
            "metric": metric,
            "value": value,
            "requirement": requirement,
            "status": status or ("PASS" if passed else "FAIL"),
        })

    baseline = per_case.get("self-reconstruction")
    if baseline is None:
        raise ValidationError("synthetic plan must materialize self-reconstruction")
    gate("reference_self_action", baseline["classification"], baseline["classification"] == "ACTION", "classification == ACTION")
    gate("reference_self_state_coverage", baseline["state_coverage"], baseline["state_coverage"] >= 0.90, ">= 0.90")
    gate("reference_self_legal_transition_fraction", baseline["legal_transition_fraction"], baseline["legal_transition_fraction"] >= 0.999, ">= 0.999")
    gate("reference_self_deviation_count", baseline["deviation_count"], baseline["deviation_count"] == 0, "== 0")

    offset = per_case.get("absolute-start-offset-30s")
    if offset:
        confidence_delta = abs(offset["confidence"] - baseline["confidence"])
        same_path = offset["distinct_state_path"] == baseline["distinct_state_path"]
        gate("absolute_start_offset_confidence_delta", confidence_delta, confidence_delta <= 0.05, "<= 0.05")
        gate("absolute_start_offset_state_path_equal", same_path, same_path, "true")

    tempo_ids = [case_id for case_id in per_case if case_id.startswith("tempo-")]
    if tempo_ids:
        good = 0
        details = {}
        for case_id in sorted(tempo_ids):
            metric = per_case[case_id]
            confidence_drop = baseline["confidence"] - metric["confidence"]
            passed = (
                metric["classification"] == "ACTION"
                and metric["state_coverage"] >= 0.80
                and metric["legal_transition_fraction"] >= 0.95
                and confidence_drop <= 0.15
            )
            good += int(passed)
            details[case_id] = {
                "passed": passed,
                "confidence": metric["confidence"],
                "confidence_drop": confidence_drop,
                "state_coverage": metric["state_coverage"],
                "legal_transition_fraction": metric["legal_transition_fraction"],
            }
        rate = good / len(tempo_ids)
        gate("tempo_invariance_0.5x_to_2x_pass_rate", {"rate": rate, "cases": details}, rate >= 0.80, ">= 0.80 of configured 0.5x-2x cases")

    reverse = per_case.get("reverse-direction")
    if reverse:
        confidence_margin = baseline["confidence"] - reverse["confidence"]
        discriminated = (
            reverse["classification"] != "ACTION"
            or confidence_margin >= 0.15
            or reverse["legal_transition_fraction"] < 0.80
            or reverse["deviation_count"] > 0
        )
        gate("reverse_direction_discrimination", {"baseline_confidence": baseline["confidence"], "reverse_confidence": reverse["confidence"], "confidence_margin": confidence_margin, "reverse_legal_transition_fraction": reverse["legal_transition_fraction"], "reverse_deviation_count": reverse["deviation_count"]}, discriminated, "NO_ACTION/UNCERTAIN, >=0.15 confidence margin, illegal state order, or explicit deviation")

    geometry = results.get("geometry-deviation")
    geometry_case = cases.get("geometry-deviation")
    if geometry and geometry_case:
        window_raw = geometry_case["transform"].get("window_ms")
        if window_raw:
            window = (int(window_raw[0]), int(window_raw[1]))
            detected = [item for item in geometry.get("deviations", []) if _overlap_fraction(window, (int(item["start_ms"]), int(item["end_ms"]))) >= 0.25]
            gate("injected_geometry_deviation_detection", len(detected), len(detected) > 0, ">= 1 deviation overlapping >=25% of injected window")

    missing = per_case.get("missing-repetition")
    extra = per_case.get("extra-repetition")
    if missing and baseline["repetition_count"] is not None and missing["repetition_count"] is not None:
        delta = missing["repetition_count"] - baseline["repetition_count"]
        gate("missing_repetition_delta", delta, delta == -1, "== -1 vs self reconstruction")
    elif "missing-repetition" in cases:
        gate("missing_repetition_delta", None, None, "== -1 vs self reconstruction", "NOT_AVAILABLE")
    if extra and baseline["repetition_count"] is not None and extra["repetition_count"] is not None:
        delta = extra["repetition_count"] - baseline["repetition_count"]
        gate("extra_repetition_delta", delta, delta == 1, "== +1 vs self reconstruction")
    elif "extra-repetition" in cases:
        gate("extra_repetition_delta", None, None, "== +1 vs self reconstruction", "NOT_AVAILABLE")

    hold = per_case.get("pause-hold-1200ms")
    if hold:
        rep_ok = baseline["repetition_count"] is None or hold["repetition_count"] is None or hold["repetition_count"] == baseline["repetition_count"]
        confidence_delta = baseline["confidence"] - hold["confidence"]
        passed = hold["classification"] == "ACTION" and rep_ok and confidence_delta <= 0.15
        gate("pause_hold_invariance", {"classification": hold["classification"], "confidence_drop": confidence_delta, "rep_count_equal": rep_ok}, passed, "ACTION, no rep inflation, confidence drop <= 0.15")

    occlusion_result = results.get("occlusion-suppression")
    occlusion_case = cases.get("occlusion-suppression")
    if occlusion_result and occlusion_case:
        window_raw = occlusion_case["transform"].get("window_ms")
        if window_raw:
            window = (int(window_raw[0]), int(window_raw[1]))
            observations = _window_observations(occlusion_result, window)
            suppressed = sum(
                str(item.get("classification", occlusion_result["classification"])) in {"SUPPRESSED", "UNCERTAIN"}
                for item in observations
            )
            suppression_fraction = suppressed / len(observations) if observations else 0.0
            high_confident_deviations = [item for item in occlusion_result.get("deviations", []) if float(item["confidence"]) >= 0.70 and _overlap_fraction(window, (int(item["start_ms"]), int(item["end_ms"]))) > 0]
            passed = suppression_fraction >= 0.70 and not high_confident_deviations
            gate("confidence_occlusion_suppression", {"window_observations": len(observations), "suppression_fraction": suppression_fraction, "high_confidence_deviations_in_window": len(high_confident_deviations)}, passed, ">=0.70 suppressed/uncertain in blind window and zero >=0.70-confidence deviations there")

    capabilities = results["self-reconstruction"].get("capabilities", {})
    for case_id, capability, label in (
        ("mirror-horizontal", "mirror_invariant", "mirror_invariance"),
        ("viewpoint-yaw-plus15", "viewpoint_invariant", "viewpoint_yaw_plus15_invariance"),
        ("viewpoint-yaw-minus15", "viewpoint_invariant", "viewpoint_yaw_minus15_invariance"),
    ):
        if case_id not in per_case:
            continue
        if not capabilities.get(capability, False):
            gate(label, None, None, "only gate when adapter declares capability", "SKIPPED_UNSUPPORTED")
            continue
        metric = per_case[case_id]
        confidence_delta = abs(metric["confidence"] - baseline["confidence"])
        passed = metric["classification"] == "ACTION" and metric["state_coverage"] >= 0.80 and confidence_delta <= 0.10
        gate(label, {"classification": metric["classification"], "state_coverage": metric["state_coverage"], "confidence_delta": confidence_delta}, passed, "ACTION, state coverage >=0.80, confidence delta <=0.10")

    unrelated = per_case.get("unrelated-motion")
    if unrelated:
        false_positive = unrelated["classification"] == "ACTION" and unrelated["confidence"] > 0.30
        false_positive_rate = 1.0 if false_positive else 0.0
        gate(
            "unrelated_motion_false_positive_rate",
            {
                "false_positive_rate": false_positive_rate,
                "false_positives": int(false_positive),
                "negative_cases": 1,
                "classification": unrelated["classification"],
                "confidence": unrelated["confidence"],
            },
            false_positive_rate <= 0.10,
            "false positives / unrelated negatives <= 0.10; with one synthetic negative this requires 0/1",
        )

    if capabilities.get("live_cues", False):
        live_values = [metric["cue_stability"] for metric in per_case.values() if metric["cue_stability"].get("available")]
        if live_values:
            max_switch_rate = max(float(item["key_switches_per_second"]) for item in live_values)
            gate("live_cue_key_switch_rate", max_switch_rate, max_switch_rate <= 1.0, "<= 1.0 key switches/second on offline replay; follow with real live-camera jitter test")
        else:
            gate("live_cue_key_switch_rate", None, None, "<= 1.0 key switches/second", "NOT_AVAILABLE")
    else:
        gate("live_cue_key_switch_rate", None, None, "only gate when live cue output is integrated", "STAGED")

    hard = [item for item in gates if item["status"] in {"PASS", "FAIL"}]
    return {
        "schema_version": 1,
        "protocol": PROTOCOL,
        "integration_status": "EXECUTED",
        "case_metrics": per_case,
        "gates": gates,
        "passed": bool(hard) and all(item["status"] == "PASS" for item in hard),
        "non_gating_statuses": sorted({item["status"] for item in gates if item["status"] not in {"PASS", "FAIL"}}),
    }


def evaluate_real_results(real_manifest: dict[str, Any], results: dict[str, dict[str, Any]]) -> dict[str, Any]:
    cases = {str(case["id"]): case for case in real_manifest["cases"]}
    unknown = sorted(set(results) - set(cases))
    if unknown:
        raise ValidationError(f"real result case ids are not present in the manifest: {unknown}")

    case_metrics: dict[str, Any] = {}
    missing_cases = sorted(set(cases) - set(results))
    for case_id, result in sorted(results.items()):
        validate_normalized_result(result, case_id)
        metrics = result_metrics(result)
        metrics["relation"] = cases[case_id]["relation"]
        metrics["domain"] = cases[case_id]["domain"]
        metrics["gating"] = bool(cases[case_id].get("gating", False))
        case_metrics[case_id] = metrics

    gates: list[dict[str, Any]] = []
    missing_gating = [case_id for case_id in missing_cases if cases[case_id].get("gating", False)]
    for case_id, case in sorted(cases.items()):
        if case.get("relation") != "self_control":
            continue
        metric = case_metrics.get(case_id)
        if metric is None:
            gates.append({
                "metric": "real_self_control",
                "case_id": case_id,
                "status": "NOT_AVAILABLE",
                "requirement": "ACTION, state coverage >=0.90, legal transition fraction >=0.999, zero deviations",
                "value": None,
            })
            continue
        passed = (
            metric["classification"] == "ACTION"
            and metric["state_coverage"] >= 0.90
            and metric["legal_transition_fraction"] >= 0.999
            and metric["deviation_count"] == 0
        )
        gates.append({
            "metric": "real_self_control",
            "case_id": case_id,
            "status": "PASS" if passed else "FAIL",
            "requirement": "ACTION, state coverage >=0.90, legal transition fraction >=0.999, zero deviations",
            "value": {
                "classification": metric["classification"],
                "state_coverage": metric["state_coverage"],
                "legal_transition_fraction": metric["legal_transition_fraction"],
                "deviation_count": metric["deviation_count"],
            },
        })

    negative_ids = sorted(case_id for case_id, case in cases.items() if case.get("relation") == "unrelated_negative")
    negative_metrics = [case_metrics[case_id] for case_id in negative_ids if case_id in case_metrics]
    if negative_metrics and len(negative_metrics) == len(negative_ids):
        false_positives = sum(metric["classification"] == "ACTION" and metric["confidence"] > 0.30 for metric in negative_metrics)
        false_positive_rate = false_positives / len(negative_metrics)
        gates.append({
            "metric": "real_unrelated_motion_false_positive_rate",
            "status": "PASS" if false_positive_rate <= 0.10 else "FAIL",
            "requirement": "false positives / unrelated negatives <= 0.10; current two-cricket-negative manifest requires 0/2",
            "value": {
                "false_positive_rate": false_positive_rate,
                "false_positives": false_positives,
                "negative_cases": len(negative_metrics),
            },
        })
    elif negative_ids:
        gates.append({
            "metric": "real_unrelated_motion_false_positive_rate",
            "status": "NOT_AVAILABLE",
            "requirement": "all unrelated-negative cases must be present before computing the rate",
            "value": {"available": len(negative_metrics), "required": len(negative_ids)},
        })

    hard = [gate for gate in gates if gate["status"] in {"PASS", "FAIL"}]
    return {
        "schema_version": 1,
        "protocol": PROTOCOL,
        "integration_status": "EXECUTED" if results else "STAGED",
        "complete": not missing_cases,
        "passed": not missing_gating and bool(hard) and all(gate["status"] == "PASS" for gate in hard),
        "missing_cases": missing_cases,
        "missing_gating_cases": missing_gating,
        "case_metrics": case_metrics,
        "gates": gates,
        "report_only_cases": sorted(case_id for case_id, case in cases.items() if not case.get("gating", False)),
    }


def command_evaluate_real(args: argparse.Namespace) -> dict[str, Any]:
    manifest = load_json(Path(args.manifest))
    if int(manifest.get("schema_version", 0)) != 1 or not isinstance(manifest.get("cases"), list):
        raise ValidationError("real-video manifest must be schema_version 1 with cases[]")
    results_dir = Path(args.results_dir)
    results = {}
    for case in manifest["cases"]:
        path = results_dir / f"{case['id']}.json"
        if path.is_file():
            results[str(case["id"])] = load_json(path)
    summary = evaluate_real_results(manifest, results)
    if args.output:
        write_json(Path(args.output), summary)
    return summary


def validate_real_manifest(path: Path, corpus_root_override: Path | None = None, verify_hash: bool = True) -> dict[str, Any]:
    manifest = load_json(path)
    if int(manifest.get("schema_version", 0)) != 1 or not isinstance(manifest.get("cases"), list):
        raise ValidationError("real-video manifest must be schema_version 1 with cases[]")
    corpus_root = corpus_root_override or Path(manifest["corpus_root"])
    external_path = Path(manifest["corpus_manifest"]["path"])
    expected_manifest_hash = str(manifest["corpus_manifest"]["sha256"])
    if not corpus_root.is_dir() or not external_path.is_file():
        raise ValidationError("external corpus root or authoritative manifest is unavailable")
    actual_manifest_hash = sha256(external_path)
    if actual_manifest_hash != expected_manifest_hash:
        raise ValidationError("authoritative corpus manifest hash mismatch")
    external = load_json(external_path)
    index = {item["relative_path"]: item for item in external["videos"]}
    seen: set[str] = set()
    represented_videos: set[str] = set()
    case_reports = []
    exercise_pairs: set[str] = set()
    exercise_controls: set[str] = set()
    for case in manifest["cases"]:
        case_id = str(case.get("id"))
        if not case_id or case_id in seen:
            raise ValidationError(f"duplicate/empty real-video case id: {case_id}")
        seen.add(case_id)
        files = {}
        for role in ("reference", "candidate"):
            relative = str(case[role])
            record = index.get(relative)
            local = corpus_root / relative
            if record is None or not local.is_file():
                raise ValidationError(f"case {case_id} {role} is not locked in external corpus: {relative}")
            actual = sha256(local) if verify_hash else record["sha256"]
            if actual != record["sha256"]:
                raise ValidationError(f"corpus hash mismatch: {relative}")
            represented_videos.add(relative)
            files[role] = {"relative_path": relative, "sha256": record["sha256"], "duration_ms": round(float(record["duration_sec"]) * 1000), "codec": record["video_codec"], "width": record["width"], "height": record["height"]}
        if case.get("domain") == "exercise":
            exercise = str(case["action_family"])
            if case.get("relation") == "wrong_vs_reference":
                exercise_pairs.add(exercise)
            if case.get("relation") == "self_control":
                exercise_controls.add(exercise)
        case_reports.append({"case_id": case_id, "domain": case.get("domain"), "relation": case.get("relation"), "files": files})
    required_exercises = set(manifest.get("required_exercise_families", []))
    if required_exercises and exercise_pairs != required_exercises:
        raise ValidationError(f"wrong-vs-reference exercise coverage mismatch: {sorted(exercise_pairs)} != {sorted(required_exercises)}")
    if required_exercises and exercise_controls != required_exercises:
        raise ValidationError(f"self-control exercise coverage mismatch: {sorted(exercise_controls)} != {sorted(required_exercises)}")
    if manifest.get("require_all_locked_videos", False):
        locked_videos = set(index)
        if represented_videos != locked_videos:
            missing_videos = sorted(locked_videos - represented_videos)
            extra_videos = sorted(represented_videos - locked_videos)
            raise ValidationError(f"locked corpus coverage mismatch: missing={missing_videos}, extra={extra_videos}")
    return {
        "schema_version": 1,
        "manifest": str(path.resolve()),
        "corpus_root": str(corpus_root.resolve()),
        "authoritative_manifest_sha256": actual_manifest_hash,
        "case_count": len(case_reports),
        "exercise_family_count": len(required_exercises),
        "exercise_wrong_reference_coverage": sorted(exercise_pairs),
        "exercise_self_control_coverage": sorted(exercise_controls),
        "generic_case_count": sum(item["domain"] != "exercise" for item in manifest["cases"]),
        "locked_video_count": len(index),
        "represented_video_count": len(represented_videos),
        "cases": case_reports,
        "ok": True,
    }


def _pose_coverage(path: Path) -> dict[str, Any]:
    payload = load_json(path)
    summary = validate_pose_extraction(payload)
    diagnostics = payload.get("diagnostics", {})
    sampled = int(diagnostics.get("sampledFrameCount", len(_frames(payload))))
    detected = int(diagnostics.get("detectedFrameCount", len(_frames(payload))))
    no_person = int(diagnostics.get("noPersonFrameCount", 0))
    unusable = int(diagnostics.get("unusableTrackingFrameCount", 0))
    analyzable = max(0, detected - unusable)
    return {
        **summary,
        "pose_json_sha256": sha256(path),
        "sampled_frame_count": sampled,
        "detected_frame_count": detected,
        "no_person_frame_count": no_person,
        "unusable_tracking_frame_count": unusable,
        "detection_fraction": detected / sampled if sampled else 0.0,
        "tracked_fraction": analyzable / sampled if sampled else 0.0,
    }


def summarize_pose_evidence(real_manifest_path: Path, evidence_root: Path) -> dict[str, Any]:
    manifest = load_json(real_manifest_path)
    cases = []
    for case in manifest["cases"]:
        case_id = str(case["id"])
        directory = evidence_root / case_id
        source = directory / "source_pose_extraction.json"
        reference = directory / "reference_pose_extraction.json"
        if not source.is_file() or not reference.is_file():
            cases.append({"case_id": case_id, "status": "NOT_AVAILABLE", "expected_directory": str(directory.resolve())})
            continue
        source_coverage = _pose_coverage(source)
        reference_coverage = _pose_coverage(reference)
        cases.append({
            "case_id": case_id,
            "status": "AVAILABLE",
            "source_relative_path": case["candidate"],
            "reference_relative_path": case["reference"],
            "source": source_coverage,
            "reference": reference_coverage,
            "minimum_tracked_fraction": min(source_coverage["tracked_fraction"], reference_coverage["tracked_fraction"]),
        })
    available = [item for item in cases if item["status"] == "AVAILABLE"]
    return {
        "schema_version": 1,
        "evidence_root": str(evidence_root.resolve()),
        "real_manifest": str(real_manifest_path.resolve()),
        "available_case_count": len(available),
        "case_count": len(cases),
        "minimum_tracked_fraction_across_available_cases": min((item["minimum_tracked_fraction"] for item in available), default=None),
        "cases": cases,
        "note": "Pose extraction coverage is provenance/diagnostic evidence only; it is not a generic action-model score.",
    }


def _parse_rep_window(value: str | None) -> tuple[int, int] | None:
    if not value:
        return None
    parts = value.split(":")
    if len(parts) != 2:
        raise ValidationError("--rep-window-ms must be START:END")
    left, right = int(parts[0]), int(parts[1])
    if left < 0 or right <= left:
        raise ValidationError("--rep-window-ms must satisfy 0 <= START < END")
    return left, right


def _run_adapter(executable: Path, request_path: Path) -> None:
    completed = subprocess.run([str(executable), str(request_path)], text=True, capture_output=True, check=False)
    if completed.returncode != 0:
        raise ValidationError(f"reference-action adapter failed for {request_path.name}: exit {completed.returncode}\n{completed.stderr[-4000:]}")


def execute_run(args: argparse.Namespace) -> dict[str, Any]:
    output = Path(args.output_dir).resolve()
    materialized_dir = output / "poses"
    rep_window = _parse_rep_window(args.rep_window_ms)
    transform_manifest = materialize_cases(
        Path(args.pose_json).resolve(),
        Path(args.plan).resolve(),
        materialized_dir,
        rep_window_ms=rep_window,
        unrelated_pose_path=Path(args.unrelated_pose_json).resolve() if args.unrelated_pose_json else None,
    )
    executable = Path(args.adapter_executable).resolve() if args.adapter_executable else None
    if executable is None:
        summary = {
            "schema_version": 1,
            "protocol": PROTOCOL,
            "integration_status": "STAGED",
            "transform_manifest": str((materialized_dir / "transform-manifest.json").resolve()),
            "materialized_case_count": len(transform_manifest["materialized_cases"]),
            "skipped_cases": transform_manifest["skipped_cases"],
            "metrics": [],
            "note": "No action-model scores are fabricated before a concrete adapter is supplied.",
        }
        write_json(output / "summary.json", summary)
        return summary
    if not executable.is_file():
        raise ValidationError(f"adapter executable not found: {executable}")
    results: dict[str, dict[str, Any]] = {}
    requests_dir = output / "requests"
    results_dir = output / "results"
    requests_dir.mkdir(parents=True, exist_ok=True)
    results_dir.mkdir(parents=True, exist_ok=True)
    base_pose = Path(args.pose_json).resolve()
    for item in transform_manifest["materialized_cases"]:
        case_id = item["case_id"]
        result_path = results_dir / f"{case_id}.json"
        request_path = requests_dir / f"{case_id}.json"
        request = {
            "schema_version": 1,
            "protocol": PROTOCOL,
            "mode": "reference_action_pose_compare",
            "case_id": case_id,
            "reference_pose_extraction_json": str(base_pose),
            "candidate_pose_extraction_json": item["path"],
            "result_output": str(result_path),
            "required_result_schema": RESULT_SCHEMA,
            "normalization_contract": {
                "timestamps": "integer milliseconds; source of truth",
                "state_ids": "adapter-stable IDs emitted by the compiled reference profile",
                "classification": sorted(CLASSIFICATIONS),
                "no_hidden_training": "adapter evaluates the supplied reference; harness does not request per-video fine-tuning",
            },
        }
        write_json(request_path, request)
        _run_adapter(executable, request_path)
        if not result_path.is_file():
            raise ValidationError(f"adapter succeeded without creating normalized result: {result_path}")
        result = load_json(result_path)
        validate_normalized_result(result, case_id)
        results[case_id] = result
    summary = evaluate_results(transform_manifest, results)
    summary["transform_manifest"] = str((materialized_dir / "transform-manifest.json").resolve())
    summary["skipped_cases"] = transform_manifest["skipped_cases"]
    write_json(output / "summary.json", summary)
    return summary


def command_evaluate(args: argparse.Namespace) -> dict[str, Any]:
    transform_manifest = load_json(Path(args.transform_manifest))
    results_dir = Path(args.results_dir)
    results = {}
    for item in transform_manifest["materialized_cases"]:
        path = results_dir / f"{item['case_id']}.json"
        if path.is_file():
            results[item["case_id"]] = load_json(path)
    summary = evaluate_results(transform_manifest, results)
    if args.output:
        write_json(Path(args.output), summary)
    return summary


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Generic reference-derived action validation harness")
    sub = parser.add_subparsers(dest="command", required=True)

    fixture = sub.add_parser("generate-fixture", help="write deterministic schema-compatible MediaPipe-33 pose fixture")
    fixture.add_argument("--output", required=True)
    fixture.add_argument("--frames", type=int, default=61)
    fixture.add_argument("--step-ms", type=int, default=100)

    real = sub.add_parser("manifest-check", help="validate external real-video manifest paths and hashes")
    real.add_argument("--manifest", default=str(DEFAULT_REAL_MANIFEST))
    real.add_argument("--corpus-root")
    real.add_argument("--no-hash", action="store_true")
    real.add_argument("--output")

    materialize = sub.add_parser("materialize", help="materialize deterministic pose-level perturbations without video re-encoding")
    materialize.add_argument("--pose-json", required=True)
    materialize.add_argument("--plan", default=str(DEFAULT_PLAN))
    materialize.add_argument("--output-dir", required=True)
    materialize.add_argument("--rep-window-ms", help="known one-repetition START:END window in reference timestamps")
    materialize.add_argument("--unrelated-pose-json")

    coverage = sub.add_parser("pose-coverage", help="summarize existing API35 source/reference pose extraction diagnostics")
    coverage.add_argument("--manifest", default=str(DEFAULT_REAL_MANIFEST))
    coverage.add_argument("--evidence-root", required=True)
    coverage.add_argument("--output")

    run = sub.add_parser("run", help="materialize perturbations and optionally invoke a future core adapter")
    run.add_argument("--pose-json", required=True)
    run.add_argument("--plan", default=str(DEFAULT_PLAN))
    run.add_argument("--output-dir", required=True)
    run.add_argument("--rep-window-ms")
    run.add_argument("--unrelated-pose-json")
    run.add_argument("--adapter-executable")

    evaluate = sub.add_parser("evaluate", help="evaluate normalized synthetic adapter outputs into machine-readable metrics")
    evaluate.add_argument("--transform-manifest", required=True)
    evaluate.add_argument("--results-dir", required=True)
    evaluate.add_argument("--output")

    evaluate_real = sub.add_parser("evaluate-real", help="evaluate normalized real-corpus adapter outputs by manifest relation")
    evaluate_real.add_argument("--manifest", default=str(DEFAULT_REAL_MANIFEST))
    evaluate_real.add_argument("--results-dir", required=True)
    evaluate_real.add_argument("--output")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        if args.command == "generate-fixture":
            result = generate_fixture(args.frames, args.step_ms)
            write_json(Path(args.output), result)
            report = {"output": str(Path(args.output).resolve()), "sha256": sha256(Path(args.output)), **validate_pose_extraction(result)}
        elif args.command == "manifest-check":
            report = validate_real_manifest(Path(args.manifest), Path(args.corpus_root) if args.corpus_root else None, verify_hash=not args.no_hash)
            if args.output:
                write_json(Path(args.output), report)
        elif args.command == "materialize":
            report = materialize_cases(
                Path(args.pose_json), Path(args.plan), Path(args.output_dir),
                rep_window_ms=_parse_rep_window(args.rep_window_ms),
                unrelated_pose_path=Path(args.unrelated_pose_json) if args.unrelated_pose_json else None,
            )
        elif args.command == "pose-coverage":
            report = summarize_pose_evidence(Path(args.manifest), Path(args.evidence_root))
            if args.output:
                write_json(Path(args.output), report)
        elif args.command == "run":
            report = execute_run(args)
        elif args.command == "evaluate":
            report = command_evaluate(args)
        elif args.command == "evaluate-real":
            report = command_evaluate_real(args)
        else:
            raise ValidationError(f"unsupported command {args.command}")
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0
    except (ValidationError, KeyError, ValueError, OSError, json.JSONDecodeError) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, indent=2), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
