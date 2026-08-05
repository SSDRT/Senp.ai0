# core-alignment

`core-alignment` is a pure Kotlin/JVM engine for timestamp-first motion phase detection, validity-aware alignment, alignment confidence, and duration-based problem windows. It has no Android, MediaPipe, decoder, OpenCV, Compose, networking, or frontend dependency.

## Integration API

```kotlin
val profile = ExerciseProfile(
    id = "biceps-curl-v1",
    featureRules = linkedMapOf(
        "left_elbow_angle" to FeatureRule(
            weight = 1.0,
            distanceScale = 35.0,
            phaseWeight = 1.0,
            minimumMotionRange = 12.0,
        ),
        "right_elbow_angle" to FeatureRule(
            weight = 1.0,
            distanceScale = 35.0,
            phaseWeight = 1.0,
            minimumMotionRange = 12.0,
        ),
        "shoulder_compensation" to FeatureRule(
            weight = 0.7,
            distanceScale = 25.0,
            phaseWeight = 0.0,
            minimumMotionRange = 8.0,
        ),
    ),
)

val result: AlignmentResult = AlignmentEngine().align(
    user = MotionTrack(userFrames),
    reference = MotionTrack(referenceFrames),
    profile = profile,
)

val alignedReferenceMs: Long? = result.referenceTimestampFor(userTimestampMs = 4_250L)
```

Each `MotionFrame` has a strictly increasing, non-negative `timestampMs` and a map of named `FeatureSample`s. A feature sample carries a nullable finite value and confidence in `[0, 1]`. Feature names are sorted internally, so map insertion order does not affect results.

All public temporal inputs and outputs are milliseconds. Frame indices exist only inside implementation details and diagnostics derived from timestamps.

## Alignment hierarchy

The engine applies these modes in order:

1. `REP_NORMALIZED`: multi-rep tracks are paired monotonically, repeated assignments are split into ordered timestamp intervals, each interval is resampled to a deterministic phase grid, and local masked DTW paths are mapped back to source timestamps.
2. `ANCHOR_CONSTRAINED_DTW`: single-motion tracks with enough phase turns are divided by corresponding turning anchors and aligned segment by segment.
3. `BANDED_GLOBAL_DTW`: non-flat motion without enough reliable rep or anchor structure uses a banded global masked DTW path.
4. `LINEAR_INSUFFICIENT_MOTION`: linear timestamp mapping is used only when at least one track lacks sufficient active motion. Confidence is capped and no problem windows are emitted.
5. `EMPTY`: one input has no frames.

Active range detection combines exercise-profile phase signals, timestamp-derived velocity, time-based smoothing, and millisecond padding. Phase offset estimation uses normalized phase-signal correlation over timestamp-resampled overlap and trims only when sufficient active duration remains.

## Validity-aware comparison

DTW compares only features that are valid on both sides and above the profile confidence threshold.

- `commonCoverage` is common valid comparison weight divided by total configured comparison weight.
- Missing comparison weight incurs a deterministic penalty.
- Cells below `minimumCommonFeatureCoverage` incur an additional blind-cell penalty.
- Blind mapping points receive zero alignment confidence and cannot generate genuine or uncertain error windows.
- Interpolation never crosses a gap longer than `maximumInterpolationGapMs`.
- Long or merged blind spans therefore cannot appear as confidently aligned regions.

Feature distance is normalized by each rule's `distanceScale` for DTW while problem reporting retains weighted raw feature-unit differences and the maximum individual feature difference.

## Confidence and windows

A monotonic user-to-reference timestamp mapping is built from the DTW path and regularized without blending it back toward a linear baseline. Local slope is measured using a millisecond window. Confidence combines symmetric log-slope confidence with valid feature coverage:

```text
slope confidence = max(floor, exp(-lambda * abs(ln(path slope))))
alignment confidence = slope confidence * sqrt(common coverage)
```

Problem windows are duration-based rather than frame-count-based:

- A genuine error needs an active, non-blind raw difference plus sufficient confidence and confidence-weighted difference.
- A single-motion peak may override unstable path confidence when a large individual feature deviation is obvious.
- Multi-rep consensus promotes repeated errors occurring at corresponding normalized rep phases.
- Borderline-confidence errors become `UNCERTAIN_ALIGNMENT` windows.
- Extremely low-confidence and blind regions produce no actionable window.
- Minimum duration, padding, and merge gaps are all expressed in milliseconds.

## Traces and benchmark

```bash
./gradlew :core-alignment:test
./gradlew :core-alignment:run --args=/tmp/senp-alignment-traces
./gradlew :core-alignment:microbenchmark
```

The trace command writes JSON and CSV files containing mode, phase diagnostics, every timestamp mapping, coverage, slope, confidence, raw/maximum/weighted differences, blind and active flags, and window decisions. Its scenarios cover equal motion, a deliberate form error, a long invalid span, speed-only timing change, single-cycle anchor alignment, and explicit uncertain classification.

The microbenchmark performs warmups and 30 measured iterations for deterministic ten-second tracks at 10, 15, 20, and 30 FPS. It prints frame counts, total and mean wall-clock milliseconds, and a checksum that prevents dead-result elimination.

## Differences from the Python behavioral reference

- Temporal thresholds and outputs are milliseconds instead of frame counts.
- Invalid features are preserved and masked; missing columns are not globally filled or silently converted to zero.
- Rep normalization truly resamples each rep before DTW and maps the normalized path back to timestamped source samples.
- Slope confidence uses `abs(ln(slope))`, treating reciprocal speed changes symmetrically; the Python reference uses `abs(slope - 1)`.
- Confidence also includes common valid feature coverage.
- Linear mapping is an explicit insufficient-motion mode, never a silent DTW failure fallback.
- Feature weights and phase signals are deterministic profile configuration rather than data-driven runtime weighting.

## Current limitations

- The engine expects upstream motion features; it does not decode video, infer pose landmarks, repair pose tracks, or choose an exercise profile.
- Phase detection assumes at least one configured feature has meaningful cyclic or single-arc motion. Profiles should avoid using a form-error-only feature as a phase driver.
- The DTW implementation is banded but retains a full cost/move matrix for deterministic traceback; this is suitable for short analysis tracks but is not a streaming aligner.
- Confidence is a deterministic heuristic score, not a calibrated probability.
