# core-alignment

`core-alignment` is the pure Kotlin/JVM timestamp-first phase and alignment implementation for the canonical Senp.ai0 pipeline. It ports the proven Wave 1 engine without changing its alignment hierarchy or millisecond algorithms. The module has no Android, MediaPipe, decoder, UI, OpenCV, networking, or model dependency.

## Canonical pipeline APIs

The module implements the frozen interfaces declared in `core-pipeline`:

```kotlin
val phaseDetector: PhaseDetector = TimestampFirstPhaseDetector()
val alignmentEngine: AlignmentEngine = TimestampFirstAlignmentEngine()

val sourcePhases: StageResult<PhaseSeries> =
    phaseDetector.detect(sourceMotion, configuration.exerciseProfileVersion)

val result: StageResult<AlignmentAnalysis> = alignmentEngine.align(
    sourceMotion = sourceMotion,
    sourcePhases = sourcePhasesValue,
    referenceMotion = referenceMotion,
    referencePhases = referencePhasesValue,
    configuration = configuration,
)
```

Inputs and outputs are only canonical contracts:

- input: `ai.senp.core.contracts.MotionSeries` and `PhaseSeries`
- output: `ai.senp.core.contracts.AlignmentResult` and `ProblemWindow` inside `AlignmentAnalysis`
- failures: canonical `StageResult.Failure` with `AnalysisFailure.Phase` or `AnalysisFailure.Alignment`
- time: canonical `TimestampMs`; every threshold and decision remains millisecond-based

The previous lane-local motion, feature, phase-diagnostic, mapping, alignment, problem-window, role, timestamp, and validity types are internal implementation details. They are not part of the module's Kotlin public API.

## Canonical feature mapping

`MotionSeries.features[*].values` are mapped by exact feature name. `MotionSeries.angles` are also accepted: each `JointAngle.joint` becomes a feature at the matching timestamp unless a value with the same name already exists in the feature map.

The expected canonical joint-angle names produced by the motion lane are:

```text
left_shoulder, right_shoulder
left_elbow, right_elbow
left_wrist, right_wrist
left_hip, right_hip
left_knee, right_knee
left_ankle, right_ankle
```

The generic profile additionally recognizes `torso_lean`, `shoulder_tilt`, `hip_tilt`, and the integration-fixture phase feature `profile_signal`. Unknown optional features are not required. Comparison uses the configured union of available features, and each DTW cell masks values unavailable or invalid on either side.

Exercise-profile versions are resolved internally from canonical `AnalysisConfiguration.exerciseProfileVersion`. Recognized profile families are:

```text
biceps curl / curl
pushup / push_up
squat
leg raise / leg_raise
lat pullover / pullover
pullup / pull_up
plank
generic / exercise-profiles/1
alignment-synthetic/1 (deterministic tests and traces)
```

The selected internal phase driver follows the exercise family, for example elbow angles for curls/pushups, knee angles for squats, shoulder angles for pullovers, and hip/knee angles for leg raises. Optional bilateral features remain masked when one side is unavailable. Unsupported profile versions or profiles with no common non-dynamic phase feature return a typed failure instead of silently selecting an arbitrary signal.

Features whose names indicate `velocity`, `speed`, `tempo`, `timestamp`, or `time_seconds` are excluded from form-distance rules. They may exist in canonical motion data, but they cannot turn a pure speed change into a form error.

## Validity and confidence mapping

Canonical validity is preserved at every timestamp:

- `VALID`, `REPAIRED`, and `DEGRADED` samples retain their values and use canonical validity confidence.
- values below the internal minimum confidence are masked during phase and DTW comparison.
- `BLIND` and `CONTINUITY_BREAK` samples are mapped to null feature values with zero confidence.
- a `JointAngle` confidence is combined with frame validity using the lower value.
- interpolation never crosses a gap longer than the configured millisecond maximum.

DTW compares only common valid features. Coverage is common valid rule weight divided by total configured rule weight. Missing weight and blind cells receive deterministic penalties. Long blind spans produce alignment confidence `0.0`, and the window engine cannot promote them to genuine form errors.

## Preserved alignment hierarchy

The port preserves the original modes and their ordering:

1. `REP_NORMALIZED` — active ranges and repetition boundaries are paired monotonically; each repetition interval is resampled to a deterministic phase grid and aligned with masked DTW.
2. `ANCHOR_CONSTRAINED_DTW` — single-motion tracks with enough turning anchors are aligned segment by segment.
3. `BANDED_GLOBAL_DTW` — non-flat motion without reliable rep or anchor structure uses banded masked global DTW.
4. `LINEAR_INSUFFICIENT_MOTION` — explicit fallback for insufficient motion; confidence is capped and form-error windows are suppressed.
5. `EMPTY` — internal typed mode for an empty lane input; the canonical pipeline normally rejects an empty mapping.

Also preserved:

- active-motion trimming and timestamp phase-offset estimation
- turning anchors and rep boundaries
- unequal-repetition monotonic assignment
- monotonic mapping regularization and frozen-run repair
- symmetric log-slope confidence and common-feature coverage confidence
- speed-only suppression
- blind suppression
- single-motion peak handling
- repeated-phase consensus
- genuine versus uncertain duration-based windows

## Canonical artifacts

`CanonicalAlignmentArtifacts` writes and validates QA-facing artifacts:

```kotlin
CanonicalAlignmentArtifacts.writeJson(analysis, jsonFile)
CanonicalAlignmentArtifacts.writeCsv(analysis.alignment, csvFile)
CanonicalAlignmentArtifacts.validate(analysis)
```

The JSON artifact contains `schemaVersion`, the canonical serializable `AlignmentResult`, and canonical `ProblemWindow` values. The CSV contains one row per canonical alignment point:

```text
source_timestamp_ms,reference_timestamp_ms,local_cost,confidence
```

Validation requires a non-empty source-strict/reference-monotonic mapping, finite non-negative costs, confidence ranges in `[0, 1]`, and valid problem-window bounds.

## Verification commands

```bash
./gradlew :core-alignment:test
./gradlew :core-alignment:run --args=/tmp/senp-alignment-internal-traces
./gradlew :core-alignment:canonicalTrace --args=/tmp/senp-alignment-canonical-traces
./gradlew :core-alignment:microbenchmark
./gradlew :core-alignment:canonicalMicrobenchmark
./gradlew clean check
```

The original deterministic trace and microbenchmark remain available. The canonical trace writes JSON and CSV for clean motion, deliberate error, long blind span, speed-only timing shift, unequal repetitions, and a single cycle. The canonical benchmark measures the adapter alignment path after phase detection at 10, 15, 20, and 30 FPS.

## Limitations

- Upstream motion processing must calculate meaningful named features or joint angles; this module does not decode video, infer pose, repair landmarks, or normalize coordinates.
- Internal profile selection is version-string based until canonical profile configuration becomes a richer frozen contract.
- Feature distance scales remain deterministic profile defaults rather than learned calibration.
- Confidence is a deterministic quality score, not a calibrated probability.
- The banded DTW implementation retains a full cost/move matrix for deterministic traceback and is intended for short exercise tracks, not unbounded streaming alignment.
