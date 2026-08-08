#!/usr/bin/env bash
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
QA_ROOT="$REPO_ROOT/qa"
MODE="full"
ARTIFACT_ROOT=""
SKIP_VISUAL=0
SKIP_EMULATOR=0

usage() {
  cat <<'EOF'
Usage: qa/verify.sh [--fast] [--artifacts PATH] [--skip-visual] [--skip-emulator]

Default mode performs the complete local Wave 1 QA verification, including the
external corpus, visual artifact generation, Android APK build, and senp_api35
instrumentation run. --fast is intended for pull-request checks and skips the
external corpus, visuals, and emulator.
EOF
}

while (($#)); do
  case "$1" in
    --fast) MODE="fast"; SKIP_VISUAL=1; SKIP_EMULATOR=1 ;;
    --artifacts)
      shift
      [[ $# -gt 0 ]] || { echo "--artifacts requires a path" >&2; exit 2; }
      ARTIFACT_ROOT="$1"
      ;;
    --skip-visual) SKIP_VISUAL=1 ;;
    --skip-emulator) SKIP_EMULATOR=1 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

if [[ -z "$ARTIFACT_ROOT" ]]; then
  timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
  ARTIFACT_ROOT="$REPO_ROOT/test-artifacts/verify-$timestamp"
elif [[ "$ARTIFACT_ROOT" != /* ]]; then
  ARTIFACT_ROOT="$REPO_ROOT/$ARTIFACT_ROOT"
fi
mkdir -p "$ARTIFACT_ROOT"
ARTIFACT_ROOT="$(cd "$ARTIFACT_ROOT" && pwd)"
STEP_FILE="$ARTIFACT_ROOT/steps.tsv"
LOG_FILE="$ARTIFACT_ROOT/verification.log"
REPORT_FILE="$ARTIFACT_ROOT/verification-report.json"
: > "$STEP_FILE"
: > "$LOG_FILE"

exec > >(tee -a "$LOG_FILE") 2>&1

required_commands=(python3)
if [[ "$SKIP_VISUAL" -eq 0 ]]; then
  required_commands+=(ffmpeg ffprobe)
fi
required_commands+=(java gradle)
if [[ "$SKIP_EMULATOR" -eq 0 ]]; then
  required_commands+=(adb senp-emulator)
fi
missing_commands=0
for command_name in "${required_commands[@]}"; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Missing required command: $command_name" >&2
    printf '%s\t%s\t%s\t%s\n' "environment:$command_name" 1 0 "missing command" >> "$STEP_FILE"
    missing_commands=1
  fi
done

write_report() {
  local overall_status="$1"
  local failure_step="${2:-}"
  VERIFY_MODE="$MODE" \
  VERIFY_STATUS="$overall_status" \
  VERIFY_FAILURE_STEP="$failure_step" \
  VERIFY_ARTIFACT_ROOT="$ARTIFACT_ROOT" \
  VERIFY_STEP_FILE="$STEP_FILE" \
  VERIFY_LOG_FILE="$LOG_FILE" \
  VERIFY_REPORT_FILE="$REPORT_FILE" \
  REPO_ROOT="$REPO_ROOT" \
  python3 - <<'PY'
import json
import os
import platform
import subprocess
from datetime import datetime, timezone
from pathlib import Path

steps = []
step_file = Path(os.environ["VERIFY_STEP_FILE"])
if step_file.exists():
    for line in step_file.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        name, returncode, elapsed_ms, detail = (line.split("\t", 3) + [""])[:4]
        steps.append({
            "name": name,
            "status": "passed" if int(returncode) == 0 else "failed",
            "returncode": int(returncode),
            "duration_ms": int(elapsed_ms),
            "detail": detail,
        })
repo = Path(os.environ["REPO_ROOT"])
def git(*args):
    result = subprocess.run(["git", *args], cwd=repo, text=True, capture_output=True, check=False)
    return result.stdout.strip()
report = {
    "schema_version": 1,
    "generated_at": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
    "ok": os.environ["VERIFY_STATUS"] == "passed",
    "mode": os.environ["VERIFY_MODE"],
    "failure_step": os.environ["VERIFY_FAILURE_STEP"] or None,
    "repository": {
        "root": str(repo),
        "branch": git("branch", "--show-current"),
        "commit": git("rev-parse", "HEAD"),
        "dirty": bool(git("status", "--porcelain")),
    },
    "machine": {
        "hostname": platform.node(),
        "platform": platform.platform(),
        "python": platform.python_version(),
        "machine": platform.machine(),
    },
    "artifacts_root": os.environ["VERIFY_ARTIFACT_ROOT"],
    "log": os.environ["VERIFY_LOG_FILE"],
    "visual_inspection": {
        "required_for_integrated_video_changes": True,
        "automated_generation_is_not_human_approval": True,
    },
    "steps": steps,
}
path = Path(os.environ["VERIFY_REPORT_FILE"])
path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Verification report: {path}")
PY
}

CURRENT_STEP="environment"
finish() {
  local status=$?
  if [[ $status -eq 0 ]]; then
    write_report passed ""
  else
    write_report failed "$CURRENT_STEP"
  fi
  exit "$status"
}
trap finish EXIT

if [[ "$missing_commands" -ne 0 ]]; then
  CURRENT_STEP="environment"
  exit 1
fi

run_step() {
  local name="$1"
  shift
  CURRENT_STEP="$name"
  echo
  echo "=== $name ==="
  local started ended elapsed status
  started="$(date +%s%3N)"
  "$@"
  status=$?
  ended="$(date +%s%3N)"
  elapsed=$((ended - started))
  printf '%s\t%s\t%s\t%s\n' "$name" "$status" "$elapsed" "$*" >> "$STEP_FILE"
  if [[ $status -ne 0 ]]; then
    echo "Step failed: $name (exit $status)" >&2
    return "$status"
  fi
}

cd "$REPO_ROOT"

run_step "python-compile" python3 -m compileall -q qa || exit $?
run_step "python-tests" env PYTHONPATH=qa python3 -m unittest discover -s qa/tests -v || exit $?
run_step "golden-contract" python3 qa/senp_qa.py golden validate || exit $?
run_step "benchmark-pass-gate" python3 qa/senp_qa.py benchmark compare \
  --baseline qa/fixtures/benchmark/baseline.json \
  --candidate qa/fixtures/benchmark/candidate-pass.json \
  --gates qa/benchmark-gates.json \
  --output "$ARTIFACT_ROOT/benchmark-comparison.json" || exit $?

CURRENT_STEP="benchmark-failure-gate"
set +e
python3 qa/senp_qa.py benchmark compare \
  --baseline qa/fixtures/benchmark/baseline.json \
  --candidate qa/fixtures/benchmark/candidate-fail.json \
  --gates qa/benchmark-gates.json \
  --output "$ARTIFACT_ROOT/benchmark-expected-failure.json" >/dev/null 2>&1
benchmark_failure_status=$?
set -e
if [[ $benchmark_failure_status -eq 0 ]]; then
  printf '%s\t%s\t%s\t%s\n' "benchmark-failure-gate" 1 0 "failing candidate unexpectedly passed" >> "$STEP_FILE"
  echo "Expected failing benchmark fixture to be rejected" >&2
  exit 1
fi
printf '%s\t%s\t%s\t%s\n' "benchmark-failure-gate" 0 0 "failing candidate rejected" >> "$STEP_FILE"

run_step "android-smoke-build" gradle -p qa/android-smoke --no-daemon \
  :app:assembleDebug :app:assembleDebugAndroidTest || exit $?

if [[ -x "$REPO_ROOT/gradlew" ]]; then
  run_step "repository-gradle-tests" "$REPO_ROOT/gradlew" --no-daemon test || exit $?
fi

if [[ "$MODE" == "full" ]]; then
  run_step "corpus-integrity" python3 qa/senp_qa.py corpus validate \
    --output "$ARTIFACT_ROOT/corpus-validation.json" || exit $?
fi

if [[ "$SKIP_VISUAL" -eq 0 ]]; then
  run_step "visual-artifacts" python3 qa/senp_qa.py visual generate \
    --output-dir "$ARTIFACT_ROOT/visual" --no-hash || exit $?
  run_step "pose-overlay" python3 qa/senp_qa.py overlay render \
    --pose-json qa/fixtures/pose/synthetic_pose.json \
    --output-dir "$ARTIFACT_ROOT/overlay" || exit $?
fi

if [[ "$SKIP_EMULATOR" -eq 0 ]]; then
  run_step "api35-emulator" python3 qa/senp_qa.py emulator run \
    --apk qa/android-smoke/app/build/outputs/apk/debug/app-debug.apk \
    --test-apk qa/android-smoke/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    --output-dir "$ARTIFACT_ROOT/emulator" || exit $?
fi

CURRENT_STEP="complete"
echo
echo "All requested verification steps passed."
