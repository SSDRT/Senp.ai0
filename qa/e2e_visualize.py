#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
import math
import subprocess
from pathlib import Path

WIDTH = 1200
HEIGHT = 700
MARGIN = 70


def _line(pixels: bytearray, x0: int, y0: int, x1: int, y1: int, color: tuple[int, int, int], thickness: int = 2) -> None:
    dx = abs(x1 - x0)
    sx = 1 if x0 < x1 else -1
    dy = -abs(y1 - y0)
    sy = 1 if y0 < y1 else -1
    err = dx + dy
    while True:
        for yy in range(y0 - thickness, y0 + thickness + 1):
            for xx in range(x0 - thickness, x0 + thickness + 1):
                if 0 <= xx < WIDTH and 0 <= yy < HEIGHT:
                    offset = (yy * WIDTH + xx) * 3
                    pixels[offset:offset + 3] = bytes(color)
        if x0 == x1 and y0 == y1:
            return
        twice = 2 * err
        if twice >= dy:
            err += dy
            x0 += sx
        if twice <= dx:
            err += dx
            y0 += sy


def _fill_rect(pixels: bytearray, left: int, top: int, right: int, bottom: int, color: tuple[int, int, int]) -> None:
    left = max(0, left); right = min(WIDTH, right); top = max(0, top); bottom = min(HEIGHT, bottom)
    row = bytes(color) * max(0, right - left)
    for y in range(top, bottom):
        start = (y * WIDTH + left) * 3
        pixels[start:start + len(row)] = row


def _canvas() -> bytearray:
    return bytearray([250, 250, 250]) * (WIDTH * HEIGHT)


def _write_png(pixels: bytearray, output: Path, label: str) -> None:
    ppm = output.with_suffix('.ppm')
    ppm.write_bytes(f"P6\n{WIDTH} {HEIGHT}\n255\n".encode('ascii') + bytes(pixels))
    safe = label.replace("'", "_").replace(':', '_')
    command = [
        'ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i', str(ppm),
        '-vf', f"drawbox=x=0:y=0:w=iw:h=45:color=black@0.78:t=fill,drawtext=text='{safe}':x=15:y=12:fontsize=20:fontcolor=white",
        '-frames:v', '1', str(output),
    ]
    result = subprocess.run(command, text=True, capture_output=True, check=False)
    ppm.unlink(missing_ok=True)
    if result.returncode != 0:
        raise SystemExit(f"ffmpeg failed to encode {output}: {result.stderr}")


def _read_motion(path: Path) -> tuple[list[dict[str, str]], list[str]]:
    with path.open(newline='', encoding='utf-8') as handle:
        reader = csv.DictReader(handle)
        rows = list(reader)
        return rows, list(reader.fieldnames or [])


def _finite_values(rows: list[dict[str, str]], field: str) -> list[float]:
    values: list[float] = []
    for row in rows:
        raw = row.get(field, '')
        if not raw:
            continue
        try:
            value = float(raw)
        except ValueError:
            continue
        if math.isfinite(value):
            values.append(value)
    return values


def _bilateral_key(field: str) -> str:
    return field.replace('.left_', '.side_').replace('.right_', '.side_')


def _pick_feature_pair(
    source_rows: list[dict[str, str]],
    reference_rows: list[dict[str, str]],
    source_fields: list[str],
    reference_fields: list[str],
) -> tuple[str, str, str]:
    source_candidates = [field for field in source_fields if field.startswith(('angle.', 'torso.'))]
    reference_by_key: dict[str, list[str]] = {}
    for field in reference_fields:
        if field.startswith(('angle.', 'torso.')):
            reference_by_key.setdefault(_bilateral_key(field), []).append(field)

    best: tuple[int, float, str, str] | None = None
    for source_field in source_candidates:
        matches = []
        if source_field in reference_fields:
            matches.append(source_field)
        matches.extend(field for field in reference_by_key.get(_bilateral_key(source_field), []) if field not in matches)
        source_values = _finite_values(source_rows, source_field)
        if len(source_values) < 2:
            continue
        for reference_field in matches:
            reference_values = _finite_values(reference_rows, reference_field)
            if len(reference_values) < 2:
                continue
            score = (max(source_values) - min(source_values)) + (max(reference_values) - min(reference_values))
            if source_field.startswith('angle.') and source_field.endswith('.degrees'):
                priority = 3
            elif source_field.startswith('torso.') and source_field.endswith('_degrees'):
                priority = 2
            elif '.confidence' not in source_field and 'velocity_' not in source_field:
                priority = 1
            else:
                priority = 0
            candidate = (priority, score, source_field, reference_field)
            if best is None or candidate[:2] > best[:2]:
                best = candidate
    if best is None:
        raise SystemExit('No comparable finite motion feature pair is available to plot')
    _, _, source_field, reference_field = best
    label = source_field if source_field == reference_field else f'{source_field} vs {reference_field}'
    return source_field, reference_field, label


def _problem_windows(analysis_path: Path) -> list[dict]:
    payload = json.loads(analysis_path.read_text(encoding='utf-8'))
    return list(payload.get('payload', {}).get('problems', []))


def _plot_motion(artifact: Path) -> tuple[str, str, str, Path]:
    source, source_fields = _read_motion(artifact / 'source_motion_trace.csv')
    reference, reference_fields = _read_motion(artifact / 'reference_motion_trace.csv')
    source_feature, reference_feature, feature_label = _pick_feature_pair(
        source, reference, source_fields, reference_fields,
    )
    series = []
    for rows, feature in ((source, source_feature), (reference, reference_feature)):
        values = []
        for row in rows:
            raw = row.get(feature, '')
            if not raw:
                continue
            try:
                value = float(raw)
                timestamp = float(row['timestamp_ms'])
            except (ValueError, KeyError):
                continue
            if math.isfinite(value):
                values.append((timestamp, value))
        series.append(values)
    all_values = [value for values in series for _, value in values]
    all_time = [timestamp for values in series for timestamp, _ in values]
    minimum = min(all_values); maximum = max(all_values)
    if maximum <= minimum:
        maximum = minimum + 1.0
    time_max = max(all_time) if all_time else 1.0
    pixels = _canvas()
    # Problem windows appear as light vertical bands in source time.
    for window in _problem_windows(artifact / 'analysis_result_miss.json'):
        start = float(window['sourceStart'])
        end = float(window['sourceEndExclusive'])
        x0 = MARGIN + int((WIDTH - 2 * MARGIN) * start / max(1.0, time_max))
        x1 = MARGIN + int((WIDTH - 2 * MARGIN) * end / max(1.0, time_max))
        _fill_rect(pixels, x0, MARGIN, x1, HEIGHT - MARGIN, (238, 225, 225))
    _line(pixels, MARGIN, HEIGHT - MARGIN, WIDTH - MARGIN, HEIGHT - MARGIN, (40, 40, 40), 1)
    _line(pixels, MARGIN, MARGIN, MARGIN, HEIGHT - MARGIN, (40, 40, 40), 1)
    colors = ((35, 105, 190), (210, 90, 45))
    for values, color in zip(series, colors):
        previous = None
        for timestamp, value in values:
            x = MARGIN + int((WIDTH - 2 * MARGIN) * timestamp / max(1.0, time_max))
            y = HEIGHT - MARGIN - int((HEIGHT - 2 * MARGIN) * (value - minimum) / (maximum - minimum))
            if previous is not None:
                _line(pixels, previous[0], previous[1], x, y, color, 2)
            previous = (x, y)
    output = artifact / 'motion_trace.png'
    _write_png(pixels, output, f'MOTION TRACE | {feature_label} | source=blue reference=orange | shaded=problem windows')
    return source_feature, reference_feature, feature_label, output


def _plot_alignment(artifact: Path) -> Path:
    with (artifact / 'alignment_trace.csv').open(newline='', encoding='utf-8') as handle:
        rows = list(csv.DictReader(handle))
    points = [(float(row['source_ms']), float(row['reference_ms'])) for row in rows]
    if not points:
        raise SystemExit('Alignment trace contains no points')
    max_source = max(x for x, _ in points) or 1.0
    max_reference = max(y for _, y in points) or 1.0
    pixels = _canvas()
    _line(pixels, MARGIN, HEIGHT - MARGIN, WIDTH - MARGIN, HEIGHT - MARGIN, (40, 40, 40), 1)
    _line(pixels, MARGIN, MARGIN, MARGIN, HEIGHT - MARGIN, (40, 40, 40), 1)
    # Ideal duration-normalized diagonal for visual comparison.
    _line(pixels, MARGIN, HEIGHT - MARGIN, WIDTH - MARGIN, MARGIN, (180, 180, 180), 1)
    previous = None
    for source, reference in points:
        x = MARGIN + int((WIDTH - 2 * MARGIN) * source / max_source)
        y = HEIGHT - MARGIN - int((HEIGHT - 2 * MARGIN) * reference / max_reference)
        if previous is not None:
            _line(pixels, previous[0], previous[1], x, y, (30, 130, 70), 2)
        previous = (x, y)
    output = artifact / 'alignment_trace.png'
    _write_png(pixels, output, 'ALIGNMENT TRACE | x=source ms y=reference ms | gray=duration diagonal')
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description='Render Wave 5 real-run motion/alignment trace evidence')
    parser.add_argument('--artifact-dir', required=True)
    args = parser.parse_args()
    artifact = Path(args.artifact_dir).resolve()
    source_feature, reference_feature, feature_label, motion = _plot_motion(artifact)
    alignment = _plot_alignment(artifact)
    report = {
        'schema_version': 1,
        'artifact_dir': str(artifact),
        'motion_feature': feature_label,
        'source_motion_feature': source_feature,
        'reference_motion_feature': reference_feature,
        'motion_trace': str(motion),
        'alignment_trace': str(alignment),
    }
    (artifact / 'visual_trace_report.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
