# Senp.ai0 — live pose and reference-based movement analysis

Senp.ai0 uses a pretrained MediaPipe model to estimate a 33-point body skeleton, then compares movement with an uploaded reference. This branch focuses on live-camera reliability, confidence-aware feedback and experimental form-qualified repetition counting. It does not establish medical correctness, human-level form accuracy or compatibility with every phone.

## Live-pose changes

- Shared camera preparation and capture-time admission: reject duplicate/backward frames, bound inference cadence, preserve rotation/aspect ratio and avoid a queue of stale frames.
- Require reliable visible major body joints and torso anchors. Retain all 33 original landmark identities, image/world coordinates, visibility, presence and timestamps.
- Recover tracking across capture gaps and geometry changes; improve analyzer-thread lifecycle, bitmap ownership, model-buffer lifetime and model integrity checks.
- Add an optional causal 1 Euro stabilizer. Extra smoothing stays off by default because jitter reduction can introduce movement delay. Raw evidence remains available.
- Extend reference coaching with confidence-masked angles/distances, phase-specific tolerances, persistent feedback and explicit unsupported/unassessable decisions.
- Add opt-in experimental qualified reps: directly observed outbound/return paths, supported turning points, fault latching, subject/context continuity and correct/acceptable/rejected/unassessable outcomes. Missing or off-screen evidence cannot certify a rep.
- Support reference-selected Full/Heavy models in the experimental qualified path. Compatible model identity is required throughout reference preparation and live comparison.
- Prefer CameraX PERFORMANCE preview with automatic fallback, verified on API 35/36 emulators.
- Add reproducible training, original-timestamp video replays, generated-fault checks, native instrumentation and versioned evidence/provenance.

These are engine/integration changes, not a frontend redesign. Some features require explicit engine configuration and are not automatically enabled in every app workflow.

## APK and build

The **29 October 2026 preview-preference debug build** is available from the [experimental release on the contributor fork](https://github.com/keptbeetle/Senp.ai0/releases/tag/live-pose-preview-20261029): [download the APK](https://github.com/keptbeetle/Senp.ai0/releases/download/live-pose-preview-20261029/Senpai-preview-preference-debug.apk). It is associated with verified engine commit `b03e340769fb03c7460365f6592263c0376dc2f5`. The contributor has no organization-release permission, so the fork hosts the artifact while the code PR targets `SSDRT/Senp.ai0:production`. Later branch commits also contain research/diagnostics; use each artifact's source/hash receipts to identify its tested implementation.

That archived APK is debug-signed for direct installation, includes pinned Full and Heavy models, and has SHA-256:

```text
888025d7c0b958704b1bfef37b4def36eb170324468058c1f6135592c30083d2
```

Use JDK 21, Android SDK platform/build tools 35 and the checked-in Gradle wrapper. Configure `JAVA_HOME` and `ANDROID_HOME` locally; do not commit SDK paths.

```powershell
.\gradlew.bat -PincludeHeavyPoseModel=true :validation-app:assembleDebug
```

APK output: `validation-app/build/outputs/apk/debug/validation-app-debug.apk`. A fresh setup needs network access for verified model assets/dependencies. Heavy is optional. Android minimum SDK is 26; packaged ABI/static alignment checks do not prove physical-device diversity or actual 16 KB runtime support.

Workflow: upload/process a reference, then open live comparison or compare a recorded candidate. Keep required body joints visible. Insufficient evidence should withhold a judgment rather than invent a correction.

## Datasets and actual learning work

### 1,042-video exercise-quality corpus

Source: [Mendeley Data V3, DOI 10.17632/kgbb3yn47p.3](https://data.mendeley.com/datasets/kgbb3yn47p/3), **CC BY 4.0**, with publisher attribution retained in provenance and resource notices.

- 1,042 MP4 recordings, 26 participants, front/side/diagonal views and existing whole-video good/bad labels.
- Seven exercises: standing side bend, bent-over row, biceps curl, push-up, overhead press, bodyweight squat and overhead triceps extension.
- Initial extraction retained MediaPipe-33 observations at least 500 ms apart, up to 24 seconds per clip: 44,985 frames and 21,128 eligible short windows.
- Participant-disjoint split: 16 training, five validation and five test participants. All views of a participant stay in one group. Training fits preprocessing/weights; validation selects thresholds.
- Supervised downstream quality classification with **weak whole-clip labels**. MediaPipe landmark-network weights were not retrained. Initial logistic training fitted six models; push-up fitting was refused because reliable validation windows were absent. Later whole-clip candidate experiments are reported separately.
- Downloaded video members have CRC and per-video SHA-256 receipts. The initial range-based acquisition did not verify the complete publisher archive hash.

**The quality candidates failed deployment requirements.** Automatic quality-review/coaching flags remain disabled (`release_approved=false`). A video label does not identify each repetition or fault timestamp, and learned scores are not calibrated form probabilities.

See [training validation](docs/EXERCISE_TECHNIQUE_VALIDATION.md), [initial training report](models/exercise_quality.training-report.json) and [later training/tuning results](docs/AUTONOMOUS_TUNING_RESULTS.md).

### Separate supporting experiments

- [Google ML Kit pose samples](https://github.com/googlesamples/mlkit/tree/master/android/vision-quickstart): 812 landmark examples for four squat/push-up states, with 650 training and 162 image-grouped holdout examples. This optional classifier recognizes states, not form correctness; independent subject identities/negative classes are unavailable. See [report](models/sample_pose_classifier.training-report.json).
- [UI-PRMD](https://webpages.uidaho.edu/ui-prmd/): existing labelled skeletal motion data for separate quality-head research. Skeletal results are not camera accuracy. The validation-failing quality-head candidate was rejected, not promoted. See [development](docs/QUALITY_HEAD_DEVELOPMENT.md) and [validation](docs/QUALITY_HEAD_VALIDATION.md).
- Generated motions/corruptions test timing and counting mechanics. They are not independent human technique labels.

Large videos, raw caches, local environments and generated outputs are excluded from Git. Keep attribution, model/data fingerprints and evaluation protocols with the reproducible source.

## Evidence and limitations

- Targeted generated observed-path tests reduced unsupported counts from 288 to zero while retaining all 288 complete-motion controls. This verifies a specific counting fix, not 100% human accuracy.
- One fixed Full-first/Heavy-fallback comparison increased usable replay configurations from 20/96 to 26/96. This measures reference availability, not prediction accuracy.
- The preview-preference checkpoint records 305 scoped JVM tests, 31 native cases on each API 35/36 and 175 Python tests, alongside frozen controls/corruptions. These are historical checkpoint results, not results for arbitrary future edits.
- Independent live human-form precision remains unknown. The recorded 50/100 readiness score is an engineering rubric, not 50% accuracy. The proposed 70 milestone and 98% precision target were not achieved.
- Physical-device diversity, sustained exercising-person performance and actual 16 KB runtime qualification remain open. Monocular depth ambiguity, occlusion, reference quality and subject/view changes limit judgments.
- Fine spinal shape, load, forces, breathing and pain are not certified by a skeleton comparison. A catalogue entry does not mean automatic or validated recognition of every exercise.
- App-wide lint retains a documented pre-existing video-editor `UnusedBoxWithConstraintsScope` failure. Scoped engine checks are not a passing root aggregate check.

Details: [live-pose validation](docs/LIVE_POSE_VALIDATION.md), [qualified repetitions](docs/QUALIFIED_REP_IMPLEMENTATION.md), [observed-path tuning](docs/OBSERVED_PATH_TUNING.md), [preview verification](docs/PREVIEW_PREFERENCE_RESULTS.md) and [readiness progress](docs/READINESS_70_PROGRESS.md).

## Engine architecture and verification

Pure Kotlin/JVM modules own contracts, movement, alignment and caches; Android/MediaPipe adapters sit behind narrow interfaces. Timestamps drive temporal decisions. Core analysis runs on-device without rendering an output MP4; optional external AI frame review is separate.

```powershell
.\gradlew.bat :core-motion:verifyMotionCore checkCoreBoundaries
.\gradlew.bat :core-motion:livePoseBenchmark
.\gradlew.bat -PincludeHeavyPoseModel=true :android-pose-mediapipe:connectedDebugAndroidTest :validation-app:connectedDebugAndroidTest --no-parallel
```

Instrumentation needs an emulator/device and prepared assets. Research replays may need external ignored data; consult their protocols first.

## Core modules

- `core-contracts`: immutable timestamp-first requests, configuration, 33-landmark pose data, motion/alignment/results, typed failures, timings, provenance, and versioned cache identity.
- `core-pipeline`: narrow decoder/pose/motion/phase/alignment/cache interfaces and injected cancellation-safe orchestration.
- `core-motion`: canonical `MotionProcessor` adapter plus the proven timestamp-aware repair, smoothing, quality, normalization, guardrail, and feature engine.
- `core-cache`: bounded pure-JVM cache implementation.
- `core-alignment`: canonical timestamp-first phase detection, validity-aware masked DTW, confidence, problem windows, and QA artifacts.
- `core-review`: pure-JVM frame-review contracts, model/reasoning selection, and Codex request/SSE handling behind an injected transport.
- `headless-runner`: deterministic fake adapters, end-to-end tests, and a human-inspectable JSON result.

See [the architecture](docs/ARCHITECTURE.md), [integration contract](docs/INTEGRATION_CONTRACT.md), and [build/toolchain notes](docs/BUILD_AND_TOOLCHAIN.md).
