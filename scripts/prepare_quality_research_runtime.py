"""Disable optional drawing exports in the isolated research venv only.

MediaPipe 0.10.32 eagerly imports OpenCV drawing helpers. Extraction uses FFmpeg
and Pillow instead. This patch changes no inference implementation or weights.
Run with the isolated venv's Python, after installing the pinned MediaPipe wheel.
"""
import hashlib
import importlib.metadata
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'test-artifacts/exercise-knowledge'
prefix = Path(sys.prefix).resolve()
if not prefix.is_relative_to(DATA.resolve()) or sys.prefix == sys.base_prefix:
    raise RuntimeError('Only an isolated venv inside test-artifacts/exercise-knowledge may be patched')
if importlib.metadata.version('mediapipe') != '0.10.32':
    raise RuntimeError('Expected pinned MediaPipe 0.10.32')
target = prefix / 'Lib/site-packages/mediapipe/tasks/python/vision/__init__.py'
backup = target.with_suffix('.py.original')
if not backup.exists():
    backup.write_bytes(target.read_bytes())
original = backup.read_bytes()
removed = ['import mediapipe.tasks.python.vision.drawing_styles',
           'import mediapipe.tasks.python.vision.drawing_utils',
           'drawing_styles = drawing_styles', 'drawing_utils = drawing_utils']
lines = original.decode().splitlines()
if any(lines.count(line) != 1 for line in removed):
    raise RuntimeError('Unexpected upstream initializer; refuse to patch')
modified = ('\n'.join(line for line in lines if line not in removed) + '\n').encode()
target.write_bytes(modified)
report = {'mediapipe': '0.10.32', 'change': 'Omit four optional drawing exports; inference modules/weights unchanged',
          'original_sha256': hashlib.sha256(original).hexdigest(),
          'modified_sha256': hashlib.sha256(modified).hexdigest(), 'removed_lines': removed}
(DATA / 'research-runtime-patch.json').write_text(json.dumps(report, indent=2) + '\n')
import mediapipe as mp
print('MediaPipe', mp.__version__, 'PoseLandmarker import passed')
