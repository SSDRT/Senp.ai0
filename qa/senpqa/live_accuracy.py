"""Score exact timestamp-matched independent 2D labels; missed joints remain PCK failures.

Truth: {width, height, annotation_source, frames: [{timestamp_ms, person_present,
torso_length_px, landmarks: [{id (MP33), x, y (normalized), visible (bool)}]}]}.
Prediction frames: {timestamp_ms, status, raw_status, raw: [...], filtered: [...]}.
Do not use upstream expected model predictions as independent truth.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path


def _index(items, key):
    result = {}
    for item in items:
        value = item[key]
        if value in result:
            raise ValueError(f"Duplicate {key}: {value}")
        result[value] = item
    return result


def _point(joint, width, height):
    point = joint["x"] * width, joint["y"] * height
    if not all(math.isfinite(value) for value in point):
        raise ValueError("Non-finite label or prediction")
    return point


def _angle(points):
    a, b, c = points
    u, v = (a[0] - b[0], a[1] - b[1]), (c[0] - b[0], c[1] - b[1])
    norm = math.hypot(*u) * math.hypot(*v)
    if norm <= 1e-9:
        return None
    return math.degrees(math.acos(max(-1.0, min(1.0, (u[0] * v[0] + u[1] * v[1]) / norm))))


def score(truth, predictions, channel="filtered"):
    if channel not in ("raw", "filtered"):
        raise ValueError("channel must be raw or filtered")
    if not truth.get("annotation_source"):
        raise ValueError("Independent annotation_source is required")
    width, height = truth["width"], truth["height"]
    if width <= 0 or height <= 0:
        raise ValueError("width and height must be positive")
    ground = _index(truth["frames"], "timestamp_ms")
    predicted = _index(predictions["frames"], "timestamp_ms")
    total = available = hit05 = hit10 = absent = false_valid = missing = 0
    angle_errors = []
    for timestamp, label in ground.items():
        frame = predicted.get(timestamp)
        if frame is None:
            missing += 1
        status_key = "raw_status" if channel == "raw" else "status"
        accepted = frame is not None and frame.get(status_key) in ("VALID", "REPAIRED")
        if not label["person_present"]:
            absent += 1
            false_valid += int(accepted)
            continue
        scale = label["torso_length_px"]
        if not math.isfinite(scale) or scale <= 0:
            raise ValueError("Positive finite independently labelled torso_length_px required")
        labels = _index(label["landmarks"], "id")
        joints = _index(frame.get(channel, []), "id") if accepted else {}
        for identity, joint in labels.items():
            if not isinstance(joint["visible"], bool):
                raise ValueError("visible must be a boolean")
            if not joint["visible"]:
                continue
            total += 1
            expected = _point(joint, width, height)
            actual = joints.get(identity)
            if actual is None or min(actual.get("visibility") or 0, actual.get("presence") or 0) < 0.5:
                continue
            position = _point(actual, width, height)
            available += 1
            distance = math.dist(expected, position) / scale
            hit05 += int(distance <= 0.05)
            hit10 += int(distance <= 0.1)
        for triplet in ((11, 13, 15), (12, 14, 16), (23, 25, 27), (24, 26, 28)):
            if not all(i in labels and labels[i]["visible"] and i in joints and min(joints[i].get("visibility") or 0, joints[i].get("presence") or 0) >= 0.5 for i in triplet):
                continue
            expected = _angle([_point(labels[i], width, height) for i in triplet])
            actual = _angle([_point(joints[i], width, height) for i in triplet])
            if expected is not None and actual is not None:
                angle_errors.append(abs(expected - actual))
    return {
        "annotation_source": truth["annotation_source"], "channel": channel,
        "labelled_frames": len(ground), "missing_prediction_frames": missing,
        "visible_labelled_joints": total, "accepted_visible_joints": available,
        "joint_coverage": available / total if total else None,
        "pck_005_all_visible": hit05 / total if total else None,
        "pck_010_all_visible": hit10 / total if total else None,
        "no_person_frames": absent, "false_valid_frames": false_valid,
        "false_valid_rate": false_valid / absent if absent else None,
        "measured_angles": len(angle_errors),
        "angle_mae_degrees": sum(angle_errors) / len(angle_errors) if angle_errors else None,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--truth", type=Path, required=True)
    parser.add_argument("--predictions", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    truth = json.loads(args.truth.read_text(encoding="utf-8"))
    predictions = json.loads(args.predictions.read_text(encoding="utf-8"))
    report = {channel: score(truth, predictions, channel) for channel in ("raw", "filtered")}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
