from __future__ import annotations

import json
import math
import os
import re
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

from .common import QaError, require, run_command, utc_now, write_json
from .corpus import CorpusContext, VideoRecord, pair_records, selected_records

FONT_CANDIDATES = (
    "/usr/share/fonts/google-noto/NotoSans-Bold.ttf",
    "/usr/share/fonts/dejavu-sans-fonts/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
)


@dataclass(frozen=True)
class FramePoint:
    frame_index: int
    requested_ms: int
    actual_ms: int


def _font_path() -> str:
    override = os.environ.get("SENP_QA_FONT")
    if override and Path(override).is_file():
        return override
    for candidate in FONT_CANDIDATES:
        if Path(candidate).is_file():
            return candidate
    discovered = run_command(
        ["fc-match", "-f", "%{file}\\n", "sans:style=Bold"], timeout=10.0, check=False
    )
    if discovered.returncode == 0:
        candidate = next((line.strip() for line in discovered.stdout.splitlines() if line.strip()), "")
        if candidate and Path(candidate).is_file():
            return candidate
    raise QaError("font_missing", "No usable TrueType font found; set SENP_QA_FONT")


def _probe_frames(video: Path, ffprobe: str) -> list[float]:
    result = run_command(
        [
            ffprobe,
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "frame=best_effort_timestamp_time",
            "-of",
            "json",
            str(video),
        ],
        timeout=120.0,
    )
    try:
        payload = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise QaError("ffprobe_bad_json", f"ffprobe returned invalid JSON for {video}") from exc
    timestamps: list[float] = []
    for frame in payload.get("frames", []):
        raw = frame.get("best_effort_timestamp_time")
        if raw is None:
            continue
        try:
            timestamps.append(float(raw))
        except (TypeError, ValueError):
            continue
    require(timestamps, "no_video_frames", f"No presentation timestamps found in {video}")
    return timestamps


def representative_frames(video: Path, record: VideoRecord, sample_count: int, ffprobe: str) -> list[FramePoint]:
    require(sample_count > 0, "bad_sample_count", "sample_count must be positive")
    timestamps = _probe_frames(video, ffprobe)
    first = timestamps[0]
    last = timestamps[-1]
    duration = max(last - first, record.duration_sec, 0.001)
    if sample_count == 1:
        fractions = [0.5]
    else:
        fractions = [0.06 + index * (0.88 / (sample_count - 1)) for index in range(sample_count)]
    selected: list[FramePoint] = []
    used: set[int] = set()
    for fraction in fractions:
        requested = first + duration * fraction
        nearest = min(range(len(timestamps)), key=lambda index: abs(timestamps[index] - requested))
        if nearest in used:
            candidates = sorted(range(len(timestamps)), key=lambda index: (abs(timestamps[index] - requested), index))
            nearest = next(index for index in candidates if index not in used)
        used.add(nearest)
        selected.append(
            FramePoint(
                frame_index=nearest,
                requested_ms=int(round(requested * 1000.0)),
                actual_ms=int(round(timestamps[nearest] * 1000.0)),
            )
        )
    return selected


def _safe_text(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9 ._+/#-]", "_", value)


def _extract_frame(
    *,
    video: Path,
    output: Path,
    point: FramePoint,
    label: str,
    codec: str,
    orientation: str,
    role: str,
    ffmpeg: str,
    cell_width: int = 360,
    cell_height: int = 460,
) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    font = _font_path()
    banner_height = 72
    title_text = _safe_text(label)
    metadata_text = _safe_text(
        f"{role.upper()} | {codec.upper()} | {orientation} | frame {point.frame_index} | "
        f"requested {point.requested_ms} ms | actual {point.actual_ms} ms"
    )
    filter_value = (
        f"select=eq(n\\,{point.frame_index}),"
        f"scale={cell_width}:{cell_height-banner_height}:force_original_aspect_ratio=decrease,"
        f"pad={cell_width}:{cell_height-banner_height}:(ow-iw)/2:(oh-ih)/2:color=black,"
        f"pad={cell_width}:{cell_height}:0:{banner_height}:color=0x141414,"
        f"drawtext=fontfile='{font}':text='{title_text}':x=8:y=8:fontsize=13:fontcolor=white,"
        f"drawtext=fontfile='{font}':text='{metadata_text}':x=8:y=34:fontsize=10:fontcolor=white"
    )
    result = run_command(
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(video),
            "-vf",
            filter_value,
            "-vsync",
            "0",
            "-frames:v",
            "1",
            str(output),
        ],
        timeout=180.0,
        check=False,
    )
    if result.returncode != 0 or not output.is_file() or output.stat().st_size == 0:
        raise QaError(
            "visual_frame_generation_failed",
            f"Failed to extract frame {point.frame_index} from {video}",
            details={"stderr": result.stderr, "stdout": result.stdout, "output": str(output)},
        )


def _png_dimensions(path: Path) -> tuple[int, int]:
    data = path.read_bytes()[:24]
    require(data.startswith(b"\x89PNG\r\n\x1a\n") and len(data) >= 24, "bad_contact_sheet_input", f"Not a readable PNG: {path}")
    width = int.from_bytes(data[16:20], "big")
    height = int.from_bytes(data[20:24], "big")
    require(width > 0 and height > 0, "bad_contact_sheet_input", f"Invalid PNG dimensions: {path}")
    return width, height


def _stack_images(images: list[Path], output: Path, columns: int, ffmpeg: str) -> None:
    require(images, "no_images", "Cannot build a contact sheet from no images")
    output.parent.mkdir(parents=True, exist_ok=True)
    rows = math.ceil(len(images) / columns)
    width, height = _png_dimensions(images[0])
    for image in images[1:]:
        require(_png_dimensions(image) == (width, height), "contact_sheet_size_mismatch", "All contact-sheet cells must have identical dimensions")
    inputs: list[str] = []
    for image in images:
        inputs.extend(["-i", str(image)])
    layout_parts: list[str] = []
    for index in range(len(images)):
        col = index % columns
        row = index // columns
        layout_parts.append(f"{col * width}_{row * height}")
    filter_value = f"xstack=inputs={len(images)}:layout={'|'.join(layout_parts)}:fill=black"
    result = run_command(
        [ffmpeg, "-hide_banner", "-loglevel", "error", "-y", *inputs, "-filter_complex", filter_value, "-frames:v", "1", str(output)],
        timeout=120.0,
        check=False,
    )
    if result.returncode != 0 or not output.is_file() or output.stat().st_size == 0:
        raise QaError(
            "contact_sheet_generation_failed",
            f"Failed to compose contact sheet {output}",
            details={"stderr": result.stderr, "stdout": result.stdout, "rows": rows, "columns": columns},
        )


def _generate_selection(
    context: CorpusContext,
    selection: dict[str, Any],
    record: VideoRecord,
    output_root: Path,
    ffmpeg: str,
    ffprobe: str,
    sample_count_override: int | None = None,
) -> dict[str, Any]:
    selection_id = str(selection["id"])
    selection_root = output_root / selection_id
    if selection_root.exists():
        shutil.rmtree(selection_root)
    selection_root.mkdir(parents=True)
    video = context.root / record.relative_path
    sample_count = sample_count_override or int(selection.get("sample_count", 6))
    points = representative_frames(video, record, sample_count, ffprobe)
    frame_reports: list[dict[str, Any]] = []
    frame_paths: list[Path] = []
    for index, point in enumerate(points):
        frame_path = selection_root / f"frame-{index:02d}-{point.actual_ms:06d}ms.png"
        _extract_frame(
            video=video,
            output=frame_path,
            point=point,
            label=str(selection.get("label", selection_id)),
            codec=record.video_codec,
            orientation=record.orientation,
            role=str(selection.get("role", "sample")),
            ffmpeg=ffmpeg,
        )
        frame_paths.append(frame_path)
        frame_reports.append(
            {
                "path": str(frame_path.resolve()),
                "frame_index": point.frame_index,
                "requested_ms": point.requested_ms,
                "actual_ms": point.actual_ms,
                "timestamp_error_ms": abs(point.actual_ms - point.requested_ms),
            }
        )
    sheet_path = output_root / f"{selection_id}-contact-sheet.png"
    _stack_images(frame_paths, sheet_path, columns=3 if len(frame_paths) > 4 else 2, ffmpeg=ffmpeg)
    return {
        "id": selection_id,
        "label": selection.get("label", selection_id),
        "role": selection.get("role"),
        "exercise": selection.get("exercise"),
        "relative_path": record.relative_path,
        "codec": record.video_codec,
        "width": record.width,
        "height": record.height,
        "orientation": record.orientation,
        "duration_ms": int(round(record.duration_sec * 1000.0)),
        "frames": frame_reports,
        "contact_sheet": str(sheet_path.resolve()),
    }


def generate_visual_artifacts(
    context: CorpusContext,
    output_root: Path,
    *,
    ids: Iterable[str] | None = None,
    ffmpeg: str = "ffmpeg",
    ffprobe: str = "ffprobe",
    include_pairs: bool = True,
) -> dict[str, Any]:
    output_root = output_root.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    selected = selected_records(context, ids)
    selection_reports = [
        _generate_selection(context, selection, record, output_root, ffmpeg, ffprobe)
        for selection, record in selected
    ]
    pair_reports: list[dict[str, Any]] = []
    if include_pairs and not ids:
        for pair in pair_records(context):
            pair_id = str(pair["id"])
            count = int(pair.get("sample_count_each", 4))
            right_selection, right_record = pair["right_entry"]
            wrong_selection, wrong_record = pair["wrong_entry"]
            right_report = _generate_selection(
                context, right_selection, right_record, output_root / "pair-parts" / pair_id, ffmpeg, ffprobe, count
            )
            wrong_report = _generate_selection(
                context, wrong_selection, wrong_record, output_root / "pair-parts" / pair_id, ffmpeg, ffprobe, count
            )
            right_frames = [Path(item["path"]) for item in right_report["frames"]]
            wrong_frames = [Path(item["path"]) for item in wrong_report["frames"]]
            sheet = output_root / f"{pair_id}-pair-contact-sheet.png"
            _stack_images(right_frames + wrong_frames, sheet, columns=count, ffmpeg=ffmpeg)
            pair_reports.append(
                {
                    "id": pair_id,
                    "label": pair.get("label", pair_id),
                    "right": right_report,
                    "wrong": wrong_report,
                    "contact_sheet": str(sheet.resolve()),
                    "layout": {"columns": count, "rows": 2, "row_0": "right", "row_1": "wrong"},
                }
            )
    report = {
        "schema_version": 1,
        "generated_at": utc_now(),
        "ok": True,
        "corpus_root": str(context.root),
        "manifest": str(context.manifest_path),
        "output_root": str(output_root),
        "selections": selection_reports,
        "pairs": pair_reports,
        "coverage": {
            "h264_portrait": any(item["codec"] == "h264" and item["orientation"] == "portrait" for item in selection_reports),
            "hevc_landscape": any(item["codec"] == "hevc" and item["orientation"] == "landscape" for item in selection_reports),
            "right_wrong_pairs": len(pair_reports),
            "cricket": any(item["role"] == "cricket" for item in selection_reports),
        },
    }
    require(report["coverage"]["h264_portrait"], "visual_coverage_missing", "No H.264 portrait visual selection")
    require(report["coverage"]["hevc_landscape"], "visual_coverage_missing", "No HEVC landscape visual selection")
    if include_pairs and not ids:
        require(len(pair_reports) >= 2, "visual_coverage_missing", "At least two right/wrong pair sheets are required")
    require(report["coverage"]["cricket"], "visual_coverage_missing", "No cricket visual selection")
    write_json(output_root / "visual-report.json", report)
    return report
