# Live pose exercise tuning: validation and limits

The engine now contains 54 explicitly selected exercise/variant profiles, 141 rules and 25 confidence-masked measurements. Forty primary exercise guidance sources supply technique facts; a 41st source supplies the identity/labels of standing side bend. The full ID/measurement/source matrix is [EXERCISE_TECHNIQUE_COVERAGE.md](EXERCISE_TECHNIQUE_COVERAGE.md); [exercise-technique-rules.csv](exercise-technique-rules.csv) also includes every rule's phase, margins, cue and limitations.

This does **not** establish human-level pose accuracy, full coverage of every exercise or compatibility with every mobile device. There is no independent keypoint ground truth for this corpus, so a defensible landmark-accuracy percentage cannot be reported.

## What is implemented

- Selected technique policies compile into the existing live coach. Reference-calibrated ranges and source-guided targets remain distinguishable. Source targets do not overwrite the phase recognizer's learned reference. Ambiguous phases, unavailable features and conflicting targets are audited and suppressed.
- Geometry includes bilateral shoulder, elbow, hip, knee, ankle and body-line angles; ten torso-normalized distances; three spacing relations. Each measurement inherits its weakest required landmark confidence, including torso normalization anchors. Near-zero ratio denominators are omitted.
- Existing confidence, action recognition, mirror handling, 240 ms persistence, hysteresis, resets and bounded history remain in effect. A short fluctuation cannot immediately produce a coaching suggestion. Margins are engineering defaults, not biomechanical error guarantees.
- Written guidance is represented as concise original fact summaries and traceable measurement mappings. Detailed spine shape, forces, breathing, pain, muscle activation, grip and exact equipment contact are explicitly unobservable. Stance ratios do not establish knee-over-toe alignment.
- An actual four-state squat/push-up classifier was fitted from Google's Apache-2.0 sample landmarks. Separately, six optional form-quality research models were fitted from licensed real videos. These are downstream classifiers; the MediaPipe landmark network itself was not retrained.

## Trained state classifier

Google ML Kit's official CSV supplies 812 examples: 650 training and 162 holdout samples, grouped by image filename. The delivered compact model has 48 prototypes over 20 image-geometry features. Image aspect ratio is corrected at the live seam.

| Measure | Result |
| --- | ---: |
| Accepted holdout state accuracy | 97.81% |
| Holdout acceptance coverage | 84.57% |
| Correct / all holdout, abstentions counted as misses | 82.72% |
| Closed-set nearest state accuracy | 93.21% |

These are state-classification results, not form quality or landmark accuracy. Subject/video identities and labelled negative cases are absent, and source inference may differ from the current model. The classifier is opt-in and never selects technique corrections automatically. See `models/sample_pose_classifier.provenance.json` and its training report.

## Real-video quality experiment

Source: [Mendeley dataset V3, DOI 10.17632/kgbb3yn47p.3](https://data.mendeley.com/datasets/kgbb3yn47p/3), CC BY 4.0, seven exercises, 26 participants, front/side/diagonal smartphone views. Downloaded 1,042 MP4 members (3,116,315,639 uncompressed bytes). Each ZIP member passed CRC checking; per-video SHA-256 is recorded. The publisher's whole-archive hash was **not** verified because members were fetched using HTTP ranges. One original `bicep` filename token was normalized to `bicep_curl` without changing its quality label.

Pinned MediaPipe Full 0.10.32 CPU extraction retained all 33 image/world landmarks, visibility/presence and actual source timestamps in ignored caches. FFmpeg selected frames at intervals of at least 500 ms, up to the first 24 seconds per clip; 44,985 frames produced 21,128 eligible windows. Forty-two source-PTS overlays were inspected, including no-pose frames, occlusions and setup movements.

The experiment uses 2.4-second windows with q10/median/q90/availability for 25 features. Features require 0.65 local confidence, three samples and 60% feature coverage. Missing poses and gaps clear history. Only training data determine imputation, means, scales and logistic weights. Each video has equal total training weight. Participant split is fixed with seed 20261022: 16 train, five validation, five test. Views of a participant never cross splits.

Research thresholds are selected on validation participants for at least 95% window precision, ten flags, two participants and three clips. Scores are uncalibrated; they are not probabilities of good/bad technique. Test results below apply to those fixed thresholds, with outlier suppression. Overlapping windows are correlated.

| Exercise | Held-out eligible windows | True / false flags at research threshold | Precision | Recall | Raw-score AUC |
| --- | ---: | ---: | ---: | ---: | ---: |
| Standing side bend | 508 | 0 / 0 | No qualified threshold | 0% | 0.519 |
| Bent-over row | 332 | 0 / 0 | No qualified threshold | 0% | 0.638 |
| Biceps curl | 641 | 0 / 4 | 0% | 0% | 0.471 |
| Overhead press | 607 | 0 / 0 | No test flags | 0% | 0.752 |
| Bodyweight squat | 574 | 17 / 2 | 89.47% | 6.03% | 0.554 |
| Overhead triceps extension | 790 | 0 / 0 | No qualified threshold | 0% | 0.565 |
| Push-up | 8; zero validation windows | No quality model fitted | Unsupported | Unsupported | — |

**These results do not qualify the learned quality models for production suggestions.** `release_approved=false`; runtime `needsReview` remains false even if a research threshold is crossed. Scores are exposed only through an explicit diagnostic opt-in. Research quality output is not converted into anatomical cues. Confidence gates were kept unchanged; test participants were not used for threshold tuning. Per-view metrics, outlier counts and video-mean exploratory metrics are in `models/exercise_quality.training-report.json`. An empty view has zero eligible windows, not zero errors. Reported window coverage does not imply all source frames/videos could be scored.

The weak whole-clip good/bad labels include setup, repetitions and occlusions. They contain no expert fault timing or anatomical error labels. Sparse pose features also omit load and fine spinal shape. Those limitations and the observed participant shift explain why passing software tests cannot establish human-level form judgement.

## Live integration

The engine API accepts `exerciseId`, `enableSampleClassifier` and `enableLearnedQuality`. Unknown exercise IDs fail explicitly. Quality diagnostics require an explicit exercise ID, raw pose evidence at the same timestamp and the pinned Full-model SHA-256; Heavy or missing identities abstain. Ordinary callers keep generic reference comparison unless they select a profile. A catalogue entry does not provide automatic 54-exercise recognition.

`LiveReferenceActionActivity` forwards raw/stabilized frames separately and exposes intent extras `exercise_technique_id`, `enable_sample_pose_classifier`, and `enable_learned_quality_diagnostics`. A current prepared reference is still required. There is no new exercise picker or frontend workflow in this engine-only change.

## Reproduction

Use JDK 21 for Gradle 8.14.3, Android SDK, Python 3.13.5 and FFmpeg in PATH. Research dependency versions are in `scripts/requirements-exercise-quality.txt`. Raw videos, caches, downloaded models and virtual environments remain ignored.

1. Run `python scripts/fetch_exercise_quality_members.py` to fetch and verify the pinned dataset members.
2. Create an isolated venv under `test-artifacts/exercise-knowledge/`. Install the pinned requirements (MediaPipe may be installed with `--no-deps` when using the FFmpeg/Pillow path).
3. Run `scripts/prepare_quality_research_runtime.py` with that venv's Python. It omits four optional MediaPipe drawing exports only, retains a backup and records original/modified hashes. Pose inference modules and model weights are unchanged. Android does not use this patch.
4. Ensure the pinned Full task asset exists (Gradle generates/downloads it), then run `scripts/extract_exercise_quality_features.py --workers 3` and `scripts/render_exercise_quality_audit.py` with the research Python.
5. Run `scripts/train_exercise_quality_model.py` to fit/export, or `--check` to reproduce parameters and both parity fixtures without changing them.
6. Run `python scripts/train_live_pose_classifier.py --fetch --check` and `python scripts/report_exercise_techniques.py`.
7. Run the module checks, native tests and benchmarks recorded in the delivery evidence. Static APK checks use `scripts/verify_apk_compatibility.py` and Android `zipalign -c -P 16 -v 4`.

## Verification and remaining qualification

Automated verification covers Python/Kotlin scores on 12 held-out video windows, world-feature/visibility parity through the live adapter on those 12 source frames, the four-state classifier on 16 holdout samples, every catalogue binding, source-target exceptions/conflicts, confidence masking, wrong model identity, raw-evidence timestamps, missing data, context/gap resets and long-stream bounded history. Catalogue mechanism tests use 648 oracle-phase synthetic scenarios; the persistence sweep uses 1,200 synthetic scenarios. These test mechanics, not human accuracy. Native tests exercise packaged resources, Full inference, concurrent model creation and the live/reference seam.

The final scoped Gradle run passed all 343 JVM variant executions (298 distinct cases), 13 native instrumentation tests and module boundary checks. All 28 Python QA tests passed using Python 3.12 under Ubuntu 24.04 WSL. Both classifier parameter/fixture reproduction checks passed. The 648 catalogue scenarios produced 648 intended events and zero false events, with weak measurements suppressed. The 240 ms choice in the persistence sweep produced 560 intended events and zero false events in its synthetic cases. The native still-image test tracked 21 frames and monitored up to 24 features against its own reference; its research quality input was correctly rejected as out of distribution. These counts are integration/mechanism evidence, not independent human exercise accuracy.

Android runtime testing uses API 36 x86_64 on a 4 KB-page emulator; no physical phone is available. The APK targets Android API 26+, packages arm64-v8a/armeabi-v7a/x86/x86_64 and passes static 16 KB ELF/ZIP checks. A 16 KB runtime, ARM phones, camera/device diversity, thermal behavior and iOS are unverified/unsupported as applicable. Universal mobile compatibility is not established.

The repository's root aggregate lint remains blocked by the pre-existing `UnusedBoxWithConstraintsScope` in `VideoEditorScreen.kt:608`. Scoped engine checks/builds are recorded separately; this unrelated frontend lint was not suppressed.

To qualify precise exercise coaching, the remaining work is independently expert-reviewed frame/rep fault labels, known landmark ground truth, variant/mobility and diverse-body coverage, multi-person/camera-negative data, independent evaluation across devices/views, calibrated exercise-specific thresholds and a physical-device performance matrix. The six quality models are a reproducible failed-to-qualify baseline, not an accuracy improvement claim.
