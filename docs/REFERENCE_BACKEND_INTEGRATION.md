# Reference-selected pose backend: autonomous integration

Follow-up: [observed-path tuning](OBSERVED_PATH_TUNING.md) adds a direct intermediate-motion requirement while retaining this cycle's development calibration and counts. The results below remain the original backend integration record.

23 October 2026. Branch `codex/live-pose-accuracy`, baseline `980e80f`.

This cycle implements the reference-only Full/Heavy experiment from `AUTONOMOUS_TUNING_RESULTS.md` in the experimental qualified-rep session. New recordings, human annotations and reviewer recruitment: zero. No landmark CNN weights or acceptance tolerances were changed in this cycle.

## Result and scope

The uploaded reference remains the target. Compile its selected exercise/side with Full first. If reference measurement/calibration fails, re-extract that same hash-verified upload with pinned Heavy, preserving sampling settings and detection/presence/tracking thresholds. Compile it against the same exercise, side, policy, declared view and raw world-coordinate pipeline. Prefer a usable primary reference without extracting Heavy. Unsupported exercises and unresolved source guidance remain unavailable.

Select before camera inference and keep one backend for the session. The compatible reference profile, raw reference extraction, model fingerprint and extraction request travel together. The video extractor resolves default assets by the pinned requested model hash. The live camera resolves its model from the selected reference fingerprint and applies the retained thresholds. A Full frame cannot qualify a Heavy-calibrated rep. Lifecycle cancellation propagates through preparation; model-loading/calibration failures do not relax the gates or start an incompatible camera session.

This applies to the existing explicit qualified-rep mode. Exercise selection and qualified counting remain opt-in; there is no new automatic exercise classifier or exercise selector UI. Generic reference comparison continues to use its prepared backend. The experimental APK bundles both official models using `-PincludeHeavyPoseModel=true`; default builds can omit Heavy and report its absence. A higher-capacity model can require more processing time and memory. No phone latency guarantee was measured.

## Measured availability

The production selection seam was replayed on all 32 unique reference/exercise/side/view configurations from the same 48 development videos. Source identities and all 16,633 timestamps were checked between the separately pinned model caches. The actual selection matches the frozen reference-only registry exactly: Full 7, Heavy 2, unavailable 23. Fourteen fallback reference extractions were requested after model-specific cache reuse. No source video score, count or good/bad label enters the production selector API.

| Development-set measure | Full only | Full first, Heavy fallback | Change |
| --- | ---: | ---: | ---: |
| Usable reference/sides | 7/32 (21.875%) | 9/32 (28.125%) | +2; +6.25 percentage points |
| Corresponding source replay configurations | 20/96 (20.833%) | 26/96 (27.083%) | +6; +6.25 points; +30% relative |
| Independently measured live-rep precision | Unknown | Unknown | No precision claim |

The 26/96 source-replay coverage follows the earlier frozen per-backend source replays and the newly verified production reference selections. This cycle does not describe 96 fresh Android video-inference sessions. Native tests separately check the session seam and original-landmark replay, plus actual Full/Heavy inference and the selected Heavy camera path.

| Exercise in the real-video development subset | Full references | Additional Heavy references | Still unavailable | Total |
| --- | ---: | ---: | ---: | ---: |
| Overhead press | 2 | 1 | 5 | 8 |
| Biceps curl | 1 | 1 | 6 | 8 |
| Bodyweight squat | 3 | 0 | 5 | 8 |
| Push-up | 1 | 0 | 7 | 8 |

The additional cases are overhead press, reference `subject_001_shoulder_good_front`, right side; and biceps curl, reference `subject_005_bicep_curl_good_side`, left side. These are particular demonstrated references, not proof of general exercise/view accuracy. Glute bridge and lateral raise retain existing mechanism policies without new real-video calibration evidence in this subset. Qualified counting has six exercise policies; a technique catalogue entry does not imply a validated rep-counting model.

## Verification

- 203 scoped JVM cases passed: core motion 159, Android pose adapter 12, validation app 32. Existing unchanged core tests may be reused by Gradle's up-to-date checks. Six new JVM cases cover reference-only selection, coherent fallback/live binding and model-specific cache reuse, missing assets, unsupported/mismatched evidence, cancellation, and the 32-configuration real-observation replay.
- All 1,152 generated causal rep scenarios passed. These verify engineering mechanics, not human technique accuracy.
- All 16 native suite cases passed, run sequentially on the available Windows API 36 x86_64 emulator with 4 KB pages. They check Full and Heavy actual inference, original source landmark parity, camera/model binding and lifecycle behavior. A separate three-case evidence run also passed and exported the parity reports and Heavy camera binding: three processed frames before pause, six after resume, all under the selected Heavy hash.
- The new Heavy-only landmark fixture uses a Full reference that fails calibration, a Heavy reference with seven calibration cycles, and 360 original source observations. Android reproduces three qualified outputs (two classified correct by the engine, one acceptable), zero rejected and eight unassessable, including attempt boundaries/progress/reasons. These are numerical parity targets, not human labels. The prior Full fixture still reproduces its existing outputs.
- 36 Python QA cases and two research-runtime cases passed. The fixture exporter retains hashes, provenance and original observations; its generated data is test-only, with CC BY 4.0 attribution. Research models remain excluded from live acceptance.
- Sixteen timestamped Full/Heavy overlay observations and four angle plots were inspected on the additional reference cases and cross-person source clips. Cropping/occlusion, missing confidence-gated evidence and substantial world-angle disagreement remain visible. A model can calibrate a supported repeated bout while other portions of the same video remain unavailable. Calibration is not whole-video pose correctness.
- The experimental APK contains both hash-pinned model assets and excludes native replay fixtures. Signature, ZIP alignment and static native-library alignment checks accompany the delivery. Emulator validation and static checks do not establish support on every handset or a 16 KB runtime.
- Pose-module lint passed with no issues. App-wide lint remains blocked by the existing `UnusedBoxWithConstraintsScope` error at `VideoEditorScreen.kt:608`; that UI file is unchanged. The delivered lint report records the error and existing warnings. App-wide lint is not claimed to pass.

## Remaining autonomous work

Twenty-three of 32 development reference/sides still fail calibration. The selector increases availability; it does not solve geometry bias, mirror/person ambiguity, inadequate visible-side evidence or bad reference demonstrations. The 98% precision target remains unestablished because this corpus provides clip labels rather than independent accepted/rejected labels for individual reps. More accepted outputs alone cannot measure that target.

Next, compare separately defined image-space and world-space measurement reliability per exercise/view, investigate temporal person/view consistency, and evaluate fault/rep decisions on task-compatible existing independent annotations. Keep frozen participant/video splits and explicit uncertainty. Any causal weakly supervised training must preserve the distinction between dataset truth, model agreement and generated engineering labels. No new human labeling step is a prerequisite for continuing those experiments.

## Reproduction

```powershell
$env:JAVA_HOME='D:/android_studio/jbr'
.\gradlew.bat :validation-app:testDebugUnitTest :android-pose-mediapipe:testDebugUnitTest :core-motion:verifyMotionCore :core-motion:qualifiedRepBenchmark checkCoreBoundaries '-PincludeHeavyPoseModel=true' '-PqualifiedReferenceReplayRoot=test-artifacts/exercise-knowledge' --no-parallel
.\gradlew.bat :validation-app:assembleDebug :android-pose-mediapipe:connectedDebugAndroidTest :validation-app:connectedDebugAndroidTest '-PincludeHeavyPoseModel=true' --no-parallel
```

The optional replay property requires the ignored local Full/Heavy high-cadence caches and the frozen reference registry from the first autonomous cycle. Without it, that opt-in test is explicitly skipped; synthetic JVM coverage remains available. Run the camera binding test with Heavy packaged. Model binaries and source videos stay outside Git; source-landmark test fixtures carry their own provenance.
