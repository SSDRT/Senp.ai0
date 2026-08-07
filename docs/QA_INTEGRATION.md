# Wave 1 QA Integration Contract

The QA lane consumes artifacts from the kernel, video/pose, motion, and
alignment lanes without importing or duplicating their implementations. All
handoffs are files under an ignored run directory such as:

```text
test-artifacts/incoming/<lane>/<run-id>/
```

Every lane publishes a `manifest.json` conforming to
`qa/schemas/lane-artifact.schema.json`. Paths in the manifest are relative to
its directory, SHA-256 values cover the exact bytes consumed by QA, and each
run records the source commit and dirty state. Generated media, models, APKs,
and local SDK paths must not be committed.

## Common envelope

```json
{
  "contract_version": "1.0",
  "lane": "video_pose",
  "run_id": "20260806T031500Z-real-corpus",
  "created_at": "2026-08-06T03:15:00+05:30",
  "git": {"commit": "<sha>", "dirty": false},
  "artifacts": [
    {
      "kind": "pose-output",
      "path": "biceps-right.pose.json",
      "sha256": "<64 lowercase hex characters>",
      "schema": "qa/schemas/pose-output.schema.json",
      "scenario_id": "biceps-right-h264-portrait"
    }
  ]
}
```

Timestamps are always integer milliseconds. Frame indexes may be included only
as diagnostics. Schema or contract changes require a version change and a
coordinated QA update; producers must not silently add a new interpretation to
an existing field.

## Kernel and infrastructure lane

Publish:

1. JVM/Gradle test reports and a `stage-report` conforming to
   `qa/schemas/stage-report.schema.json`.
2. The debuggable engine APK and instrumentation APK, or stable Gradle task
   names that build them.
3. Package name, fully qualified instrumentation runner, optional device JSON
   artifact path, pipeline/cache/provenance versions, and typed failure codes.
4. A benchmark report for orchestration/cache paths when those measurements are
   meaningful.

QA invokes the production APKs through:

```bash
python3 qa/senp_qa.py emulator run \
  --apk /path/to/engine-debug.apk \
  --test-apk /path/to/engine-debug-androidTest.apk \
  --package <application-id> \
  --test-package <instrumentation-application-id> \
  --runner <fully-qualified-runner> \
  --remote-artifact <device-json-path> \
  --output-dir test-artifacts/emulator-engine
```

The instrumentation JSON must contain `{"ok": true}` on success. The harness
retains raw instrumentation output, logcat, meminfo, and pulled JSON and rejects
install failures, non-success instrumentation codes, malformed artifacts,
crashes, and ANRs.

## Video and pose lane

Publish one pose JSON per selected video using
`qa/schemas/pose-output.schema.json`:

- `schema_version: 1`.
- Source video relative path and SHA-256 in `source`.
- Original/decode orientation metadata and image dimensions.
- Strictly increasing integer `timestamp_ms` values.
- Exactly 33 indexed landmarks per frame, retaining image/world coordinates,
  visibility, and presence. When both coordinate spaces are emitted, use
  separate named arrays or separate contract-versioned files; do not discard
  either space.
- Sampling/model/provenance metadata required to reproduce output.

Before integration acceptance, QA overlays representative real pose output on
matching decoded frames:

```bash
python3 qa/senp_qa.py overlay render \
  --pose-json /path/to/real.pose.json \
  --background /path/to/matching-frame.png \
  --output-dir test-artifacts/overlay-real
```

A human must inspect the result for correct orientation, timestamp/frame match,
33-landmark topology, left/right consistency, joint placement, confidence
rendering, and person framing. Synthetic overlay success alone is not enough to
declare the integrated video/pose lane complete.

## Motion lane

For every golden scenario, publish the normalized/feature track consumed by the
alignment lane in the same timestamp-first shape as each golden fixture input:

```json
{
  "time_unit": "ms",
  "samples": [
    {
      "timestamp_ms": 0,
      "valid": true,
      "features": {"primary": 0.25, "secondary": 0.80}
    }
  ]
}
```

Also publish stage timing/memory, quality state, repaired versus unrepaired gap
masks, confidence/validity masks, normalization/profile versions, and a stable
feature-name/weight table. QA does not prescribe the algorithm. It checks
strict timestamp ordering, finite numeric features, deterministic repeated
output, short-gap and long-blind fixtures, and the absence of confident output
where the producer reports invalid data.

## Phase and alignment lane

For every valid golden fixture, publish a result with this minimum interface:

```json
{
  "contract_version": "1.0",
  "scenario_id": "long_blind",
  "alignment_mode": "anchor_dtw",
  "mapping": [
    {"reference_ms": 0, "candidate_ms": 0},
    {"reference_ms": 10000, "candidate_ms": 10000}
  ],
  "genuine_windows": [],
  "uncertain_windows": [{"start_ms": 3500, "end_ms": 5800}],
  "confidence": {"overall": 0.72}
}
```

Validate each result with:

```bash
python3 qa/senp_qa.py golden evaluate \
  --fixture qa/fixtures/golden/long_blind.json \
  --result /path/to/long_blind.result.json
```

The evaluator checks allowed alignment mode, bounded monotonic timestamp
mapping, required/forbidden genuine regions, and required uncertainty overlap.
It explicitly protects the speed-only and blind-region invariants. Additional
trace fields are welcome and remain producer-owned.

## Benchmark publication

Each lane writes a report conforming to
`qa/schemas/benchmark-report.schema.json`. A case must include stable ID, input
shape (for example 10-second track at 10/15/20/30 FPS), repetitions, median,
p95, and peak RSS. Store raw repetitions when practical and include machine,
JVM/device, build type, commit, and configuration metadata.

Compare candidate and baseline with:

```bash
python3 qa/senp_qa.py benchmark compare \
  --baseline /path/to/baseline.json \
  --candidate /path/to/candidate.json \
  --gates qa/benchmark-gates.json \
  --output test-artifacts/benchmark-comparison.json
```

Machine-specific baselines belong in CI artifacts or release evidence, not as
unqualified performance claims in source control. Expensive matrices run in a
nightly/manual workflow; fast PR checks validate contracts and deterministic
fixtures.

## Integration acceptance sequence

1. Run `qa/verify.sh` on the integration branch with the real external corpus
   and `senp_api35` available.
2. Validate every lane artifact SHA and schema, then evaluate all golden lane
   results.
3. Run the production engine instrumentation APK through the emulator harness.
4. Generate real pose overlays and re-perform human visual inspection.
5. Compare timing/memory reports against the chosen machine baseline.
6. Retain the verification report, raw logs, visual report, inspection notes,
   emulator JSON, and benchmark comparison together under one ignored run
   directory.

Wave 1 is not integrated merely because compilation succeeds. It is integrated
only when the timestamped media evidence has been inspected, all deterministic
contracts pass, the API 35 runtime is clean of crashes/ANRs, and the resulting
report is auditable.
