from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

from .common import QaError, load_json, require, sha256_file, utc_now

DEFAULT_LOCK_PATH = Path(__file__).resolve().parents[1] / "corpus.lock.json"


@dataclass(frozen=True)
class VideoRecord:
    relative_path: str
    bytes: int
    sha256: str
    duration_sec: float
    video_codec: str
    width: int
    height: int
    avg_frame_rate: str
    pixel_format: str

    @property
    def orientation(self) -> str:
        if self.height > self.width:
            return "portrait"
        if self.width > self.height:
            return "landscape"
        return "square"

    @classmethod
    def from_json(cls, value: dict[str, Any]) -> "VideoRecord":
        path = value.get("relative_path", value.get("path"))
        require(isinstance(path, str) and path, "bad_manifest", "Video record lacks relative_path")
        required = ("bytes", "sha256", "duration_sec", "video_codec", "width", "height")
        missing = [key for key in required if key not in value]
        require(not missing, "bad_manifest", f"Video record {path!r} is missing fields: {missing}")
        return cls(
            relative_path=path,
            bytes=int(value["bytes"]),
            sha256=str(value["sha256"]),
            duration_sec=float(value["duration_sec"]),
            video_codec=str(value["video_codec"]),
            width=int(value["width"]),
            height=int(value["height"]),
            avg_frame_rate=str(value.get("avg_frame_rate", "0/1")),
            pixel_format=str(value.get("pixel_format", "unknown")),
        )


@dataclass(frozen=True)
class CorpusContext:
    lock_path: Path
    lock: dict[str, Any]
    manifest_path: Path
    root: Path
    records: tuple[VideoRecord, ...]

    @property
    def by_path(self) -> dict[str, VideoRecord]:
        return {record.relative_path: record for record in self.records}


def _resolved_path(raw: str, *, env_name: str, base: Path) -> Path:
    override = os.environ.get(env_name)
    candidate = Path(override if override else raw).expanduser()
    if not candidate.is_absolute():
        candidate = (base / candidate).resolve()
    return candidate


def load_context(
    lock_path: Path = DEFAULT_LOCK_PATH,
    *,
    manifest_path: Path | None = None,
    root: Path | None = None,
    verify_manifest_hash: bool = True,
) -> CorpusContext:
    lock_path = lock_path.resolve()
    lock = load_json(lock_path)
    require(isinstance(lock, dict), "bad_lock", f"Corpus lock must contain an object: {lock_path}")
    external = lock.get("external_manifest")
    require(isinstance(external, dict), "bad_lock", "corpus.lock.json lacks external_manifest")

    resolved_manifest = manifest_path or _resolved_path(
        str(external.get("default_path", "")), env_name="SENP_CORPUS_MANIFEST", base=lock_path.parent
    )
    if not resolved_manifest.exists():
        raise QaError(
            "missing_manifest",
            f"External corpus manifest is missing: {resolved_manifest}",
            details={"hint": "Set SENP_CORPUS_MANIFEST to the downloaded manifest path."},
        )
    expected_manifest_hash = str(external.get("sha256", ""))
    if verify_manifest_hash and expected_manifest_hash:
        actual_manifest_hash = sha256_file(resolved_manifest)
        if actual_manifest_hash != expected_manifest_hash:
            raise QaError(
                "manifest_hash_mismatch",
                f"External manifest changed: {resolved_manifest}",
                details={"expected": expected_manifest_hash, "actual": actual_manifest_hash},
            )

    manifest = load_json(resolved_manifest)
    require(isinstance(manifest, dict), "bad_manifest", "External manifest must contain an object")
    raw_records = manifest.get("videos")
    require(isinstance(raw_records, list), "bad_manifest", "External manifest lacks videos[]")
    records = tuple(VideoRecord.from_json(value) for value in raw_records)
    paths = [record.relative_path for record in records]
    require(len(paths) == len(set(paths)), "bad_manifest", "External manifest contains duplicate relative paths")

    expected_count = int(lock.get("expected_file_count", len(records)))
    expected_total_bytes = int(lock.get("expected_total_bytes", sum(record.bytes for record in records)))
    require(
        len(records) == expected_count,
        "manifest_count_mismatch",
        f"Expected {expected_count} videos but manifest contains {len(records)}",
    )
    total_bytes = sum(record.bytes for record in records)
    require(
        total_bytes == expected_total_bytes,
        "manifest_size_mismatch",
        f"Expected manifest total {expected_total_bytes} bytes but found {total_bytes}",
    )

    manifest_root = Path(str(manifest.get("root", ""))).expanduser()
    resolved_root = root or Path(os.environ.get("SENP_CORPUS_ROOT", str(manifest_root))).expanduser()
    if not resolved_root.is_absolute():
        resolved_root = (resolved_manifest.parent / resolved_root).resolve()

    return CorpusContext(lock_path, lock, resolved_manifest.resolve(), resolved_root.resolve(), records)


def selected_records(context: CorpusContext, ids: Iterable[str] | None = None) -> list[tuple[dict[str, Any], VideoRecord]]:
    selections = context.lock.get("selections")
    require(isinstance(selections, list), "bad_lock", "corpus.lock.json lacks selections[]")
    requested = set(ids or [])
    available_ids = {str(item.get("id")) for item in selections if isinstance(item, dict)}
    unknown = sorted(requested - available_ids)
    require(not unknown, "unknown_selection", f"Unknown corpus selection ids: {unknown}")
    by_path = context.by_path
    result: list[tuple[dict[str, Any], VideoRecord]] = []
    for item in selections:
        require(isinstance(item, dict), "bad_lock", "Each selection must be an object")
        selection_id = str(item.get("id", ""))
        if requested and selection_id not in requested:
            continue
        relative_path = str(item.get("relative_path", ""))
        require(relative_path in by_path, "selection_missing", f"Selection path is absent from manifest: {relative_path}")
        record = by_path[relative_path]
        for key, actual in (
            ("video_codec", record.video_codec),
            ("orientation", record.orientation),
        ):
            expected = item.get(key)
            if expected is not None:
                require(
                    str(expected) == actual,
                    "selection_metadata_mismatch",
                    f"Selection {selection_id} expected {key}={expected}, manifest reports {actual}",
                )
        result.append((item, record))
    return result


def pair_records(context: CorpusContext) -> list[dict[str, Any]]:
    pairs = context.lock.get("pairs")
    require(isinstance(pairs, list), "bad_lock", "corpus.lock.json lacks pairs[]")
    selections = {str(item[0].get("id")): item for item in selected_records(context)}
    result: list[dict[str, Any]] = []
    for pair in pairs:
        require(isinstance(pair, dict), "bad_lock", "Each pair must be an object")
        right_id = str(pair.get("right", ""))
        wrong_id = str(pair.get("wrong", ""))
        require(right_id in selections and wrong_id in selections, "bad_pair", f"Pair references unknown ids: {pair}")
        result.append({**pair, "right_entry": selections[right_id], "wrong_entry": selections[wrong_id]})
    return result


def validate_corpus(
    context: CorpusContext,
    *,
    selected_only: bool = False,
    ids: Iterable[str] | None = None,
    hash_files: bool = True,
) -> dict[str, Any]:
    if not context.root.is_dir():
        raise QaError(
            "missing_corpus_root",
            f"Corpus root is missing or not a directory: {context.root}",
            details={"hint": "Set SENP_CORPUS_ROOT to the external 21-video corpus."},
        )
    if selected_only:
        records = [record for _, record in selected_records(context, ids)]
    else:
        records = list(context.records)
    failures: list[dict[str, Any]] = []
    verified: list[dict[str, Any]] = []
    for record in records:
        path = context.root / record.relative_path
        if not path.is_file():
            failures.append({"code": "missing_file", "relative_path": record.relative_path, "path": str(path)})
            continue
        actual_bytes = path.stat().st_size
        if actual_bytes != record.bytes:
            failures.append(
                {
                    "code": "size_mismatch",
                    "relative_path": record.relative_path,
                    "expected": record.bytes,
                    "actual": actual_bytes,
                }
            )
            continue
        actual_hash = sha256_file(path) if hash_files else None
        if hash_files and actual_hash != record.sha256:
            failures.append(
                {
                    "code": "hash_mismatch",
                    "relative_path": record.relative_path,
                    "expected": record.sha256,
                    "actual": actual_hash,
                }
            )
            continue
        verified.append(
            {
                "relative_path": record.relative_path,
                "bytes": actual_bytes,
                "sha256": actual_hash,
                "codec": record.video_codec,
                "orientation": record.orientation,
            }
        )
    report = {
        "schema_version": 1,
        "generated_at": utc_now(),
        "ok": not failures,
        "manifest": str(context.manifest_path),
        "manifest_sha256": sha256_file(context.manifest_path),
        "root": str(context.root),
        "selected_only": selected_only,
        "checked_count": len(records),
        "verified_count": len(verified),
        "failures": failures,
        "verified": verified,
    }
    if failures:
        raise QaError("corpus_validation_failed", f"Corpus validation failed for {len(failures)} file(s)", details=report)
    return report
