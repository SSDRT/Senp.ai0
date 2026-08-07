from __future__ import annotations

import hashlib
import json
import os
import platform
import subprocess
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Sequence


class QaError(RuntimeError):
    """Expected verification failure with a stable machine-readable code."""

    def __init__(self, code: str, message: str, *, details: Any | None = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.details = details

    def as_dict(self) -> dict[str, Any]:
        value: dict[str, Any] = {"code": self.code, "message": self.message}
        if self.details is not None:
            value["details"] = self.details
        return value


@dataclass(frozen=True)
class CommandResult:
    argv: tuple[str, ...]
    returncode: int
    stdout: str
    stderr: str
    elapsed_ms: float


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(chunk_size), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise QaError("missing_json", f"JSON file does not exist: {path}") from exc
    except json.JSONDecodeError as exc:
        raise QaError(
            "bad_json",
            f"Invalid JSON in {path}: line {exc.lineno}, column {exc.colno}: {exc.msg}",
        ) from exc


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def run_command(
    argv: Sequence[str | os.PathLike[str]],
    *,
    timeout: float = 120.0,
    cwd: Path | None = None,
    env: dict[str, str] | None = None,
    check: bool = True,
    input_text: str | None = None,
) -> CommandResult:
    command = tuple(os.fspath(item) for item in argv)
    started = time.perf_counter()
    try:
        completed = subprocess.run(
            command,
            cwd=cwd,
            env=env,
            input=input_text,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError as exc:
        raise QaError("command_missing", f"Required command is unavailable: {command[0]}") from exc
    except subprocess.TimeoutExpired as exc:
        raise QaError(
            "command_timeout",
            f"Command timed out after {timeout:.1f}s: {' '.join(command)}",
            details={"stdout": exc.stdout or "", "stderr": exc.stderr or ""},
        ) from exc
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    result = CommandResult(command, completed.returncode, completed.stdout, completed.stderr, elapsed_ms)
    if check and completed.returncode != 0:
        raise QaError(
            "command_failed",
            f"Command exited {completed.returncode}: {' '.join(command)}",
            details={"stdout": completed.stdout, "stderr": completed.stderr},
        )
    return result


def machine_metadata() -> dict[str, Any]:
    return {
        "hostname": platform.node(),
        "os": platform.platform(),
        "python": platform.python_version(),
        "machine": platform.machine(),
        "processor": platform.processor(),
    }


def percentile(values: Iterable[float], percentile_value: float) -> float:
    ordered = sorted(float(value) for value in values)
    if not ordered:
        raise QaError("empty_samples", "Cannot calculate a percentile from no values")
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * percentile_value
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    weight = position - lower
    return ordered[lower] * (1.0 - weight) + ordered[upper] * weight


def require(condition: bool, code: str, message: str, *, details: Any | None = None) -> None:
    if not condition:
        raise QaError(code, message, details=details)
