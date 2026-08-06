# Motion Core (Wave 3 canonical adapter)

`core-motion` is the pure Kotlin/JVM motion-quality module for Senp.ai0. Its public boundary is `CoreMotionProcessor`, an implementation of `ai.senp.core.pipeline.MotionProcessor` that accepts canonical `PoseSequence` and returns canonical `MotionSeries`. The complete proven Wave 1 timestamp-aware engine remains module-internal; there are no Kotlin-public duplicate pose, validity, role, timestamp, or motion DTOs.

The module has no Android, AndroidX, MediaPipe, Compose, OpenCV, decoder, FFmpeg, frontend, desktop UI, network, rendering, or alignment implementation dependency. It uses canonical Kotlin 2.0.21, Java 21, and the repository Gradle 8.13 wrapper.

## Canonical integration API

```kotlin
val processor: MotionProcessor = CoreMotionProcessor()
val result: StageResult<MotionSeries> = processor.process(
    poses = canonicalPoseSequence,
    normalizationVersion = MotionCoreVersions.NORMALIZATION,
    exerciseProfileVersion = MotionCoreVersions.EXERCISE_PROFILES + "/squat",
)
```

The adapter validates both version strings and returns `AnalysisFailure.Motion` for an empty sequence or unsupported version. Input timestamps stay authoritative and must already be strictly increasing under the canonical contract.

Canonical mapping rules:

- all 33 landmarks are mapped by canonical index without reordering;
- image `x/y/z` are copied exactly into the internal engine;
- optional world `x/y/z` are copied only when present; depth is never synthesized;
- when one of visibility or presence is `null`, the available confidence is used for both gates;
- when both are `null`, both internal confidence values are zero, so features are conservatively unavailable rather than falsely trusted;
- canonical `BLIND` input is treated as unavailable pose data;
- canonical `CONTINUITY_BREAK` explicitly resets per-landmark EMA state.

The adapter merges upstream validity with motion-derived validity by this severity order and never lowers the upstream state:

```text
VALID < REPAIRED < DEGRADED < BLIND < CONTINUITY_BREAK
```

Upstream reasons are retained. Motion repair, unusable tracking, long gaps, tracking resets, left/right swaps, and impossible proportions add their canonical reasons when applicable. `BLIND` confidence is always zero. Angles that cannot be trusted remain `null`; they are not converted to zero or another sentinel.

## Canonical feature schema

`MotionFeatureSchema.VERSION` and `MotionCoreVersions.FEATURE_SCHEMA` are both `motion-series-features/1`. Every `FeatureSample.values` map is inserted in deterministic order. Wave 4 can consume the following stable keys.

### Quality, guardrail, and normalization keys

| Key | Meaning |
|---|---|
| `quality.score` | Exercise-aware motion score in `[0,1]` |
| `quality.motion_confidence` | Final canonical frame confidence after validity merge |
| `quality.input_confidence` | Upstream canonical frame confidence |
| `quality.required_coverage` | Required-landmark coverage |
| `quality.required_visibility` | Mean required-landmark visibility |
| `quality.required_presence` | Mean required-landmark presence |
| `quality.preferred_quality` | Preferred-landmark combined quality |
| `quality.repaired_fraction` | Fraction of required landmarks repaired by motion core |
| `quality.clipping` | Explicit clipping signal, currently zero at the canonical adapter boundary |
| `quality.instability` | Explicit instability signal, currently zero at the canonical adapter boundary |
| `quality.selected_side_code` | `-1` left, `0` both/not selected, `1` right |
| `guardrail.left_right_swap_applied` | `1` when conservative whole-side relabeling was applied, else `0` |
| `guardrail.impossible_proportions` | `1` when the diagnostic proportion guardrail fired, else `0` |
| `normalization.image.available` | `1` when pelvis/torso image normalization succeeded, else `0` |
| `normalization.world.available` | `1` when real world-anchor normalization succeeded, else `0` |

### Angle keys

For each joint below and for both `image` and `world` spaces:

```text
angle.<space>.<joint>.degrees
angle.<space>.<joint>.confidence
angle.<space>.<joint>.velocity_degrees_per_second
```

The ordered joint set is:

```text
left_shoulder, right_shoulder,
left_elbow, right_elbow,
left_wrist, right_wrist,
left_hip, right_hip,
left_knee, right_knee,
left_ankle, right_ankle
```

The angle confidence is the minimum visibility/presence of the triplet multiplied by final frame confidence. It is zero whenever the angle value is `null`. Velocity is degrees per second from the actual timestamp delta and is `null` on the first sample, after an invalid angle, or for a non-positive delta.

For Wave 4, canonical `MotionSeries.angles` emits the normalized image-space angles with the exact bare joint names above, for example `left_elbow`. Only non-null angles are emitted, and each `JointAngle.confidence` carries the angle confidence. World angles remain available through the detailed `angle.world.*` feature-map keys without creating ambiguous duplicate comparison joints.

### Torso keys

For both `image` and `world` spaces:

```text
torso.<space>.length
torso.<space>.lean_from_vertical_degrees
torso.<space>.shoulder_tilt_degrees
torso.<space>.hip_tilt_degrees
```

These values are `null` when anchors/confidence are unavailable or the frame is `BLIND`/`CONTINUITY_BREAK`.

## Exercise-profile mapping

The stable profile family version is `exercise-profiles/1`. Passing that version alone selects `generic`. The canonical adapter accepts a bare ID, `<id>/1`, or `exercise-profiles/1/<id>`. Hyphens and underscores are equivalent. Supported IDs and aliases are:

| Internal profile | Accepted IDs/aliases | Wave 4 comparison angles |
|---|---|---|
| `generic` | `generic` | all 12 image angles |
| `biceps_curl` | `biceps_curl`, `biceps_curls` | elbow, shoulder, wrist; both sides |
| `pushup` | `pushup`, `pushups`, `push_up`, `push_ups` | elbow, shoulder, hip; both sides |
| `squat` | `squat`, `squats` | hip, knee, ankle; both sides |
| `leg_raise` | `leg_raise`, `leg_raises` | hip and knee; both sides |
| `plank` | `plank`, `planks` | shoulder, hip, knee; both sides |
| `pullup` | `pullup`, `pullups`, `pull_up`, `pull_ups` | elbow, shoulder, hip; both sides |

`MotionFeatureSchema.comparisonFeaturesByExercise` publishes these exact bare canonical `JointAngle.joint` comparison-name lists. The internal profile still owns required/preferred landmarks, side policy, coverage threshold, and scoring weights; the feature mapping does not add exercise-specific branches to the algorithms.

### Legacy COCO-17 compatibility

The internal `Coco17Adapter` exists only for replaying the Python backend fixture and importing legacy test exports. It maps the 17 standard COCO joints into exact MP33 slots, copies legacy confidence into visibility and presence, and leaves every MediaPipe-only landmark explicitly absent. Production inference and the public adapter remain canonical MP33-native.

Stable identifiers are centralized in `MotionCoreVersions` for pipeline, MP33 contract, COCO adapter, normalization, exercise profiles, feature schema, angle definitions, and fixture schema.

## Temporal pipeline

Timestamps are the only temporal source of truth. No decision uses frame counts.

### 1. Validity and internal gap repair

A raw landmark is usable for tracking when its image coordinate is finite and both visibility and presence meet their configured thresholds. For each landmark independently, an invalid run is repaired only when:

1. it is internal, with usable samples on both sides; and
2. the timestamp duration between those boundary samples is at most `maxRepairGapMs`.

Coordinates are linearly interpolated by timestamp. When a landmark gap is repaired, image coordinates are interpolated; its real model-provided world coordinate is also interpolated only when both boundary landmarks contain finite world values, otherwise it remains `null`. Repaired visibility and presence are interpolated and capped at `repairedConfidenceCap` (default `0.35`), deliberately preserving the Python reference's confidence downgrade.

Leading and trailing gaps are never extrapolated. A gap just beyond the repair boundary remains missing.

### 2. Continuity breaks

A recovered landmark is marked as a continuity break when its invalid boundary-to-boundary duration is at least `continuityBreakGapMs`. A direct timestamp jump of at least the same duration marks all usable landmarks in the arriving frame.

The EMA state is reset before a continuity-break landmark is accepted, so motion does not smear across a long blind interval. The first recovered frame whose required landmarks break continuity receives `FrameValidity.CONTINUITY_BREAK`.

### 3. Elapsed-time EMA

For elapsed time `dtMs` and half-life `halfLifeMs`:

```text
alpha = 1 - exp(-ln(2) * dtMs / halfLifeMs)
smoothed = previous + alpha * (current - previous)
```

This replaces the Python implementation's fixed per-frame alpha. Missing unrepaired samples clear the per-landmark EMA state. Repaired finite samples may be smoothed, while retaining their downgraded confidence and repaired marker.

### Millisecond configuration

All temporal configuration is explicitly in milliseconds and represented as `Long`:

| Field | Meaning |
|---|---|
| `maxRepairGapMs` | Maximum boundary-to-boundary duration eligible for interpolation |
| `continuityBreakGapMs` | Minimum gap/jump duration that resets continuity and is surfaced |
| `emaHalfLifeMs` | Smoothing half-life |
| `blindEnterDurationMs` | Sustained low-score duration required to enter blind state |
| `recoverDurationMs` | Sustained usable-score duration required to leave blind state |

## Exercise-aware quality and hysteresis

`ExerciseProfile` supplies required landmarks, preferred landmarks, side policy, minimum required coverage, and scoring weights. The generic equation has no arm-specific indices.

For the active required set, the gate computes:

- mean visibility;
- mean presence;
- required coverage, with finite repaired landmarks receiving configurable partial credit;
- repaired fraction.

Preferred landmarks contribute their combined visibility/presence/coverage quality only when the profile actually declares preferred landmarks. Clipping and instability are caller-provided values in `[0,1]`. An impossible-proportion guardrail can apply a bounded penalty without rewriting coordinates.

The positive weighted score is followed by multiplicative repaired, clipping, instability, and proportion penalties, then clamped to `[0,1]`.

Hysteresis uses elapsed timestamp durations:

- low score below `blindEnterThreshold` for `blindEnterDurationMs` enters blind state and backdates the batch result to the start of that low run, matching the Python batch behavior;
- recovery requires score at or above `usableThreshold` for `recoverDurationMs` and backdates the recovered run;
- an isolated poor frame is `DEGRADED`, not immediately `BLIND`.

Validity precedence is:

1. `CONTINUITY_BREAK` for required-landmark recovery after a long gap;
2. `BLIND` for a sustained hysteresis interval;
3. `DEGRADED` for inadequate required coverage or score;
4. `REPAIRED` when otherwise usable required landmarks include a repair;
5. `VALID`.

## Side policy and guardrails

Supported `SidePolicy` values are:

- `BOTH`: score every profile landmark;
- `LEFT_ONLY` / `RIGHT_ONLY`: retain neutral landmarks and the requested side;
- `BEST_VISIBLE`: compare required-landmark evidence on both sides.

Best-visible selection is deterministic (left wins an exact initial tie) and uses `sideSwitchMargin` to prevent frame-to-frame side flapping.

The left/right swap guardrail compares temporal continuity across shoulder, elbow, wrist, hip, knee, and ankle pairs. It swaps all paired 33-landmark labels only when enough pairs are present and the whole-side swapped assignment is substantially and absolutely cheaper. It does not infer camera mirroring and is intentionally conservative.

The proportion guardrail checks limb-to-torso ranges and large left/right homologous segment asymmetry. It only reports a flag and optional quality penalty. It never enforces bone lengths.

## Normalization

`PoseNormalizer` returns a typed `NormalizationResult` with `NORMALIZED`, `MISSING_ANCHORS`, `DEGENERATE_SCALE`, or `DEGENERATE_ORIENTATION`.

Image normalization:

1. subtracts the pelvis midpoint; and
2. divides by pelvis-to-shoulder-center torso scale.

It does not rotate image coordinates and does not change world coordinates.

World normalization uses only world coordinates already supplied by the pose model. It performs the same root/scale normalization and can optionally orient the pose to orthonormal body axes derived from hip width, torso-up, and their cross product. It never synthesizes depth.

## Features

`MotionFeatures` exposes a deterministic ordered map of 12 bilateral angles over the full 33-landmark contract:

- shoulder, elbow, wrist;
- hip, knee, ankle.

A triplet returns `null` when any coordinate is absent/non-finite, confidence is below threshold, either vector is degenerate, or the frame is `BLIND`/`CONTINUITY_BREAK`. Image-space angles and torso geometry intentionally use only image `x/y`; MediaPipe image `z` is retained but is not metric depth. World-space features use all three real model-provided world coordinates. Angular velocity is degrees per second using timestamp differences and is `null` for invalid angles or non-positive elapsed time.

Reusable torso features include pelvis/shoulder centers, torso vector and length, lean from vertical, shoulder tilt, and hip tilt. Trajectory utilities return timestamped points and per-second velocities without introducing a hidden sampling rate.

## Deliberate Python reference port

Behavioral reference files inspected:

- `backend/app/services/smooth_service.py`
- `backend/app/services/quality_gate_service.py`
- `backend/app/services/angle_service.py`
- `backend/app/services/normalize_service.py`
- `backend/app/services/lift3d_service.py`
- `backend/app/services/skeleton_defs.py`

Preserved behavior:

- confidence-gated missing landmarks;
- bounded internal linear interpolation;
- repaired confidence capped at `0.35` by default;
- EMA smoothing and continuity reset across missing spans;
- strict blind/recover hysteresis with batch backdating;
- confidence-aware deterministic angles and `null` invalid triplets;
- root-relative torso-scale normalization.

Deliberate differences:

| Python reference | Kotlin motion core |
|---|---|
| COCO-17 arrays | Immutable full 33-landmark contract |
| Frame-count gap/hysteresis thresholds | Timestamp-duration thresholds in milliseconds |
| Fixed EMA alpha per frame | Elapsed-time half-life EMA |
| Single confidence | Separate visibility and presence |
| Generic formula with hardcoded arm indices | Required/preferred exercise profiles and side policy |
| Boolean visible mask | Explicit `VALID`, `REPAIRED`, `DEGRADED`, `BLIND`, `CONTINUITY_BREAK` |
| Heuristic 2D-to-3D depth lifter | Not ported; only real optional world coordinates are accepted |
| Unconditional calibrated bone-length enforcement | Not ported; proportions are diagnostic only |
| No explicit clipping/instability input | Both are explicit bounded quality inputs |
| No side policy/swap diagnostics | Fixed/best-visible side policies plus conservative guardrails |

## Verification

Run the complete lane verification from the repository root:

```bash
core-motion/scripts/verify.sh
```

Equivalent Gradle task:

```bash
./gradlew --no-daemon :core-motion:verifyMotionCore
```

This runs deterministic unit/replay tests, regenerates artifacts into an ignored build directory and byte-compares them with the committed copies, then executes the synthetic benchmark. CI runs this same task. Artifact drift fails verification rather than silently rewriting Git files.

Committed deterministic artifacts:

```text
core-motion/src/test/resources/traces/clean.csv
core-motion/src/test/resources/traces/jittered.csv
core-motion/src/test/resources/traces/short-gap.csv
core-motion/src/test/resources/traces/long-blind.csv
core-motion/src/test/resources/traces/fps-10.csv
core-motion/src/test/resources/traces/fps-15.csv
core-motion/src/test/resources/traces/fps-20.csv
core-motion/src/test/resources/traces/summary.json
core-motion/src/test/resources/fixtures/mp33_squat_motion_core_v1.json
core-motion/src/test/resources/fixtures/legacy_coco17_motion_core_a54a845.manifest.json
core-motion/src/test/resources/fixtures/legacy_coco17_motion_core_a54a845.source.json
```

The native MP33 fixture is timestamped and records full input, tracked/smoothed output, quality state, repair/continuity metadata, guardrails, normalized image and body-oriented world landmarks, image/world angles, torso features, version identifiers, and source provenance. It includes a squat cycle, required-joint short repair, a sustained blind interval with recovery continuity break, and preferred-only shoulder loss.

The legacy fixture contains the complete COCO-17 golden data from `SSDRT/senp.ai` commit `a54a8453907a6cd1ece61ad7565020a98118c032` and records the SHA-256 of the original source file. Replay tests validate the compatibility adapter, early smoothing parity, short/long gaps, blind range, and angle edge cases without porting the old heuristic 3D lifter.

Regenerate committed deterministic artifacts only when an intentional behavior or schema change has been reviewed:

```bash
./gradlew --no-daemon :core-motion:updateMotionArtifacts
./gradlew --no-daemon :core-motion:checkMotionArtifacts
```

The generated benchmark report is ignored build output at:

```text
core-motion/build/reports/microbenchmark/motion-core-10s.json
```

The benchmark scenario is always a deterministic ten-second, 15 FPS, 151-frame track with small seeded noise and one repairable wrist gap. It uses 20 warmups and 120 measured runs and emits mean, p50, p95, throughput, and a checksum.

## Current limitations

- The API is batch-oriented; a stateful streaming facade is not part of Wave 1.
- It evaluates one pose track at a time and does not select among multiple people.
- Clipping and instability must be computed by an upstream pose/video lane.
- Left/right correction detects abrupt label inversion, not semantic camera mirroring or gradual subject turns.
- Proportion limits are broad guardrails, not a clinical or biomechanical model.
- Image `z` is retained as supplied but is not treated as metric depth.
- Repaired landmarks default below the angle-confidence threshold, so repaired joint angles remain `null` rather than pretending to be observed.
- Phase detection, repetition segmentation, DTW/alignment, decoding, Android integration, rendering, and UI are intentionally outside this module.
