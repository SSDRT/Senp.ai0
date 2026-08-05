from __future__ import annotations

import base64
import json
import tempfile
import unittest
from pathlib import Path

from senpqa.common import QaError, load_json
from senpqa.pose_overlay import validate_pose_payload
from senpqa.visuals import _stack_images

PNG_1X1 = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
)


class PoseVisualTests(unittest.TestCase):
    def test_synthetic_pose_has_all_33_landmarks(self) -> None:
        fixture = Path(__file__).resolve().parents[1] / "fixtures" / "pose" / "synthetic_pose.json"
        payload = validate_pose_payload(load_json(fixture))
        self.assertEqual(len(payload["frames"]), 4)
        self.assertTrue(all(len(frame["landmarks"]) == 33 for frame in payload["frames"]))
        self.assertEqual([frame["timestamp_ms"] for frame in payload["frames"]], [0, 333, 666, 999])

    def test_bad_json_reports_bad_json(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "bad.json"
            path.write_text("{not-json", encoding="utf-8")
            with self.assertRaises(QaError) as caught:
                load_json(path)
            self.assertEqual(caught.exception.code, "bad_json")

    def test_missing_landmark_is_rejected(self) -> None:
        fixture = Path(__file__).resolve().parents[1] / "fixtures" / "pose" / "synthetic_pose.json"
        payload = load_json(fixture)
        payload["frames"][0]["landmarks"].pop()
        with self.assertRaises(QaError) as caught:
            validate_pose_payload(payload)
        self.assertEqual(caught.exception.code, "bad_pose_json")

    def test_contact_sheet_generation_failure_is_nonzero_error(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            image = root / "cell.png"
            image.write_bytes(PNG_1X1)
            with self.assertRaises(QaError) as caught:
                _stack_images([image], root / "sheet.png", columns=1, ffmpeg="false")
            self.assertEqual(caught.exception.code, "contact_sheet_generation_failed")


if __name__ == "__main__":
    unittest.main()
