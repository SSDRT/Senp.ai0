# Autonomous tuning: consistent peak support at rep departure

23 October 2026. Branch `codex/live-pose-accuracy`; baseline `8a01f1c`.

This iteration fixes a causal peak-initialization error in the live qualified-rep engine and reference-cycle compiler. No new human labels or recordings were required. The uploaded reference remains the target. This changes the reference comparator, not the MediaPipe network weights.

## Behavior

Previously, an attempt initialized its maximum progress directly from the first departure frame. Subsequent peaks required adjacent observations. An isolated, confident error in that first frame could therefore invent a high peak and put the engine into its returning phase before the real outbound movement. It could hide an actual shallow reversal or make a complete observed movement unassessable. Reference calibration could split its outbound/return branches at that same unsupported frame.

The initial peak now uses the earlier compatible driver observation and the departure observation. Later samples retain their existing adjacent-support rule. Reference-cycle selection uses the same rule, so one departure error cannot move its selected turnaround ahead of the observed path. Stored previous progress is cleared on reset, evidence/context failure, timeout and finish.

A hard early-reversal fault also requires a supported excursion through the existing 20% departure boundary. A single departure spike followed by ready-position noise cannot prove an incomplete physical repetition. Without sufficient excursion or other evidence it remains unassessable. Confirmed shallow motion still produces the existing latched fault.

Policy identity advances to `qualified-reference-reps/3`. Existing angle tolerances, confidence cutoffs, excursion and completion boundaries, confirmation durations, visibility requirements, six-node curves and 80% self-reconstruction gate remain unchanged. Direct intermediate observations in both directions are still mandatory. All six policies—squat, biceps curl, push-up, overhead press, glute bridge and lateral raise—use this correction, with selected-side and mirrored mappings. Qualified counting remains explicitly configured and experimental.

## Generated before/after evidence

The new benchmark runs six policies × two selected sides × two mirror mappings × four cadences (10/15/30/60 FPS) × four scenarios: 384 executions. The spike scenarios replace exactly the first post-ready observation with normalized progress 0.98. All later source observations are unchanged. The declared oracle specifies complete movement or a 45% shallow movement; it does not supply human technique labels.

| Measure | Baseline | Corrected |
| --- | ---: | ---: |
| Premature return-phase transitions in spike scenarios | 192/192 | 0/192 |
| Complete spike scenarios counted | 0/96 | 96/96 |
| Shallow spike scenarios rejected | 0/96 | 96/96 |
| Clean complete controls counted | 96/96 | 96/96 |
| Clean shallow controls rejected | 96/96 | 96/96 |
| Benchmark failures | 192/384 | 0/384 |

The baseline benchmark intentionally fails; its report/log is preserved. The final benchmark passes. These are specific corruption-resilience results, not a general accuracy percentage. The existing 1,152 generated mechanism scenarios and 576 path-observation scenarios also pass.

Four additional JVM tests cover isolated departure errors, preserved genuine adjacent peaks, unsupported departure followed by ready jitter, and reference compilation followed by acceptance of a separate clean cycle. An existing reference test now demonstrates the endpoint-only counterfactual successfully reconstructing a jump after the branch-splitting correction; production still withholds that reference through its independent observed-path/self-reconstruction gates. This is not a weakened production gate.

## Original-video replay and inspection

Both pinned Full and Heavy caches were replayed on the same 48 development videos: 16,633 original observations and 96 configurations per backend. Source/reference hashes, frame counts and original timestamp bounds are checked. Reference selection uses reference calibration alone; source clip outcomes do not choose the model.

| Full-first/Heavy-fallback measure | Baseline | Corrected |
| --- | ---: | ---: |
| Usable configurations | 26/96 | 26/96 |
| Usable unique reference/sides | 9/32 | 9/32 |
| Correct + acceptable counts | 16 (3 + 13) | 16 (3 + 13) |
| Rejected events | 50 | 46 |
| Unassessable events | 142 | 144 |
| Total finalized events | 208 | 206 |
| Independently measured live-rep precision | Unknown | Unknown |

All previously counted events retain their start/end times and verdicts. Some supported maxima, reversal/fault times and rejected/uncertain event boundaries change; the comparison JSON preserves every difference. Two earlier fragments merge into longer uncertain attempts. Four earlier hard-rejection decisions no longer have sufficient supported peak/reversal evidence. This is a change in evidence handling, not proof that four human faults were false positives. Accepted outputs remain nine in good-labelled clips and seven in bad-labelled clips; whole-clip labels cannot establish individual-rep truth.

Full and Heavy each still compile 20/96 configurations. The fallback registry remains seven Full references, two Heavy references and 23 unavailable reference/sides. Four unavailable Full runs now fail missing critical milestone samples rather than insufficient cycles: corrected peak selection exposes a later fitting-stage failure. This does not increase calibration success.

Sixteen original timestamped overlays and four before/after phase plots were inspected. They include a front squat start-frame bump with later tracking loss, a front curl with several isolated normalized-progress jumps, an occluded Heavy squat that becomes uncertain, and the preserved accepted overhead-press output. Model coordinates/progress are diagnostics, not ground-truth anatomy. No pixels, landmarks, confidence values or exercise labels were edited to obtain the results.

## Android verification and fixture maintenance

All 218 scoped JVM cases, 16 native suite cases, 2,112 generated scenarios, 36 Python QA cases and two research-runtime cases passed. The native suite exercises original Full and Heavy landmark fixtures, actual production fallback selection, and Heavy camera model binding through pause/resume. The available runtime is the Windows API 36 x86_64 emulator with 4 KB pages; the repository's historical Fedora setup is unavailable here. Physical-device precision is not established.

The Full native fixture retains identical raw landmark observations, counts, verdicts and event times. Exactly one expected diagnostic maximum changes, for the accepted attempt at 5333–6733 ms: `1.0045220092616203` → `0.9951675399050122`. Its isolated departure value no longer supplies its own peak; later adjacent original observations supply the maximum. This change and the fixture hash are recorded in `models/qualified_rep_fixture.provenance.json`. The Heavy fixture is unchanged.

The experimental APK bundles both hash-pinned official models. Signature, ZIP/native-library alignment and static 16 KB checks pass. No actual 16 KB device run or broad handset compatibility claim is made. Test fixtures and offline classifier artifacts are excluded from the app. The previously recorded unrelated UI lint failure remains outside this tuning change; no new app-wide lint pass is claimed.

The 98% precision target remains unestablished. Further tuning needs better geometry/uncertainty under occlusion, view/person changes and adjacent correlated landmark errors, plus task-compatible independent existing repetition labels under usable licences. This iteration imports no new datasets or trained derivatives and requires no new human annotation step.

## Reproduction

```powershell
$env:JAVA_HOME='D:/android_studio/jbr'
.\gradlew.bat :core-motion:verifyMotionCore :core-motion:qualifiedRepDepartureBenchmark :core-motion:qualifiedRepBenchmark :core-motion:qualifiedRepPathBenchmark checkCoreBoundaries --no-parallel
.\gradlew.bat :core-motion:qualifiedRepVideoReplay '-PqualifiedReplayInput=test-artifacts/exercise-knowledge/rep-world-feature-cache' '-PqualifiedReplayOutput=test-artifacts/exercise-knowledge/departure-peak-full-replay' --no-parallel
.\gradlew.bat :core-motion:qualifiedRepVideoReplay '-PqualifiedReplayInput=test-artifacts/exercise-knowledge/autonomous-heavy-cache' '-PqualifiedReplayOutput=test-artifacts/exercise-knowledge/departure-peak-heavy-replay' --no-parallel
.\gradlew.bat :validation-app:testDebugUnitTest :android-pose-mediapipe:testDebugUnitTest :validation-app:assembleDebug :android-pose-mediapipe:connectedDebugAndroidTest :validation-app:connectedDebugAndroidTest '-PincludeHeavyPoseModel=true' '-PqualifiedReferenceReplayRoot=test-artifacts/exercise-knowledge' --no-parallel
test-artifacts/exercise-knowledge/training-venv313/Scripts/python.exe scripts/summarize_departure_peak_tuning.py
test-artifacts/exercise-knowledge/training-venv313/Scripts/python.exe scripts/render_departure_peak_audit.py
```

Video replays and summaries require the existing ignored source caches. To reproduce the old benchmark, apply only the new benchmark class/task to baseline `8a01f1c` in an isolated checkout and run `qualifiedRepDepartureBenchmark` with `-PqualifiedDepartureOutput=...`. It should report 192 failures. No production ablation switch is added for the initialization bug. The baseline/new reports and their hashes accompany the delivery.
