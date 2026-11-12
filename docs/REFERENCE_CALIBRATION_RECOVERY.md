# Reference calibration recovery

23 October 2026. Branch `codex/live-pose-accuracy`; baseline `053ea72`.

Calibration availability improves from **26/96 (27.1%) to 30/96 (31.3%) on the exact existing reference/source inputs**. That is four additional replay configurations, one additional distinct usable reference/side, +4.17 percentage points and +15.4% relative availability. A separate complete-reference experiment reaches **36/96 (37.5%)**, with additional original reference footage. Neither number measures independent human-rep accuracy. No new human annotation, recording, dataset download or landmark-network training was used.

## Enabled change

The comparator previously used median reference endpoints as both progress anchors and the driver's admissible global range. Exact progress anchoring intentionally removes the driver's phase-dependent residual variation; its per-phase MAD is consequently almost zero. A small but repeatedly demonstrated endpoint variation could therefore be absent from the admissible range, causing a reference to fail its own causal replay.

The original fit remains first choice. If its profile passes the existing confidence/noise/feature checks but fails causal self-reconstruction, calibration can try a repeated observed driver range:

- Use only complete selected reference cycles, with the original minimum of three cycles.
- Compute each cycle's low/high driver endpoints from adjacent original observations; both observations must meet the existing confidence gate for every required feature. Repaired or interpolated points cannot establish an endpoint.
- Use the second-lowest low endpoint and second-highest high endpoint. Each expanded edge must therefore have support in at least two different reference cycles. One anomalous cycle cannot widen either edge. There is no extrapolation beyond these observed supported endpoints.
- Keep the existing endpoint-distribution MAD, width, inlier-count and outlier-fraction limits. No noisy or missing reference is forced through them.
- Replay every selected cycle again through the same causal engine. The unchanged **80%** self-reconstruction requirement, one-to-one event matching and 300 ms matching tolerance must pass.

This changes learned admissible driver bounds when warranted by repeated reference evidence. It does not change the configured angle allowances, confidence thresholds, progress/completion boundaries, confirmation durations, path requirements or visible/ambiguous coverage limits. Progress anchors remain the original medians, avoiding endpoint drift. Source anatomical target bands take precedence over the learned driver range. A reference that already qualifies keeps its original curves and behavior.

Policy identity is `qualified-reference-reps/4`, including the learned range and configuration. Runtime and diagnostics share the same effective-band calculation. The reference stores the original and recovered self-accepted cycle counts, learned bounds, contributing cycle count and endpoint MADs. No live/source outcomes or labels enter reference fitting or backend selection. The six existing explicit selected-side qualified-counting policies remain the supported scope.

## Fixed-input comparison

Replay the same 48 development source recordings, 16,633 source observations per model, all original timestamps and the same truncated reference observations used by the previous delivery. Evaluate all 96 configurations per backend; Full remains first choice, with Heavy fallback selected solely by reference calibration.

| Measurement | Previous | Repeated-range fallback |
| --- | ---: | ---: |
| Full usable runs | 20/96 | 20/96 |
| Heavy usable runs | 20/96 | 24/96 |
| Combined usable runs | 26/96 (27.1%) | 30/96 (31.3%) |
| Combined usable distinct reference/sides | 9/32 | 10/32 |
| Qualified outputs | 16 | 17 |
| Rejected outputs | 46 | 46 |
| Unassessable outputs | 144 | 168 |

All **26 previously usable runs** retain exactly the same counts, attempt boundaries, verdicts, reasons, progress/coverage values and fault events. Policy hashes intentionally change. The added usable reference is `subject_001_shoulder_good_front.mp4`, LEFT, Heavy. Its selected-cycle self-reconstruction increases from **3/5 to 4/5**, meeting the same 80% gate.

Its median driver anchors remain 81.3654°/169.4901°. The learned admissible range is 79.8933°–171.1941°, extending the edges by about 1.47° and 1.70°. These are model-derived reference measurements, not independently verified anatomical angles. One observed return sample at 16067 ms lies inside the new range plus the unchanged allowance, whereas the old median-only range excluded it.

The four newly usable source runs add one acceptable output and 24 uncertain events. That additional counted output occurs in a good-labelled clip, but a whole-clip label does not certify that individual rep. This is not evidence that precision rose by 15.4% or that the 98% target was achieved.

An experimental median-pairwise curve-fit candidate was also evaluated on both models. It increases standalone Heavy availability to 22/96, but that reference was already served by Full. Combined availability stays 26/96. Ordinary least squares remains the default; median-pairwise fitting is an explicit research option only.

## Separate complete-reference experiment

The research RGB decoder previously stopped at 24 seconds. Fourteen of the 16 reference recordings are longer, generally about 29–34 seconds. Re-extract every complete reference with each pinned model, including the two shorter recordings. Each model gains **1,581 original reference observations: 5,610 → 7,191**. Prefix timestamps, detection patterns and all 33 image/world/confidence landmark fields match the earlier extraction exactly; maximum prefix numerical difference is zero on this host.

The source replay clips and their 16,633 timestamps stay fixed. Complete references are loaded separately, preventing the new tail from accidentally changing source evaluation intervals. Duration limit is now an explicit extraction/cache identity field: default 24000 ms preserves existing experiments; `None` requests the complete recording. A capped or legacy cache cannot masquerade as an uncapped cache.

| Reference evidence | Previous fit | New repeated-range fallback |
| --- | ---: | ---: |
| Earlier capped inputs | 26/96 (27.1%) | 30/96 (31.3%) |
| Complete original references | 28/96 (29.2%) | 36/96 (37.5%) |

With complete references, the new policy serves 11/32 distinct reference/sides and emits 25 acceptable, 61 rejected and 178 uncertain events. These totals cover a different set of usable comparisons and are not a precision comparison with the capped-input counts. Whole-clip metadata buckets are 19 counted outputs in good-labelled clips and six in bad-labelled clips; neither bucket establishes per-rep correctness.

Compared with the previous capped-reference registry, complete-reference recovery adds the LEFT front curl, LEFT side push-up and LEFT front press references from subject 001. It also **loses** the previously usable LEFT front press reference from subject 005: its complete-input curves fail reliability checks. The failure is retained. Longer evidence can reveal inconsistency, so more footage does not guarantee that every individual calibration improves.

The Android extractor already processes complete uploads. **This was a research extraction cap, not an app truncation bug.** The 37.5% result combines additional evidence with the new fitting policy and must be reported separately from the +4.17-point fixed-input algorithm gain.

## Verification

All 226 scoped JVM cases, 17 Android suite cases, 2,112 existing generated scenarios, 36 Python QA cases and four research-runtime cases pass. Eight new core tests cover repeated edge support in both driver directions, isolated endpoint outliers, noisy/short/malformed evidence, unchanged qualified references, successful recovery followed by clean/shallow/clear-fault source motions, missing evidence, policy identity and the experimental pairwise estimator. Python tests exercise actual FFmpeg decoding beyond 24 seconds, preserved prefix pixels/timestamps and cache invalidation for capped/uncapped/legacy entries.

Native replay reuses the unchanged Heavy raw landmark fixture with LEFT-side expectations. It verifies that the old policy withholds the reference at 3/5, the new policy accepts it at 4/5, the threshold remains 80%, actual production Full-to-Heavy selection succeeds, and source counts/intervals/reasons match JVM results. The existing Full/Heavy native parity and Heavy camera pause/resume tests remain enabled. A separate four-case native export run also passes.

The first new native test failed because its expected source was incorrectly associated with another source video. The source-hash assertion caught that mismatch before replay. The exporter now derives the source name and hashes directly from the unchanged raw fixture (`subject_018_shoulder_good_front.mp4`) and verifies them against the replay. Corrected expectations and final native runs pass; the initial failure log is retained. No raw fixture observations, existing goldens, production thresholds or test deadlines were changed to fix it.

Sixteen original timestamped frames and four measurement plots were inspected, including the recovered press return, post-24-second curl/push-up observations and the complete-reference press failure. They show real motion, visibility limitations and inferred-angle jumps. They are diagnostics, not human form annotations.

The available Android runtime is the Windows API 36 x86_64 emulator with 4 KB pages; the historical Fedora environment in repository instructions is unavailable here. The experimental APK bundles both hash-pinned official pose models. Signature, ZIP alignment and static native-library 16 KB alignment pass; no physical-device or actual 16 KB runtime guarantee is made. Test-only fixtures/expectations and offline classifier artifacts are excluded from the app. The previously recorded unrelated app UI lint failure is unchanged; no app-wide lint pass is claimed.

Independent live-rep precision remains unknown. Missing/occluded joints, correlated model errors, camera/view/person changes, unreliable curves and insufficient complete repetitions remain open calibration limits. Further autonomous work can use existing appropriately licensed independent per-rep labels and stronger measurement methods without requesting new human annotation.

## Reproduction

```powershell
$env:JAVA_HOME='D:/android_studio/jbr'
.\gradlew.bat :core-motion:verifyMotionCore :core-motion:qualifiedRepBenchmark :core-motion:qualifiedRepPathBenchmark :core-motion:qualifiedRepDepartureBenchmark checkCoreBoundaries --no-parallel
# Fixed-input policy replay; use -PqualifiedObservedDriverEnvelope=false for the counterfactual.
.\gradlew.bat :core-motion:qualifiedRepVideoReplay '-PqualifiedReplayInput=test-artifacts/exercise-knowledge/rep-world-feature-cache' '-PqualifiedReplayOutput=test-artifacts/exercise-knowledge/calibration-envelope-full-replay' --no-parallel
.\gradlew.bat :core-motion:qualifiedRepVideoReplay '-PqualifiedReplayInput=test-artifacts/exercise-knowledge/autonomous-heavy-cache' '-PqualifiedReplayOutput=test-artifacts/exercise-knowledge/calibration-envelope-heavy-replay' --no-parallel
test-artifacts/exercise-knowledge/training-venv313/Scripts/python.exe scripts/extract_complete_reference_corpus.py --model full --workers 3
test-artifacts/exercise-knowledge/training-venv313/Scripts/python.exe scripts/extract_complete_reference_corpus.py --model heavy --workers 3
# Repeat replay with -PqualifiedReplayReferences=test-artifacts/exercise-knowledge/complete-reference-full-cache
# or complete-reference-heavy-cache. Use the matching model; preserve the original source input directory.
test-artifacts/exercise-knowledge/training-venv313/Scripts/python.exe scripts/summarize_calibration_recovery.py
test-artifacts/exercise-knowledge/training-venv313/Scripts/python.exe scripts/export_reference_range_expectations.py
test-artifacts/exercise-knowledge/training-venv313/Scripts/python.exe scripts/render_calibration_recovery_audit.py
.\gradlew.bat :validation-app:testDebugUnitTest :android-pose-mediapipe:testDebugUnitTest :validation-app:assembleDebug :android-pose-mediapipe:connectedDebugAndroidTest :validation-app:connectedDebugAndroidTest '-PincludeHeavyPoseModel=true' '-PqualifiedReferenceReplayRoot=test-artifacts/exercise-knowledge' --no-parallel
```

The scripts require the existing ignored licensed corpus/caches and research runtime. The summary records all ten baseline/candidate/input-duration replay reports with hashes. It distinguishes fixed-input recovery, the unpromoted curve-fit candidate and full-reference evidence. No videos, model binaries or generated replay/build files are committed.
