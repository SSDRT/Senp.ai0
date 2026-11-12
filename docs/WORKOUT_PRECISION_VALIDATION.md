# Workout precision implementation and evidence

Date: 2026-10-22. Repository: `SSDRT/Senp.ai0`, local folder `D:\projects\senpai`, branch `codex/live-pose-accuracy`, continuation from `83a94d6`. Scope is the native Android pose/movement engine and generic reference-action live composition.

The engine now learns a demonstrated movement or static hold, recognizes its phase, and evaluates reliable local measurements against configurable reference tolerances. It does not require a fixed exercise-name list. This enables arbitrary demonstrated workouts within the measurement coverage below; it does not establish validated technique rules for every exercise. Human-level accuracy and universal mobile compatibility have **not** been achieved or certified.

## What changed

- Added 20 coachable measurement types: 12 bilateral angles and eight torso-normalized distances. All 33 original landmarks, timestamps, visibility, presence and image/world coordinates remain available.
- Each measurement carries its weakest contributing joint confidence, including torso anchors for normalized distances. Missing visibility/presence cannot borrow global confidence. Live recognition masks weak measurements before phase matching.
- Added automatic and explicit static-hold compilation. Holds enter after reliable evidence, remain tracked without invented repetitions, and lose confidence when observations disappear. Finite actions also recheck current observations after completion.
- Added per-feature and per-phase thresholds, noise widening, continuous confirmation, hysteresis, release dwell and repeat cooldown. Every evaluated profile feature has an explicit monitored/context/unsupported/weak/missing/incompatible decision; a whole-frame abstention identifies why evaluation is unavailable.
- Body proportions are context only in the generic live composition, excluded from phase matching and correction cues. Distance suggestions require enough reliable, comparable limb ratios. This prevents forcing another person's limb lengths, but is not a full anthropometric calibration.
- Added bounded live recognition history and immediate removal of uncertain feedback. The reference target does not adapt to a sustained user error.
- Added verified optional MediaPipe Heavy assets and model provenance in inference results. Full remains the application default.
- Both recorded and live adapters now pass SHA-verified direct/mapped model buffers with explicit lifetime retention, bypassing the shared native asset-cache rewrite that can invalidate an overlapping estimator.

`ReferenceActionSessionEngine` compiles and recognizes the same extended features. `LiveReferenceActionProcessor` exposes the complete precision result and routes active suggestions through the existing live feedback stabilizer. The isolated legacy `PushUpLiveEvaluator` path and recorded deviation evaluator remain separate; this change does not replace every older screen/evaluator. A push-up demonstration can use the new generic reference path.

## Measurement coverage

| Feature names (prefix shown) | Count | Unit / purpose |
| --- | ---: | --- |
| `angle.left_shoulder`, `right_shoulder` | 2 | Elbow–shoulder–hip angle, degrees |
| `angle.left_elbow`, `right_elbow` | 2 | Shoulder–elbow–wrist angle, degrees |
| `angle.left_hip`, `right_hip` | 2 | Shoulder–hip–knee angle, degrees |
| `angle.left_knee`, `right_knee` | 2 | Hip–knee–ankle angle, degrees |
| `angle.left_ankle`, `right_ankle` | 2 | Knee–ankle–foot-index angle, degrees |
| `angle.left_body_line`, `right_body_line` | 2 | Shoulder–hip–ankle angle, degrees |
| `distance.hand_separation`, `foot_separation` | 2 | Bilateral wrist/ankle spacing, torso lengths |
| `distance.knee_separation`, `elbow_separation` | 2 | Bilateral knee/elbow spacing, torso lengths |
| `distance.left_hand_to_shoulder`, `right_hand_to_shoulder` | 2 | Same-side wrist-to-shoulder distance, torso lengths |
| `distance.left_hand_to_hip`, `right_hand_to_hip` | 2 | Same-side wrist-to-hip distance, torso lengths |
| Existing `ratio.*`, `signed.*`, trajectory features | Variable | Context/phase evidence, never direct correction cues |
| Unknown profile measurements | Variable | Explicitly unsupported |

Each state can expose fewer monitored measurements when reference samples, joints, confidence or morphology are insufficient. No implementation can infer every exercise property from these 20 quantities. Fine wrist/finger mechanics, spinal curvature, neck orientation, equipment trajectory/load, contact, balance forces, breathing and expert exercise-specific constraints need additional validated measurements or sensors. Multi-person identity association is not established by the single-pose estimator. Camera angle, occlusion and monocular depth ambiguity remain limitations even with world landmarks.

## Trigger policy and configuration

| Default | Meaning |
| --- | --- |
| Angle enter 10°, exit 6° | Distance **outside** the phase's learned robust range, not deviation from its median |
| Spacing enter 0.12, exit 0.07 torso lengths | Same semantics, normalized to observed torso scale |
| Noise multiplier 2 | Effective enter is at least `2 × 1.4826 × reference MAD`; noisy reference cannot create artificially narrow triggers |
| Confirmation 240 ms | Continuous, reliable threshold exceedance before activation |
| Release 160 ms | Continuous return within exit tolerance before release |
| Evidence gap 250 ms | Longer gaps discard accumulated evidence |
| Repeat cooldown 3000 ms | Limits repeated trigger events for the same feature/direction; active suggestions are returned separately |
| Feature confidence 0.65 | Minimum of local confidence, frame, reference feature and action confidence |
| State confidence 0.55; coverage 0.60 | Phase must be recognized reliably |
| Reference confidence 0.55; at least five inliers | Weak reference features abstain; outlier fraction above 0.25 is rejected |
| At least four proportion features; max relative difference 0.25 | Conservative spacing compatibility gate; at most one fifth may exceed this bound |

Exactly reaching the enter boundary is allowed; only a strict exceedance starts confirmation. A new phase requires fresh confirmation against its own target and tolerance. Gaps, lost action, weak/missing joints and subject/mirror/evidence changes suppress stale cues. Mirrored comparisons preserve reference feature keys while suggestion text names the observed anatomical side. These confidence scores are quality gates, not calibrated probabilities of correctness.

`WorkoutPrecisionConfig.featureOverrides` sets tolerances for a particular calibrated feature. `stateFeatureOverrides[stateId][featureName]` takes precedence for a particular phase. Invalid feature/state names fail immediately. A caller can pass the config to `newLiveProcessor` or `PreparedReferenceAction`. Example:

```kotlin
val tuning = WorkoutPrecisionConfig(
    featureOverrides = mapOf("angle.left_knee" to WorkoutTolerance(12.0, 7.0)),
    stateFeatureOverrides = mapOf(
        profile.states.first().id to mapOf("angle.left_knee" to WorkoutTolerance(15.0, 9.0))
    ),
    confirmationMs = 240,
)
val processor = session.newLiveProcessor(profile, 15.0, tuning)
```

Set `ReferenceActionCompilerConfig.referenceMode` to `MOVEMENT` or `STATIC_HOLD` when the AUTO decision is inappropriate. AUTO regards a reference as static only when at least three movement measurements stay within 6° / 0.06 torso units between their 5th and 95th percentiles. Slow or small movements may require explicit mode. The live composition uses up to 12 phases and a minimum five samples per phase; weak individual phase measurements still abstain. Profiles are versioned `reference-action/2` and must be recompiled to provide evidence typing.

Only matching, single-source `THREE_D` reference/live geometry is coachable by default. Untyped/mixed/incompatible references abstain. `allowProjected2d` is an explicit research opt-in; aspect-correct pixel geometry and cross-view calibration are not implemented in the frozen pose contract, so this option is not a validated accuracy mode. A movement can still be recognized while precise coaching abstains.

## Open-source models and mobile behavior

Uses the existing Apache-2.0 MediaPipe/BlazePose stack (`tasks-vision:0.10.32`) and pure Kotlin robust statistics, phase matching and state machines. No paid inference service or cloud upload was added. Sources: [MediaPipe pose classification](https://github.com/google-ai-edge/mediapipe/blob/master/docs/solutions/pose_classification.md), [Google pose classification guidance](https://developers.google.com/ml-kit/vision/pose-detection/classifying-poses), [Pose Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker). Upstream model benchmarks are not evidence of this application's exercise accuracy.

Full SHA-256: `5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1`.
Heavy SHA-256: `64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b` (30,664,242 bytes). Both have provenance under `models/`; binaries are excluded from Git. Heavy download preparation verifies size and SHA, then atomically installs the asset. Optional packaging: `-PincludeHeavyPoseModel=true`. Select it through `LiveMediaPipePoseEstimator.create(context, LivePoseModel.HEAVY)`; packaging alone does not switch the application default. Reference extraction and live inference must use the same model/configuration, and callers must preserve that pairing; the profile does not independently enforce model identity.

Existing CameraX latest-frame backpressure, CPU default, GPU-to-CPU fallback and orientation/lifecycle handling are retained. Android minimum SDK is 26. The APK contains arm64-v8a, armeabi-v7a, x86 and x86_64. ELF/ZIP static 16 KB checks and `zipalign -c -P 16 4` pass. The emulator uses 4 KB pages: runtime 16 KB behavior, physical phones, GPU delegates, thermal/battery behavior, iOS and every mobile device remain unverified. This is an Android implementation, not universal mobile support.

## Verification results

- **316 JVM test executions, zero failures/errors/skips**, including debug/release Android unit executions. Fourteen new coach tests cover exact boundaries, transient versus sustained motion, release/cooldown, weak/missing local confidence, gaps, source/subject changes, mirrored side labels, complete feature accounting, morphology guards, threshold/noise overrides, static/finite behavior, bounded history, fresh evidence per phase and phase invariance under changed limb ratios.
- **13 native Android test cases pass**: eleven pose/model tests and two validation-app camera/integration tests. The new overlap regression keeps one graph inferring while repeatedly creating and closing eight live and eight recorded estimators, and asserts neither native asset-cache path is rewritten. Both suites pass with the buffer-loading mitigation. Independently reran model comparison, reference integration and timestamped overlay evidence tests.
- **28 Python QA tests pass in WSL Ubuntu**. Native Windows execution of existing shell-fixture tests is unsuitable (WinError193); WSL executes them correctly.
- Comprehensive checks passed for core contracts/pipeline/motion/alignment/cache/review, headless runner, sync integration/validation and Android pose/video/codex, plus app unit tests/build, core boundary verification, motion verification and both connected suites. After the final proportion-context change, core/app unit tests/build and connected app suite were rerun; final engine checks were repeated.
- Root aggregate `check` remains blocked by the pre-existing Compose `UnusedBoxWithConstraintsScope` lint error at `VideoEditorScreen.kt:608`; that frontend file is unchanged. Engine/module checks pass. This is not reported as a clean whole-repository lint result.

Reproduce the focused checks with the repository wrapper (configured JDK/Android SDK and a booted emulator required):

```powershell
.\gradlew.bat :core-motion:check :core-motion:workoutPrecisionBenchmark :sync-v2-integration:check checkCoreBoundaries --no-parallel
.\gradlew.bat :android-pose-mediapipe:check :validation-app:test :validation-app:assembleDebug --no-parallel
.\gradlew.bat :android-pose-mediapipe:connectedDebugAndroidTest :validation-app:connectedDebugAndroidTest --no-parallel
python scripts/verify_apk_compatibility.py validation-app/build/outputs/apk/debug/validation-app-debug.apk --output test-artifacts/apk-compatibility.json
```

The evidence archive includes full verification commands/logs, final XML results, pre-fix native failures, per-frame replay measurements, both model hashes and the complete threshold sweep. It excludes model binaries and videos.

### Synthetic threshold sweep

1,200 deterministic cases: 25 named feature scenarios × 10/15/30/60 FPS × two seeds × six confirmation durations. Streams include ±2° / ±0.02 torso noise, ±20% timestamp jitter, 200 ms transient faults, two-second sustained faults, and weak-confidence faulty frames. Phase labels are supplied by an **oracle**, so this does not test camera estimation or exercise classification. Family names label feature combinations, not real exercise videos or expert technique rules.

| Confirmation | True events | False events | Decision precision | Episode recall | Max trigger delay |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 0 ms | 560 | 560 | 50.0% | 100% | 1837 ms |
| 80 ms | 490 | 490 | 50.0% | 87.5% | 1967 ms |
| 120 ms | 490 | 420 | 53.8% | 87.5% | 1962 ms |
| 180 ms | 490 | 70 | 87.5% | 87.5% | 213 ms |
| **240 ms** | **560** | **0** | **100%** | **100%** | **343 ms** |
| 320 ms | 560 | 0 | 100% | 100% | 384 ms |

At the selected 240 ms there were zero weak-confidence active faulty cues. Shorter dwell falsely responds to the injected transient, and its cooldown can then suppress/delay the real episode. Results justify this mechanism default under the tested distribution; they do not establish a percentage of human pose accuracy. Full rows are in `threshold-sweep.json`; reproduce with `:core-motion:workoutPrecisionBenchmark`.

Synthetic scenarios: squat, lunge, split squat, step up, hip hinge, deadlift pattern, glute bridge, push-up, horizontal/overhead press, pull-up, row, biceps curl, triceps extension, lateral raise, leg raise, crunch pattern, plank, side plank, wall sit, calf raise, jumping jack, mountain climber, burpee pattern and an unlisted custom workout using all 20 measurements.

### Real-model runtime evidence

API 36 x86_64 emulator, CPU inference, repeated upstream `pose.jpg`, 12 post-warmup latency samples per model:

| Model | Valid frames | Median inference | P95 inference |
| --- | ---: | ---: | ---: |
| Full | 16/16 | 25.81 ms | 37.37 ms |
| Heavy | 16/16 | 57.00 ms | 57.93 ms |

Both preserve 33 world landmarks and reject the blank image. Heavy costs more latency here; neither has a locally measured human-accuracy advantage. The real-model integration compiles 30 native frames as a hold, tracks 21 of 25 subsequent frames, monitors all 20 feature types, emits no self-reference correction and removes feedback on disappearance. This is a self-consistency test, not independent accuracy validation.

Timestamped image/blank replay yields 21 valid of 24 frames, median inference 29.94 ms, P95 47.46 ms. Raw/filtered overlays and the contact sheet were inspected: visible limbs align plausibly; blank frames contain no skeleton. Repeated source imagery does not establish temporal exercise accuracy. Timing varies with host load and these small samples do not support a phone FPS guarantee.

**Native stability finding and mitigation:** two combined camera-then-reference runs crashed in `libmediapipe_tasks_vision_jni.so` with SIGBUS before the buffer-loading change. The failure is consistent with an upstream asset-cache race: the [pinned 0.10.32 source](https://github.com/google-ai-edge/mediapipe/blob/v0.10.32/mediapipe/util/android/asset_manager_util.cc) unconditionally overwrites cached assets, potentially invalidating another graph's mapping. [Upstream issue 6369](https://github.com/google-ai-edge/mediapipe/issues/6369) reports this hazard. Both adapters now bypass that path using the [supported model-buffer API](https://github.com/google-ai-edge/mediapipe/blob/v0.10.32/mediapipe/tasks/java/com/google/mediapipe/tasks/core/BaseOptions.java). Uncompressed assets map immutable APK bytes; compressed custom assets use a direct-memory copy. Buffers remain owned until graph close, including tracker recreation and CPU fallback. The native overlap regression and combined camera/reference suite pass after this mitigation. Failed-run/crash logs remain in the evidence archive. This removes the identified shared-file hazard; a full physical-device lifecycle/soak qualification is still required.

## Remaining accuracy and release gates

1. Obtain freely usable, independently expert-labelled workout videos and held-out subjects/views/devices; the repository's Fedora-only external corpus was unavailable in this Windows environment. Synthetic streams and upstream still imagery cannot replace this data.
2. For every claimed exercise and phase, define expert-approved target bands and trigger labels, including acceptable anthropometric variants. Measure landmark PCK/MPJPE where ground truth exists, angular error, trigger precision/recall, false corrections per minute and latency. Split by subject and video before tuning; report uncertainty and coverage/abstention.
3. Calibrate reliable 3D/2D/view transitions and model pairing, low light, occlusion, loose clothing, camera movement and multiple-person scenes. Add metrics for unsupported exercise mechanics before claiming coverage.
4. Qualify the model-buffer crash mitigation under sustained physical-device sessions; run ARM32/ARM64 phones across supported Android versions, cameras, rotations, GPU/CPU, sustained thermal conditions and actual 16 KB devices. Publish a supported device/exercise matrix. Implement and validate iOS separately if required.

Implementation and reproducible mechanism tests are delivered. These empirical release gates remain unfinished because the required independent annotations and device fleet are absent; no human-level or every-workout/device claim should be made from these results.
