# Multiple-pass causal pose tuning

This run tests three complementary approaches: stricter evidence for an observed movement path, phase-aware return geometry, and an autonomous threshold search. The first two are implemented. The wider-threshold candidate is withheld after real-video replay exposed more acceptances on bad-labelled clips. All changes stay in `codex/live-pose-accuracy`, against baseline `d26df1e`.

The app still compares live movement with the uploaded reference. MediaPipe landmark-network weights, source/reference recordings and model hashes are unchanged. No human annotations or new subjects were introduced. This is tuning of the causal reference comparator, not training the landmark CNN from written exercise descriptions.

## Changes that are enabled

**Compatible path evidence.** An intermediate pose can prove that a rep's outbound or return path was observed only if all required selected-side features are directly visible and compatible with that reference branch. Previously, a single visible but incompatible pose could supply the only path evidence. A long endpoint hold could dilute its brief ambiguity below the overall ambiguity limit and earn a count. Such a case now becomes unassessable. Brief deviations retain the original fault dwell; this does not turn an uncertain observation into a severe form rejection.

**Return-branch comparison.** Previously, the first portion of an observed return could be tested against the outbound curve until reversal dwell finished. References with different outbound and return geometry could therefore withhold a compatible rep. After a directly confirmed far endpoint and an observed fall below the supported peak, the engine can immediately use the return curve if the entire observed pose is compatible with that branch. It selects one coherent branch for all required features, never a different branch per feature. The existing reversal confirmation, far/ready dwell, fault persistence, coverage, range and counting requirements remain unchanged. A compatible provisional return does not itself confirm reversal or produce a count.

The policy schema advances from `qualified-reference-reps/4` to **`qualified-reference-reps/5`**. Every reference key changes deliberately, invalidating stale runtime policies. Full/Heavy selection remains reference-only, standard fits first, with bounded robust recovery afterward under `qualified-reference-selection/2`. Existing raw landmark fixtures and prior version expectations remain byte-identical; separate version-five expectations document the changed behavior.

## Results

| Measurement | Previous engine | Compatible-path pass only | Final engine |
|---|---:|---:|---:|
| Unsupported constructed path counts | 288/288 | 0/288 | 0/288 |
| Compatible asymmetric constructed cycles accepted | 0/96 | 0/96 | 96/96 |
| Wrong-return constructed cycles accepted | 0/96 | 0/96 | 0/96 |
| Compatible sparse constructed cycles accepted | 96/96 | 96/96 | 96/96 |
| Total failures in this 576-case suite | 384 | 96 | 0 |

These cases cover all six qualified policies, two sides, direct/mirrored mapping and 10/15/30/60 FPS. They use deliberately constructed angular features and test engine mechanics, **not human-form precision**. The compatible-path-only run isolates the contribution of the first change. The final benchmark fails its task if any control fails; report-only mode is available for measuring earlier behavior.

On the unchanged real-video development corpus:

| Cohort | Usable calibrations before → after | Accepted outputs before → after | Bad-clip accepted outputs before → after |
|---|---:|---:|---:|
| Original capped references | 30/96 → 30/96 | 17 → 18 | 7 → 7 |
| Complete references | 38/96 → 38/96 | 31 → 31 | 7 → 7 |

No previously usable reference/side is lost and model/fit selection is unchanged. These two cohorts overlap and are not independent datasets. Calibration availability stays **31.25% capped / 39.58% complete**. There is no claim of an additional coverage gain.

The capped LEFT press source `subject_018_shoulder_good_front.mp4` increases from one to two accepted outputs. Its 5.133–6.800 second attempt keeps the same raw driver progression and interval; measured ambiguity drops **16.02% to 0%** after the curve-selection fix. The earlier LEFT reference reconstructs **4/5** cycles under the original 80% requirement **without the expanded driver envelope previously needed**. The recovered complete robust reference still reconstructs 9/9 cycles. Complete-cohort accepted/rejected/unassessable totals remain 31/62/185; capped totals become 18/46/167.

Eight original source frames, all raw coordinates/confidences and timestamped angle traces were rendered and inspected. They show an outward press and return, with some noisy landmark observations during return. This is a visual sanity check, not an independent judgment that the rep is anatomically correct. The delivered report includes these images, their timestamps and hashes.

## Additional autonomous verification

All **31 currently accepted complete-input outputs** are tested after four controlled mutations of their existing descriptor streams: severe quality-feature corruption, missing driver confidence, incorrect model identity and shallow driver excursion. The original timestamps remain unchanged; these are explicitly manufactured test faults, not new ground-truth labels. **124/124** mutated target attempts lose their accepted count, with no replacement count overlapping the original target or increase in other qualified counts. The suite fails if any such control fails. Its scope is robustness to specified corruption, not an estimate of real-world precision.

The existing 2,112 generated mechanism/path/departure controls also pass, including clear sustained faults, shallow reps, nearly correct movement, brief transients, gaps, confidence, identity and cadence handling. New JVM tests check each compatible-path direction, brief-fault behavior, asymmetric phases, wrong-return rejection and causal prefix behavior. New Android tests execute the packaged engine on compatible/incompatible sparse observations and asymmetric correct/wrong returns. Original Full/Heavy model inference and real-landmark native parity remain enabled.

## Threshold search and rejected candidate

The automatic search evaluates **27** configurations over **10,692 generated training cases**, then checks the current and selected settings on **396 checking cases each** with different seeds/frame rates. These are different seeds from the same generator family, not an independent human test.

The selected synthetic candidate changes acceptable allowance from 12° to 16° and fault confirmation from 160 ms to 120 ms, keeping required range at 85%. It improves generated good acceptance from **105/108 to 108/108** while keeping zero generated bad/uncertain acceptances. Four additional real-video replays cover Full/Heavy and standard/bounded fits using this configuration.

On complete references, the candidate accepts **39 versus 31** outputs. Good-clip accepted outputs rise 24→29; bad-clip accepted outputs rise **7→10**. Whole-clip labels cannot determine which individual outputs are wrong, but they do not justify a precision improvement. The candidate is **not promoted**. Production confidence, angle allowances, range, timing, reconstruction and coverage thresholds remain unchanged.

## Validation and limits

Final scoped validation: **235 JVM tests, 20 Android tests, 2,688 generated regression controls, 124 real-timestamp controlled corruptions, 36 Python QA tests and four research-runtime tests**, plus the parameter search above. A separate seven-test native export captures parity, constructed controls and Heavy camera binding. Exact source intervals, verdicts and reasons are compared against versioned expectations. All three raw MP33 fixtures and the prior range expectations are verified unchanged from baseline.

Runtime checks use the available Windows API 36 x86_64 emulator with 4 KB pages; the historical Fedora runtime in repository instructions is unavailable. Selected-Heavy camera inference is checked before/after pause, under the original 20-second deadline. Signature, ZIP alignment and static native-library 16 KB alignment checks pass. This does not verify a physical phone or an actual 16 KB runtime. Test fixtures and offline classifier artifacts remain excluded from the app APK. No app-wide lint pass is claimed; the previously documented unrelated UI lint issue remains outside this change.

An initial new unit assertion expected zero ambiguity at every high-cadence early-return sample. It exposed that a valid return can begin before the larger reversal excursion threshold is reached. The implementation now permits a fully compatible return branch after an observed decline from the confirmed far endpoint, while keeping reversal confirmation unchanged. The same strict unit assertion and all earlier controls then pass. The native parity expectations are updated separately because the intended new policy changes the LEFT press output and no longer needs its earlier expanded envelope; no raw fixture or original expected-output file was edited.

**Real-world correct-rep precision remains unmeasured. The 98% target is not established.** Whole-clip labels, self-replay and synthetic/corrupted streams cannot supply per-rep human-form truth. Tracking error, occlusion, camera perspective and incomplete cycles still limit coverage. Six explicitly configured qualified policies remain: squat, biceps curl, push-up, overhead press, glute bridge and lateral raise. Real-video development coverage includes the first four only. No every-workout or universal-mobile-device claim is made.

## Reproduction

Use the existing verified Full/Heavy assets and original MP33 caches. No network retraining service is used.

```powershell
$env:JAVA_HOME='D:/android_studio/jbr'
./gradlew.bat :core-motion:verifyMotionCore :core-motion:qualifiedRepEvidenceBenchmark :core-motion:qualifiedRepBenchmark :core-motion:qualifiedRepPathBenchmark :core-motion:qualifiedRepDepartureBenchmark :core-motion:qualifiedRepCounterfactualBenchmark checkCoreBoundaries --no-parallel
./gradlew.bat :validation-app:testDebugUnitTest :android-pose-mediapipe:testDebugUnitTest :validation-app:assembleDebug :validation-app:assembleDebugAndroidTest '-PincludeHeavyPoseModel=true' '-PqualifiedReferenceReplayRoot=test-artifacts/exercise-knowledge' --no-parallel
./gradlew.bat :android-pose-mediapipe:connectedDebugAndroidTest :validation-app:connectedDebugAndroidTest '-PincludeHeavyPoseModel=true' --no-parallel
```

The eight main real-video replay reports cover capped/complete references, Full/Heavy and standard/bounded fits using `:core-motion:qualifiedRepVideoReplay`. Four candidate reports additionally set `qualifiedReplayConfig` to the generated parameter-search report. `scripts/summarize_multipass_pose_tuning.py` verifies matching hashes, frame counts and time ranges, freezes reference-only selection and reports all outcome changes. `scripts/export_v5_parity_expectations.py` binds native parity to the unchanged raw fixture identities. The delivery archive contains report hashes, exact logs, test XML, parameter candidates, visual provenance and APK checks.
