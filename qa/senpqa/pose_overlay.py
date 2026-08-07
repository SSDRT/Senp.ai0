from __future__ import annotations

import shutil
import tempfile
from pathlib import Path
from typing import Any

from .common import QaError, load_json, require, run_command, utc_now, write_json
from .visuals import _stack_images

# MediaPipe Pose topology. All 33 landmarks remain represented; face points are
# intentionally connected sparsely to keep diagnostics readable.
POSE_CONNECTIONS: tuple[tuple[int, int], ...] = (
    (0, 1), (1, 2), (2, 3), (3, 7),
    (0, 4), (4, 5), (5, 6), (6, 8),
    (9, 10),
    (11, 12),
    (11, 13), (13, 15), (15, 17), (15, 19), (15, 21), (17, 19),
    (12, 14), (14, 16), (16, 18), (16, 20), (16, 22), (18, 20),
    (11, 23), (12, 24), (23, 24),
    (23, 25), (25, 27), (27, 29), (29, 31), (27, 31),
    (24, 26), (26, 28), (28, 30), (30, 32), (28, 32),
)


def validate_pose_payload(payload: Any) -> dict[str, Any]:
    require(isinstance(payload, dict), "bad_pose_json", "Pose JSON must contain an object")
    require(int(payload.get("schema_version", 0)) == 1, "bad_pose_json", "Pose schema_version must be 1")
    image = payload.get("image")
    require(isinstance(image, dict), "bad_pose_json", "Pose JSON lacks image object")
    width = int(image.get("width", 0))
    height = int(image.get("height", 0))
    require(width > 0 and height > 0, "bad_pose_json", "Pose image dimensions must be positive")
    coordinates = str(payload.get("coordinates", "normalized"))
    require(coordinates in {"normalized", "pixels"}, "bad_pose_json", "coordinates must be normalized or pixels")
    frames = payload.get("frames")
    require(isinstance(frames, list) and frames, "bad_pose_json", "Pose JSON requires at least one frame")
    previous_timestamp = -1
    normalized_frames: list[dict[str, Any]] = []
    for frame_number, frame in enumerate(frames):
        require(isinstance(frame, dict), "bad_pose_json", f"Pose frame {frame_number} must be an object")
        timestamp_ms = int(frame.get("timestamp_ms", -1))
        require(timestamp_ms >= 0, "bad_pose_json", f"Pose frame {frame_number} has invalid timestamp_ms")
        require(timestamp_ms > previous_timestamp, "bad_pose_json", "Pose frame timestamps must be strictly increasing")
        previous_timestamp = timestamp_ms
        landmarks = frame.get("landmarks")
        require(isinstance(landmarks, list), "bad_pose_json", f"Pose frame {frame_number} lacks landmarks[]")
        require(len(landmarks) == 33, "bad_pose_json", f"Pose frame {frame_number} must contain all 33 landmarks")
        by_index: dict[int, dict[str, Any]] = {}
        for raw in landmarks:
            require(isinstance(raw, dict), "bad_pose_json", "Each landmark must be an object")
            index = int(raw.get("index", -1))
            require(0 <= index < 33, "bad_pose_json", f"Landmark index out of range: {index}")
            require(index not in by_index, "bad_pose_json", f"Duplicate landmark index: {index}")
            try:
                x = float(raw["x"])
                y = float(raw["y"])
                z = float(raw.get("z", 0.0))
                visibility = float(raw.get("visibility", 1.0))
                presence = float(raw.get("presence", 1.0))
            except (KeyError, TypeError, ValueError) as exc:
                raise QaError("bad_pose_json", f"Landmark {index} has non-numeric coordinates") from exc
            if coordinates == "normalized":
                require(-0.25 <= x <= 1.25 and -0.25 <= y <= 1.25, "bad_pose_json", f"Normalized landmark {index} is implausibly out of range")
            require(0.0 <= visibility <= 1.0, "bad_pose_json", f"visibility out of range for landmark {index}")
            require(0.0 <= presence <= 1.0, "bad_pose_json", f"presence out of range for landmark {index}")
            by_index[index] = {
                "index": index,
                "x": x,
                "y": y,
                "z": z,
                "visibility": visibility,
                "presence": presence,
            }
        require(set(by_index) == set(range(33)), "bad_pose_json", f"Pose frame {frame_number} does not contain indexes 0..32")
        normalized_frames.append({"timestamp_ms": timestamp_ms, "landmarks": [by_index[index] for index in range(33)]})
    return {
        "schema_version": 1,
        "coordinates": coordinates,
        "image": {"width": width, "height": height},
        "frames": normalized_frames,
        "source": payload.get("source", {}),
    }


def _blank_rgb(width: int, height: int) -> bytearray:
    pixels = bytearray(width * height * 3)
    for y in range(height):
        for x in range(width):
            offset = (y * width + x) * 3
            shade = 32 + int(18 * y / max(1, height - 1))
            pixels[offset:offset + 3] = bytes((shade, shade, shade))
    return pixels


def _parse_ppm(data: bytes) -> tuple[int, int, bytearray]:
    require(data.startswith(b"P6"), "background_decode_failed", "ffmpeg did not return a P6 PPM image")
    position = 2
    tokens: list[bytes] = []
    while len(tokens) < 3:
        while position < len(data) and data[position] in b" \t\r\n":
            position += 1
        if position < len(data) and data[position] == ord("#"):
            while position < len(data) and data[position] not in b"\r\n":
                position += 1
            continue
        start = position
        while position < len(data) and data[position] not in b" \t\r\n":
            position += 1
        tokens.append(data[start:position])
    width, height, maximum = (int(token) for token in tokens)
    require(maximum == 255, "background_decode_failed", "Only 8-bit PPM backgrounds are supported")
    while position < len(data) and data[position] in b" \t\r\n":
        position += 1
    pixels = bytearray(data[position:])
    require(len(pixels) == width * height * 3, "background_decode_failed", "PPM pixel payload has wrong size")
    return width, height, pixels


def _decode_background(background: Path, width: int, height: int, ffmpeg: str) -> bytearray:
    # Decode through a temporary PPM because the common command helper is text-only.
    with tempfile.TemporaryDirectory(prefix="senp-pose-bg-") as temp_dir:
        ppm = Path(temp_dir) / "background.ppm"
        conversion = run_command(
            [
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                str(background),
                "-vf",
                f"scale={width}:{height}:force_original_aspect_ratio=decrease,pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color=0x202020",
                "-frames:v",
                "1",
                str(ppm),
            ],
            timeout=120.0,
            check=False,
        )
        if conversion.returncode != 0 or not ppm.is_file():
            raise QaError("background_decode_failed", f"Failed to decode background {background}", details=conversion.stderr)
        _, _, pixels = _parse_ppm(ppm.read_bytes())
        return pixels


def _set_pixel(pixels: bytearray, width: int, height: int, x: int, y: int, color: tuple[int, int, int]) -> None:
    if 0 <= x < width and 0 <= y < height:
        offset = (y * width + x) * 3
        pixels[offset:offset + 3] = bytes(color)


def _draw_disk(pixels: bytearray, width: int, height: int, cx: int, cy: int, radius: int, color: tuple[int, int, int]) -> None:
    radius_sq = radius * radius
    for y in range(cy - radius, cy + radius + 1):
        for x in range(cx - radius, cx + radius + 1):
            if (x - cx) ** 2 + (y - cy) ** 2 <= radius_sq:
                _set_pixel(pixels, width, height, x, y, color)


def _draw_line(
    pixels: bytearray,
    width: int,
    height: int,
    start: tuple[int, int],
    end: tuple[int, int],
    color: tuple[int, int, int],
    thickness: int = 3,
) -> None:
    x0, y0 = start
    x1, y1 = end
    dx = abs(x1 - x0)
    sx = 1 if x0 < x1 else -1
    dy = -abs(y1 - y0)
    sy = 1 if y0 < y1 else -1
    error = dx + dy
    while True:
        _draw_disk(pixels, width, height, x0, y0, max(1, thickness // 2), color)
        if x0 == x1 and y0 == y1:
            break
        doubled = 2 * error
        if doubled >= dy:
            error += dy
            x0 += sx
        if doubled <= dx:
            error += dx
            y0 += sy


def _write_ppm(path: Path, width: int, height: int, pixels: bytearray) -> None:
    path.write_bytes(f"P6\n{width} {height}\n255\n".encode("ascii") + bytes(pixels))


def _landmark_pixel(landmark: dict[str, Any], coordinates: str, width: int, height: int) -> tuple[int, int]:
    if coordinates == "normalized":
        x = int(round(float(landmark["x"]) * (width - 1)))
        y = int(round(float(landmark["y"]) * (height - 1)))
    else:
        x = int(round(float(landmark["x"])))
        y = int(round(float(landmark["y"])))
    return x, y


def render_pose_overlays(
    pose_path: Path,
    output_root: Path,
    *,
    background: Path | None = None,
    ffmpeg: str = "ffmpeg",
) -> dict[str, Any]:
    payload = validate_pose_payload(load_json(pose_path))
    width = int(payload["image"]["width"])
    height = int(payload["image"]["height"])
    output_root = output_root.resolve()
    if output_root.exists():
        shutil.rmtree(output_root)
    output_root.mkdir(parents=True)
    background_pixels = _decode_background(background, width, height, ffmpeg) if background else _blank_rgb(width, height)
    overlays: list[dict[str, Any]] = []
    overlay_paths: list[Path] = []
    for frame_number, frame in enumerate(payload["frames"]):
        pixels = bytearray(background_pixels)
        landmarks = frame["landmarks"]
        points = [_landmark_pixel(item, payload["coordinates"], width, height) for item in landmarks]
        for start_index, end_index in POSE_CONNECTIONS:
            confidence = min(
                float(landmarks[start_index]["visibility"]),
                float(landmarks[start_index]["presence"]),
                float(landmarks[end_index]["visibility"]),
                float(landmarks[end_index]["presence"]),
            )
            color = (46, 232, 124) if confidence >= 0.5 else (120, 120, 120)
            _draw_line(pixels, width, height, points[start_index], points[end_index], color, 4)
        for index, point in enumerate(points):
            confidence = min(float(landmarks[index]["visibility"]), float(landmarks[index]["presence"]))
            color = (255, 224, 76) if confidence >= 0.5 else (180, 80, 80)
            _draw_disk(pixels, width, height, point[0], point[1], 5 if index >= 11 else 3, color)
        ppm = output_root / f"overlay-{frame_number:02d}-{int(frame['timestamp_ms']):06d}ms.ppm"
        png = output_root / f"overlay-{frame_number:02d}-{int(frame['timestamp_ms']):06d}ms.png"
        _write_ppm(ppm, width, height, pixels)
        label = f"33-LANDMARK DIAGNOSTIC | frame {frame_number} | {int(frame['timestamp_ms'])} ms"
        result = run_command(
            [
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                str(ppm),
                "-vf",
                f"drawbox=x=0:y=0:w=iw:h=44:color=black@0.75:t=fill,drawtext=text='{label}':x=10:y=12:fontsize=18:fontcolor=white",
                "-frames:v",
                "1",
                str(png),
            ],
            timeout=60.0,
            check=False,
        )
        ppm.unlink(missing_ok=True)
        if result.returncode != 0 or not png.is_file():
            raise QaError("overlay_generation_failed", f"Failed to encode overlay frame {frame_number}", details=result.stderr)
        overlay_paths.append(png)
        visible_count = sum(
            1
            for landmark in landmarks
            if min(float(landmark["visibility"]), float(landmark["presence"])) >= 0.5
        )
        overlays.append(
            {
                "path": str(png.resolve()),
                "timestamp_ms": int(frame["timestamp_ms"]),
                "landmark_count": 33,
                "visible_landmark_count": visible_count,
            }
        )
    sheet = output_root / "pose-overlay-contact-sheet.png"
    _stack_images(overlay_paths, sheet, columns=2 if len(overlay_paths) > 1 else 1, ffmpeg=ffmpeg)
    report = {
        "schema_version": 1,
        "generated_at": utc_now(),
        "ok": True,
        "pose_json": str(pose_path.resolve()),
        "background": str(background.resolve()) if background else None,
        "coordinates": payload["coordinates"],
        "image": payload["image"],
        "frames": overlays,
        "contact_sheet": str(sheet.resolve()),
        "connection_count": len(POSE_CONNECTIONS),
    }
    write_json(output_root / "overlay-report.json", report)
    return report
