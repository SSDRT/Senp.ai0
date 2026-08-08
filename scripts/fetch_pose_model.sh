#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESTINATION="${1:-$ROOT/local-models/pose_landmarker_full.task}"
MODEL_VERSION="1"
MODEL_URL="https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/${MODEL_VERSION}/pose_landmarker_full.task"
EXPECTED_SHA256="5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"
EXPECTED_BYTES="9398198"

mkdir -p "$(dirname "$DESTINATION")"
if [[ -f "$DESTINATION" ]]; then
  current_sha="$(sha256sum "$DESTINATION" | awk '{print $1}')"
  current_bytes="$(stat -c '%s' "$DESTINATION")"
  if [[ "$current_sha" == "$EXPECTED_SHA256" && "$current_bytes" == "$EXPECTED_BYTES" ]]; then
    printf 'verified %s  %s\n' "$EXPECTED_SHA256" "$DESTINATION"
    exit 0
  fi
fi

temporary="${DESTINATION}.tmp.$$"
trap 'rm -f "$temporary"' EXIT
curl --fail --location --retry 3 --output "$temporary" "$MODEL_URL"
actual_sha="$(sha256sum "$temporary" | awk '{print $1}')"
actual_bytes="$(stat -c '%s' "$temporary")"
if [[ "$actual_sha" != "$EXPECTED_SHA256" ]]; then
  printf 'SHA-256 mismatch: expected %s, got %s\n' "$EXPECTED_SHA256" "$actual_sha" >&2
  exit 1
fi
if [[ "$actual_bytes" != "$EXPECTED_BYTES" ]]; then
  printf 'size mismatch: expected %s, got %s\n' "$EXPECTED_BYTES" "$actual_bytes" >&2
  exit 1
fi
mv "$temporary" "$DESTINATION"
trap - EXIT
printf 'downloaded and verified %s  %s\n' "$EXPECTED_SHA256" "$DESTINATION"
