# Reference curve recovery

This iteration adds an outlier-resistant reference-curve fallback to the existing uploaded-reference/live comparison. It does not retrain MediaPipe weights. On identical complete-reference inputs, usable calibration runs rise from **36/96 (37.50%) to 38/96 (39.58%)**, an increase of **2.08 percentage points / 5.56% relative**. Distinct usable reference/side configurations rise from **11/32 to 12/32**. The original capped-input cohort stays **30/96 (31.25%)**. These cohorts overlap and must not be pooled as independent samples.

All 36 previously usable complete-input runs and all 30 previously usable capped-input runs preserve their reference keys, source counts, attempt intervals, reasons and fault outputs. No reference/source input changes in this iteration: 16 complete references contain 7,191 observations per model; the same 48 source recordings contain 16,633 observations per model.

## Implementation

The production selector uses reference evidence only, in this order:

1. Full with the configured standard curve fit.
2. Heavy with the same configured fit.
3. Full with bounded median pairwise slopes, if its standard fit failed `WEAK_REFERENCE` or `LOW_SELF_RECONSTRUCTION`.
4. Heavy with bounded median pairwise slopes, under the same eligibility restriction.

Both established model fits take priority over curve recovery. The selected model remains bound to the reference and live session. Curve retries reuse the model-specific extraction and never examine source/live outcomes or whole-clip labels. Missing critical landmarks, insufficient cycles, insufficient excursion, unresolved guidance and invalid provenance are not repaired by this estimator. Cancellation is checked around compilation.

For non-driver reference features, the robust estimator uses the median slope of sufficiently separated progress pairs. It deterministically selects at most 128 original observations per phase/feature bin, bounding the quadratic portion at 8,128 slopes. Every original observation still contributes to reference distributions, confidence and causal self-replay. Driver slope remains fixed by its observed endpoints. The original minimum 80% self-reconstruction, confidence, timing, coverage, form allowances and observed outbound/return path gates remain in force. Learned reference curves can change on recovery.

Selection is versioned `qualified-reference-selection/2`; reference schema stays `qualified-reference-reps/4`. The fit mode participates in the reference key. Existing standard-fit keys remain unchanged. The earlier unbounded `MEDIAN_PAIRWISE` option remains a research setting; automatic recovery uses the new `BOUNDED_MEDIAN_PAIRWISE` mode.

## Measured behavior and limits

The complete `subject_005_shoulder_good_front.mp4` LEFT reference previously failed both model fits. Heavy bounded fitting now reconstructs **9/9 observed reference cycles**, passing the unchanged 80% gate. This restores a reference lost when the research input was expanded beyond its former 24-second cap in the previous iteration. No extraction-duration setting changes here.

Across complete-input source replays, accepted engine outputs rise **25 to 31**, rejected outputs **61 to 62**, and unassessable outputs **178 to 185**, because two more source runs can now be evaluated. On the exported source fixture, Android reproduces **5 acceptable, 1 rejected and 4 unassessable** outputs, including exact intervals/reasons and observed path evidence.

These are engine outputs, not measured correct repetitions. Accepted outputs on whole-clip good-labelled recordings rise 19 to 24; those on bad-labelled recordings rise **6 to 7**. Whole-clip labels do not say which individual repetitions are wrong. Consequently this experiment establishes improved calibration availability, **not improved real-world rep precision**. Precision remains unmeasured; the 98% target is not achieved or substantiated. Repeated model bias and camera/view ambiguity remain unresolved.

A global robust-fit replacement was rejected: the unbounded median experiment recovers this reference but loses another press reference that the established fit handles. Ten-node curves provide no additional combined availability. This motivates preserving both standard model fits before trying recovery.

Support remains six explicitly configured qualified-rep policies: squat, biceps curl, push-up, overhead press, glute bridge and lateral raise. Existing real-video development coverage contains the first four only. Neither every-workout accuracy nor compatibility across every mobile device is claimed. No new human labels, videos or independent subjects were added.

## Verification and review

The final scoped suites pass: **228 JVM cases, 18 Android suite cases, 2,112 generated mechanism scenarios, 36 Python QA cases and four research-runtime cases**. A separate five-case native evidence export also passes. Tests verify the production selector against all 32 reference/side configurations in each cohort, exact reference keys, standard-first attempt order, unchanged self-reconstruction threshold and cached extraction reuse. A 100,000-point contaminated-curve test covers the bounded estimator and stationary/empty inputs.

Original Full/Heavy MP33 landmarks, image/world coordinates, visibility, presence and source timestamps are preserved. The new CC BY 4.0 native fixture contains landmarks only; its hashes, model identities and source association are recorded in `models/robust_reference_fixture.provenance.json`. Existing native fixtures are unchanged. App APKs exclude test fixtures and offline classifiers.

Sixteen original timestamped frames and raw angle traces were rendered and inspected. The recovered reference shows repetitive presses; Full exhibits more isolated elbow-angle jumps than Heavy. The accepted source interval at 10.933–12.200 seconds visibly traverses a press and return; the rejected 1.133–1.467 second interval shows setup/bending. This is a visual sanity check, not anatomical truth or independent form annotation. Audit PNG/JPEG and timestamp/hash provenance are included in the delivery evidence.

Runtime verification uses the available Windows API 36 x86_64 emulator with 4 KB pages; the historical Fedora environment is unavailable. Real Full/Heavy inference, timestamp/landmark checks, production parity and selected-Heavy camera pause/resume tests pass. Camera deadlines are unchanged. APK signature, ZIP alignment and static native-library 16 KB checks pass; no physical-handset or actual 16 KB runtime claim is made. No app-wide lint pass is claimed; the previously documented unrelated UI lint issue is unchanged.

The first build caught a JUnit assertion argument-order error in the new instrumentation test; it was corrected without changing expectations. Initial Windows Python QA attempts lacked the module path, then failed to execute POSIX test fixtures. The default WSL distribution also had an older Python without dictionary union support. Running the unchanged suite under Ubuntu 24.04 WSL with `PYTHONPATH=qa` passes all 36 cases. These were test setup failures, not measured engine regressions.

## Reproduction

Use Java 21 and the existing pinned Full/Heavy assets and hash-verified landmark caches. The scripts use licensed development data held outside Git.

```powershell
$env:JAVA_HOME='D:/android_studio/jbr'
./gradlew.bat :core-motion:verifyMotionCore :core-motion:qualifiedRepBenchmark :core-motion:qualifiedRepPathBenchmark :core-motion:qualifiedRepDepartureBenchmark checkCoreBoundaries :validation-app:testDebugUnitTest :android-pose-mediapipe:testDebugUnitTest :validation-app:assembleDebug :validation-app:assembleDebugAndroidTest '-PincludeHeavyPoseModel=true' '-PqualifiedReferenceReplayRoot=test-artifacts/exercise-knowledge' --no-parallel
./gradlew.bat :android-pose-mediapipe:connectedDebugAndroidTest :validation-app:connectedDebugAndroidTest '-PincludeHeavyPoseModel=true' --no-parallel
```

Generate per-model bounded-fit replay reports using `:core-motion:qualifiedRepVideoReplay` with `-PqualifiedReferenceCurveFit=BOUNDED_MEDIAN_PAIRWISE`; retain standard-fit reports separately. Set `qualifiedReplayInput`, `qualifiedReplayOutput` and `qualifiedReplayReferences` to the respective original source cache, report directory and capped/complete reference cache. `scripts/summarize_reference_fit_recovery.py` freezes the reference-only choices, hashes the eight input summaries and compares every previously usable result. `scripts/render_reference_fit_audit.py` renders original-frame evidence. See the delivered evidence archive for exact reports, test XML, APK checks and provenance.
