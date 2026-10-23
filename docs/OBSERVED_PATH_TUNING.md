# Autonomous tuning: observed movement paths

23 October 2026. Same branch `codex/live-pose-accuracy`; baseline `8ccfb0b`.

This iteration hardens qualified counting against confident endpoint jumps while preserving the existing development-video results. It also evaluates a finer reference-curve candidate. New human annotations/recordings: zero. Landmark CNN weights were not retrained; this is tuning of the causal reference comparator. The uploaded reference remains the movement target.

## What changed

Previously, ready-position confirmation, a sustained far endpoint and confirmed return could qualify a rep even when no intermediate movement was observed. Two endpoint poses could therefore supply an apparent complete cycle. The new default requires at least one directly observed intermediate pose outbound and one returning, with every required selected-side feature meeting the existing confidence gate. Interpolation and repaired coordinates cannot provide these observations.

Outbound evidence must occur after the engine is armed and before the far milestone. Importantly, visible motion between the 10% ready boundary and the 20% attempt-start boundary is retained. Discarding it would lose legitimate early movement at ordinary camera cadences. Returning to ready, tracking loss, missing critical evidence, evidence gaps, timeout, capture/subject changes, reset or session termination discard stale evidence. A tentative reversal that is abandoned cannot supply evidence for a later return. Return evidence must follow a confirmed far endpoint; revisiting that endpoint clears an earlier return witness.

An attempt with missing path evidence is **unassessable**, with reason `INSUFFICIENT_PATH_EVIDENCE`; it is not described as a proven technique fault. Actual form/range faults retain their existing confirmation and latching behavior. Attempt diagnostics now report whether each direction was observed. Counts still require the original endpoint, visibility, ambiguity, form, range and provenance checks.

The rule applies to all six existing qualified-counting policies: squat, biceps curl, push-up, overhead press, glute bridge and lateral raise, on the selected side with direct or mirrored mapping. No exercise auto-classifier or new UI was added. Qualified counting remains explicitly configured and experimental. Generic reference comparison retains its existing behavior.

Reference-policy identity advances to `qualified-reference-reps/2`. The default path gate and curve resolution are included in configuration identity. `requireObservedPath=false` is retained for reproducible endpoint-only ablation, not enabled by the product UI. Angle allowances, range requirements, confidence cutoffs, confirmation times and the 80% reference self-reconstruction requirement remain unchanged.

## Generated before/after checks

The new benchmark runs all six policies × two selected sides × two mirror mappings × four cadences (10/15/30/60 FPS). Three incomplete-observation scenarios and three complete-motion speeds produce 576 executions. Endpoint jumps, missing outbound observation and missing return observation have no complete directly observed path under the declared engineering oracle. They are not human form labels.

| Measure | Endpoint-only counterfactual | Final path gate |
| --- | ---: | ---: |
| Unsupported counts in 288 incomplete-observation controls | 288 | 0 |
| Counted complete-motion controls | 288/288 | 288/288 |
| Hard technique rejections in incomplete-observation controls | 0 | 0 |
| Incomplete-observation controls left unassessable | 0 | 288 |

This is elimination of a specific generated counting shortcut, not 100% human precision. The existing 1,152 generated causal-rep scenarios also pass, including clear faults, shallow reversals, uncertainty, missing evidence, jitter and timing cases. No speed cap or invented joint coordinates were used to obtain the result.

Additional tests cover early evidence before departure, discarding abandoned early motion, discarding abandoned partial returns, single intermediate observations at ordinary cadence, reference self-calibration, raw MP33 adapter/session integration, and reference-key invalidation.

## Real-video regression

Replay both pinned models on the same 48 development videos, retaining all 16,633 original timestamps per backend and all raw image/world landmarks and confidence. Compare all 96 source/reference configurations per model against the frozen earlier replays. Availability, counts, source intervals, frame counts, event boundaries, verdicts, maximum progress and coverage fractions match exactly under the final policy. One already-unassessable Full attempt additionally reports `INSUFFICIENT_PATH_EVIDENCE` alongside its existing `AMBIGUOUS_FORM` reason. The final diagnostics add path-observation flags; reference-policy hashes intentionally change.

| Measure | Previous delivery | Final policy |
| --- | ---: | ---: |
| Full usable configurations | 20/96 | 20/96 |
| Heavy usable configurations | 20/96 | 20/96 |
| Full-first/Heavy-fallback configurations | 26/96 | 26/96 |
| Unique usable reference/sides with fallback | 9/32 | 9/32 |
| Counted outputs with fallback | 16 | 16 |
| Independent live-rep precision | Unknown | Unknown |

The frozen whole-clip metadata buckets also remain unchanged: nine counted outputs occur in good-labelled clips and seven in bad-labelled clips. A clip label cannot identify which individual reps are correct. This iteration does not show a measured reduction in real-video false positives or an improvement toward 98% precision. The gain demonstrated here is observation-path robustness without a detected regression on this development cohort.

Eight timestamped source/model overlays and a focused progress plot were inspected. In a real overhead-press example, visible early observations at 5200 and 5267 ms occur before the attempt begins at 5333 ms. The final policy retains them. A stricter prototype discarded this evidence and withheld that previously accepted output. Images show actual arm movement as well as model disagreement/occlusion; the engine's normalized progress is not a ground-truth anatomical angle.

## Candidates evaluated but not enabled

Ten reference-curve nodes, versus six, reduce interpolation error on a generated nonlinear reference test. They do not increase real calibration coverage. Under Full, counted outputs change 12→14, including changes in both clip-label classes (5→6 good-labelled, 7→8 bad-labelled). Under Heavy, counts remain 15. These weak-label results do not justify changing the live default. Six nodes remain enabled; ten nodes remain an explicit reproducible research option.

A two-separated-band path prototype reduces reference availability and acceptance on the development recordings. A one-intermediate prototype without retaining pre-departure evidence also loses a previously accepted real output. Those prototypes are recorded as discarded experiments; their historical replay reports are retained. The maintained policies are the final intermediate-observation rule and its endpoint-only counterfactual.

## Verification and delivery

All 214 scoped JVM cases, 16 native suite cases, the 1,152 original generated scenarios, 576 new observation-path scenarios, 36 Python QA cases and two research-runtime cases passed. The delivery also includes production reference-backend selection replay, Full/Heavy source replay summaries, original source overlays and provenance. Native checks verify the original Full fixture and Heavy-only fallback fixture with observed-path flags, plus the selected Heavy camera model through pause/resume.

An additional evidence-export run initially failed the existing 20-second Heavy camera startup check, producing two frames while another replay was active. The final sequential native suite and isolated three-case export rerun passed. Both logs are retained; the timeout was not widened or suppressed. This is emulator/runtime variability, not evidence of phone performance.

The APK bundles both hash-pinned official models. Test-only landmark fixtures and trained whole-clip research artifacts are excluded from the application. Signature, ZIP/native-library alignment and emulator checks accompany it. The available device is a Windows API 36 x86_64 emulator with 4 KB pages; no handset or actual 16 KB runtime guarantee is implied. The existing app-wide UI lint error at `VideoEditorScreen.kt:608` is unchanged from the previous delivery; this cycle does not suppress or claim to fix it.

## Independent-data research and next tuning work

Checked original publisher terms rather than relying on repository descriptions. [REHAB24-6's publisher record](https://zenodo.org/records/13305826) describes segmented repetitions and correctness labels, but its stated terms restrict use to noncommercial academic/nonprofit research. No dataset files or trained derivatives from it were added to this app. The [UI-PRMD dataset paper](https://doi.org/10.3390/data3010002) identifies a public-domain data licence; its original download host was unavailable to the research browser in this run, so no data was imported or evaluated. Skeleton formats and exercise variants would require explicit validated adapters even if accessible.

Next priorities remain reliable geometry and uncertainty under camera/view/person changes, separately qualified image-space versus world-space measurements, and evaluation against task-compatible existing independent annotations under usable licences. Keep participant/video splits frozen, preserve uncertainty and all failures, and distinguish generated controls, clip labels and independent form labels. The 98% live precision target remains unestablished. No new human labeling step is required to continue autonomous research.

## Reproduction

```powershell
$env:JAVA_HOME='D:/android_studio/jbr'
.\gradlew.bat :core-motion:verifyMotionCore :core-motion:qualifiedRepBenchmark :core-motion:qualifiedRepPathBenchmark checkCoreBoundaries --no-parallel
.\gradlew.bat :core-motion:qualifiedRepVideoReplay '-PqualifiedReplayInput=test-artifacts/exercise-knowledge/rep-world-feature-cache' '-PqualifiedReplayOutput=test-artifacts/exercise-knowledge/observed-path-final-full-replay' --no-parallel
.\gradlew.bat :core-motion:qualifiedRepVideoReplay '-PqualifiedReplayInput=test-artifacts/exercise-knowledge/autonomous-heavy-cache' '-PqualifiedReplayOutput=test-artifacts/exercise-knowledge/observed-path-final-heavy-replay' --no-parallel
# Ablation options: -PqualifiedRequireObservedPath=false; -PqualifiedReferenceCurveNodes=10
.\gradlew.bat :validation-app:testDebugUnitTest :android-pose-mediapipe:testDebugUnitTest :validation-app:assembleDebug :android-pose-mediapipe:connectedDebugAndroidTest :validation-app:connectedDebugAndroidTest '-PincludeHeavyPoseModel=true' '-PqualifiedReferenceReplayRoot=test-artifacts/exercise-knowledge' --no-parallel
```

The local replay/audit tools require ignored source caches and videos from earlier cycles. `scripts/summarize_observed_path_tuning.py` compares final results with frozen baseline/prototype reports; `scripts/render_observed_path_audit.py` renders the focused original-frame evidence. No source videos or model binaries are committed to Git.
