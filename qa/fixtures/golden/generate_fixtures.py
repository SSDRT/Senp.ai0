#!/usr/bin/env python3
from pathlib import Path
import sys

QA_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(QA_ROOT))

from senpqa.golden import generate_fixtures  # noqa: E402

if __name__ == "__main__":
    paths = generate_fixtures(Path(__file__).resolve().parent)
    for path in paths:
        print(path)
