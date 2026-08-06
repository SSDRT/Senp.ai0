from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from senpqa.common import QaError
from senpqa.corpus import load_context, validate_corpus


class CorpusTests(unittest.TestCase):
    def load_test_context(self, lock: Path, directory: Path):
        return load_context(
            lock,
            manifest_path=directory / "manifest.json",
            root=directory / "corpus",
        )

    def make_corpus(self, directory: Path) -> tuple[Path, Path, Path]:
        root = directory / "corpus"
        root.mkdir()
        video = root / "sample.mp4"
        video.write_bytes(b"deterministic-video-placeholder")
        digest = hashlib.sha256(video.read_bytes()).hexdigest()
        manifest = {
            "root": str(root),
            "file_count": 1,
            "total_bytes": video.stat().st_size,
            "videos": [
                {
                    "relative_path": "sample.mp4",
                    "bytes": video.stat().st_size,
                    "sha256": digest,
                    "duration_sec": 1.0,
                    "video_codec": "h264",
                    "width": 320,
                    "height": 640,
                    "avg_frame_rate": "30/1",
                    "pixel_format": "yuv420p",
                }
            ],
        }
        manifest_path = directory / "manifest.json"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        manifest_hash = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
        lock = {
            "schema_version": 1,
            "external_manifest": {"default_path": str(manifest_path), "sha256": manifest_hash},
            "expected_file_count": 1,
            "expected_total_bytes": video.stat().st_size,
            "selections": [
                {
                    "id": "sample",
                    "relative_path": "sample.mp4",
                    "video_codec": "h264",
                    "orientation": "portrait",
                }
            ],
            "pairs": [],
        }
        lock_path = directory / "corpus.lock.json"
        lock_path.write_text(json.dumps(lock), encoding="utf-8")
        return lock_path, root, video

    def test_valid_corpus(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            lock, _, _ = self.make_corpus(Path(temp))
            report = validate_corpus(self.load_test_context(lock, Path(temp)))
            self.assertTrue(report["ok"])
            self.assertEqual(report["verified_count"], 1)

    def test_missing_corpus_file_fails_clearly(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            lock, _, video = self.make_corpus(Path(temp))
            video.unlink()
            with self.assertRaises(QaError) as caught:
                validate_corpus(self.load_test_context(lock, Path(temp)))
            self.assertEqual(caught.exception.code, "corpus_validation_failed")
            self.assertEqual(caught.exception.details["failures"][0]["code"], "missing_file")

    def test_hash_mismatch_fails_clearly(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            lock, _, video = self.make_corpus(Path(temp))
            video.write_bytes(b"tampered-video-placeholder!!!")
            # Keep byte length stable so the failure is specifically the hash gate.
            expected_size = json.loads(Path(lock).read_text())["expected_total_bytes"]
            data = video.read_bytes()[:expected_size].ljust(expected_size, b"!")
            video.write_bytes(data)
            with self.assertRaises(QaError) as caught:
                validate_corpus(self.load_test_context(lock, Path(temp)))
            self.assertEqual(caught.exception.details["failures"][0]["code"], "hash_mismatch")

    def test_manifest_hash_mismatch_fails_before_media_access(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            lock, _, _ = self.make_corpus(Path(temp))
            value = json.loads(Path(lock).read_text())
            value["external_manifest"]["sha256"] = "0" * 64
            Path(lock).write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaises(QaError) as caught:
                self.load_test_context(lock, Path(temp))
            self.assertEqual(caught.exception.code, "manifest_hash_mismatch")


if __name__ == "__main__":
    unittest.main()
