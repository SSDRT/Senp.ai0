#!/usr/bin/env python3
"""Generate a deterministic four-frame MediaPipe-compatible pose fixture."""
from __future__ import annotations

import json
import math
from pathlib import Path

# Neutral standing pose coordinates for all MediaPipe Pose landmark indexes.
BASE = {
    0: (0.50, 0.13), 1: (0.485, 0.12), 2: (0.475, 0.12), 3: (0.465, 0.125),
    4: (0.515, 0.12), 5: (0.525, 0.12), 6: (0.535, 0.125), 7: (0.445, 0.14),
    8: (0.555, 0.14), 9: (0.485, 0.155), 10: (0.515, 0.155),
    11: (0.42, 0.27), 12: (0.58, 0.27), 13: (0.36, 0.42), 14: (0.64, 0.42),
    15: (0.32, 0.57), 16: (0.68, 0.57), 17: (0.30, 0.59), 18: (0.70, 0.59),
    19: (0.31, 0.58), 20: (0.69, 0.58), 21: (0.33, 0.56), 22: (0.67, 0.56),
    23: (0.455, 0.53), 24: (0.545, 0.53), 25: (0.44, 0.72), 26: (0.56, 0.72),
    27: (0.43, 0.91), 28: (0.57, 0.91), 29: (0.42, 0.94), 30: (0.58, 0.94),
    31: (0.45, 0.96), 32: (0.55, 0.96),
}


def build() -> dict:
    frames = []
    for frame_index, timestamp_ms in enumerate((0, 333, 666, 999)):
        phase = frame_index / 3.0
        landmarks = []
        for index in range(33):
            x, y = BASE[index]
            if index in {13, 15, 17, 19, 21}:
                x -= 0.07 * math.sin(math.pi * phase)
                y -= 0.08 * math.sin(math.pi * phase)
            if index in {14, 16, 18, 20, 22}:
                x += 0.07 * math.sin(math.pi * phase)
                y -= 0.08 * math.sin(math.pi * phase)
            visibility = 0.38 if frame_index == 2 and index in {29, 31} else 0.98
            landmarks.append(
                {
                    "index": index,
                    "x": round(x, 6),
                    "y": round(y, 6),
                    "z": round((0.5 - x) * 0.08, 6),
                    "visibility": visibility,
                    "presence": 0.99,
                }
            )
        frames.append({"timestamp_ms": timestamp_ms, "landmarks": landmarks})
    return {
        "schema_version": 1,
        "coordinates": "normalized",
        "image": {"width": 640, "height": 720},
        "source": {"kind": "synthetic", "generator": "generate_synthetic_pose.py"},
        "frames": frames,
    }


if __name__ == "__main__":
    destination = Path(__file__).with_name("synthetic_pose.json")
    destination.write_text(json.dumps(build(), indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(destination)
