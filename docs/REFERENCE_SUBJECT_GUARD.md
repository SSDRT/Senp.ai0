# Single-subject reference provenance

The reference audit exposed a specific integrity problem in baseline `4d89989`: four complete cycles from four different tracked identities, one cycle per person, could be pooled into a reference meeting the three-cycle minimum. Live attempts already terminate on identity changes, but reference preparation did not enforce the same subject provenance rule.

Sequence-based reference compilation now requires one nonblank tracked subject ID on every frame supplying reliable selected-side critical evidence. Multiple IDs return `AMBIGUOUS_REFERENCE_SUBJECT`; missing IDs return `MISSING_PROVENANCE`. Identity ambiguity is fatal to automatic model/curve recovery. Choosing another estimator cannot establish that mixed observations belong to one person. Different spatial segments with the same subject ID remain usable, and all four directly observed same-person cycles still reconstruct in the control.

This checks tracker provenance, not biometric identity. A provider that reuses one ID for different real people can still conceal a swap. The existing single-person MediaPipe adapter generally supplies one stable ID; the guard also protects alternative typed observation providers. The trusted precompiled-profile overload cannot recover subject identities absent from its input. Production uploaded references use sequence compilation.

`QualifiedRepCompiler.VERSION` is now `qualified-rep-compiler/2` and participates in reference-key hashing. All previous reference keys are intentionally invalidated. The live rep state machine and runtime schema `qualified-reference-reps/5` are unchanged. No confidence, range, reconstruction, timing or form threshold is relaxed; no landmark weights are retrained.

Eight real-video replays cover Full/Heavy, standard/bounded fits and capped/complete reference inputs against the same original MP33 data. Reference-only selection is unchanged. Capped availability stays 30/96 and complete availability stays 38/96. Counts, intervals, verdicts, path flags and fault outputs are identical for every previously evaluated run. Only reference keys change. Accepted totals remain 18 capped and 31 complete; bad-clip accepted totals remain seven in each overlapping cohort. No real-world precision increase is claimed.

Verification passes 239 scoped JVM cases, 21 Android cases, 2,688 existing constructed regression controls, 124 controlled-corruption cases, 22 Python acquisition/scoring/research tests and 36 Python QA tests. A separate eight-case native export verifies the mixed-subject refusal, preserved four-cycle single-subject reference, real-landmark parity, compatible-path controls, return-phase controls and Heavy camera pause/resume. Existing raw fixtures and version-five expected outputs are unchanged. Original timestamped source-frame overlays were inspected again; no image/world coordinate or PTS changed. APK signature, ZIP alignment and static native 16 KB checks pass on the available Windows API 36 x86_64 emulator with 4 KB pages. No physical-device or actual 16 KB runtime claim is made; the historical Fedora runtime is unavailable and the earlier unrelated app-wide UI lint issue remains outside scope.

## Readiness goal

The user requested ongoing autonomous improvement to a 70/100 readiness milestone. The fixed rubric and its scoring script are now in `models/readiness-rubric.json` and `scripts/score_readiness.py`. Its baseline is 42.5/100, formalizing the earlier approximate 40/100 estimate. This numerical difference is not an improvement. The identity guard does not earn extra points: the existing mechanical-validation category already has its maximum weight. Actual live correct-rep precision remains unmeasured.

The original delivered baseline is captured from its hash-verified immutable delivery manifest, comparison and archived reference registry, rather than inferred from later test files. Tests prevent a changed reference denominator, changed weights/target/policies, duplicate API runtime entries, nonfinite metrics, seen test subjects, insufficient independent labels and missing soak evidence from inflating the score. A reported raw score above 70 is capped at 69 until an independent-label domain meets the frozen gates.

UI-PRMD publisher archives have been acquired with pinned sizes, publisher MD5 checks and local SHA-256 receipts for an independent skeletal-label benchmark. The subject split and exercise variants are frozen before opening validation/test trajectories. The publisher's local joint-coordinate format and lower-body visualization warning require a validated measurement adapter; raw local coordinates must not be treated as global MP33 landmarks. Data acquisition alone is not model training, independent precision or earned readiness points. See `models/uiprmd-evaluation-protocol.json` and `docs/READINESS_70_PLAN.md` for the next work.

The persistent goal and the two-hour thread follow-up remain active. Notify a verified 70 milestone only when the fixed rubric and its gates support it. The current run does not reach 70.

## Reproduction

```powershell
$env:JAVA_HOME='D:/android_studio/jbr'
./gradlew.bat :core-motion:verifyMotionCore :core-motion:qualifiedRepReferenceContextAudit :core-motion:qualifiedRepEvidenceBenchmark :core-motion:qualifiedRepBenchmark :core-motion:qualifiedRepPathBenchmark :core-motion:qualifiedRepDepartureBenchmark --no-parallel
./gradlew.bat :core-motion:qualifiedRepCounterfactualBenchmark '-PqualifiedCounterfactualOutput=test-artifacts/exercise-knowledge/readiness-identity-counterfactuals.json' --no-parallel
./gradlew.bat :validation-app:testDebugUnitTest :android-pose-mediapipe:testDebugUnitTest :validation-app:assembleDebug :validation-app:assembleDebugAndroidTest '-PincludeHeavyPoseModel=true' '-PqualifiedReferenceReplayRoot=test-artifacts/exercise-knowledge' '-PqualifiedReferenceReplayRegistry=readiness-identity-summary' --no-parallel
./gradlew.bat :android-pose-mediapipe:connectedDebugAndroidTest :validation-app:connectedDebugAndroidTest '-PincludeHeavyPoseModel=true' --no-parallel
```

Run the eight existing `qualifiedRepVideoReplay` combinations into `readiness-identity-{capped|complete}-{full|heavy}-{standard|robust}` and use `scripts/summarize_reference_identity.py` to compare them with the frozen `multipass-*` reports and generate the new registry. `scripts/capture_readiness_baseline.py` accepts the preceding immutable delivery folder; `scripts/score_readiness.py` checks its rubric/report receipts before computing readiness. `scripts/fetch_uiprmd.py` downloads and verifies the independent data separately, under ignored `test-artifacts/`.
