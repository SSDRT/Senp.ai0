# Wave 1 QA and Emulator Verification

This directory contains the phone-free verification surface for the Senp.ai0
engine. It does not implement video, pose, motion, or alignment algorithms. It
validates external media, generates human-inspectable evidence, defines
cross-lane contracts, checks deterministic synthetic fixtures, records timing
and memory, and exercises Android behavior on an API 35 emulator.

## Complete local verification

From the repository root:

```bash
qa/verify.sh
```

The default command verifies all 21 external videos by byte count and SHA-256,
generates timestamped contact sheets and a 33-landmark synthetic pose overlay,
builds the test-only Android APKs, starts or reuses `senp_api35`, runs the
instrumentation suite, scans logcat for crashes/ANRs, and writes an auditable
`verification-report.json` under `test-artifacts/verify-<UTC timestamp>/`.
Generated videos, images, APKs, and reports are ignored by Git.

Useful variants:

```bash
qa/verify.sh --fast
qa/verify.sh --skip-emulator
qa/verify.sh --artifacts test-artifacts/my-run
```

`--fast` is the pull-request surface: Python contracts/failure tests, golden
fixtures, benchmark gates, and APK compilation. It intentionally does not
pretend that the external corpus or an emulator is present in ordinary hosted
PR jobs.

## Environment

The full command expects:

- Python 3.11 or newer.
- FFmpeg and ffprobe with H.264/HEVC decode, `drawtext`, and `xstack`.
- A discoverable bold TrueType/OpenType font. Set `SENP_QA_FONT` when fontconfig
  is unavailable.
- Java 17 or newer and Gradle 8.x.
- Android SDK platform/build tools 35, `adb`, the `senp_api35` Android 15
  x86_64 AVD, and the `senp-emulator` helper.
- The external corpus and authoritative manifest described below.

The defaults are the Fedora Wave 1 paths. Override them without changing Git:

```bash
export SENP_CORPUS_ROOT=/path/to/drive-14DVra51GZZozAF-4uBwOuk75U0z-QwMO
export SENP_CORPUS_MANIFEST=/path/to/drive-14DVra51GZZozAF-4uBwOuk75U0z-QwMO.json
```

## Corpus integrity and selection

`corpus.lock.json` is small and committed. It pins the authoritative external
manifest SHA-256, expected 21-file count and total byte count, and the compact
visual selection. No media is copied into Git.

```bash
python3 qa/senp_qa.py corpus validate
python3 qa/senp_qa.py corpus list
```

Validation fails separately for a missing manifest, changed manifest, missing
root, missing video, changed byte count, and changed SHA-256. Visual coverage
contains H.264 portrait, HEVC landscape, two labeled right/wrong exercise pairs,
and cricket bowling.

## Timestamped frames and contact sheets

```bash
python3 qa/senp_qa.py visual generate \
  --output-dir test-artifacts/visual
```

The selector reads presentation timestamps from ffprobe, chooses representative
points across the usable duration, selects exact frame indexes, and records both
requested and actual timestamps in milliseconds. The report is
`visual-report.json`. Each tile states the source role, codec, orientation,
frame index, requested timestamp, and decoded timestamp.

Generation is not visual approval. A reviewer must open the generated sheets
and verify orientation, person framing, temporal coverage, pair labels,
timestamp plausibility, and image integrity. Store observations beside the
artifacts. When real video-pose output becomes available, overlay it and repeat
that inspection before integrated Wave 1 is declared complete.

## Pose diagnostics

The overlay tool accepts the versioned `schemas/pose-output.schema.json`
contract: strict increasing millisecond timestamps and exactly all 33 MediaPipe
landmarks per frame, including x/y/z, visibility, and presence.

```bash
python3 qa/senp_qa.py overlay render \
  --pose-json qa/fixtures/pose/synthetic_pose.json \
  --output-dir test-artifacts/overlay
```

It can run independently today using the deterministic fixture. A decoded frame
may be supplied with `--background /path/to/frame.png`. Low-confidence points
and connections are deliberately rendered differently so blind regions remain
visible during review.

## Golden fixtures and lane results

The fixtures in `fixtures/golden/` cover clean, speed-shifted,
deliberate-error, short-gap, long-blind, different-rep-count, single-rep, and
corrupt-input scenarios. They define inputs and invariants; they do not contain
a duplicate implementation of motion or alignment.

```bash
python3 qa/senp_qa.py golden validate
python3 qa/senp_qa.py golden evaluate \
  --fixture qa/fixtures/golden/long_blind.json \
  --result /path/to/alignment-result.json
```

A result must provide contract version `1.0`, matching `scenario_id`, an allowed
alignment mode, bounded monotonic timestamp mapping, and genuine/uncertain
windows. The harness enforces, among other invariants, that speed-only changes
are not genuine errors and long blind spans are uncertain rather than confident.

## Benchmark and stage reports

- `schemas/stage-report.schema.json` defines per-stage duration, peak RSS,
  status, total duration, and process peak RSS.
- `schemas/benchmark-report.schema.json` defines repeat count, median, p95, and
  peak RSS for named cases.
- `benchmark-gates.json` defines machine-readable regression gates.

```bash
python3 qa/senp_qa.py benchmark compare \
  --baseline baseline.json \
  --candidate candidate.json \
  --gates qa/benchmark-gates.json \
  --output test-artifacts/benchmark-comparison.json
```

The command exits nonzero when required cases are missing or timing/memory gates
fail. The committed benchmark numbers are contract fixtures, not product
performance claims. Each implementation lane must publish measurements from its
own code using the same schema.

## Android emulator harness

The standalone `android-smoke` project creates a no-UI target APK and a custom
instrumentation APK. The test validates target context access, monotonic
millisecond timestamps, artifact writing, API/ABI metadata, and process memory.

```bash
gradle -p qa/android-smoke --no-daemon \
  :app:assembleDebug :app:assembleDebugAndroidTest

python3 qa/senp_qa.py emulator run \
  --apk qa/android-smoke/app/build/outputs/apk/debug/app-debug.apk \
  --test-apk qa/android-smoke/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --output-dir test-artifacts/emulator
```

The runner requires a booted API 35 `senp_api35` emulator, installs both APKs,
runs instrumentation, saves raw output/logcat/meminfo/JSON, detects install or
instrumentation failure, rejects malformed test JSON, scans for package
crash/ANR signatures, and exits nonzero on any failure. There is no phone
fallback.

For integration with the production engine APK or its instrumentation suite,
pass that APK, test APK, target package, instrumentation test package,
runner, and remote JSON artifact to the same command. See
[QA integration](../docs/QA_INTEGRATION.md).
