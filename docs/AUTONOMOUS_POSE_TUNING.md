# Autonomous pose-tuning sequence

23 October 2026. Continue on `codex/live-pose-accuracy`, starting from `0f768e8`.

This replaces the earlier sequence that required new manual annotation, reviewer recruitment or fresh human recordings before tuning. The requested workflow uses no new human participation in training. Existing licensed dataset annotations are reused as supplied; generated labels and model agreement are recorded separately. The assistant runs extraction, training, parameter search, replay, inspection and verification itself.

## 1. Freeze the baseline automatically

Retain the current app, model identities, reference profiles, defaults, source hashes and replay results. Record all source intervals and failures, including unavailable references. The baseline has 20/96 usable replay configurations and 7/32 usable unique reference-side configurations. These are availability measures, not a real-world precision percentage.

Keep the uploaded reference as the motion target. Reference matching and reference matching with technique guards remain explicit policies. No candidate should silently redefine the selected exercise, erase a latched fault or fill a missing mandatory endpoint.

## 2. Use available data without asking for new labels

Use the existing 1,042-video CC BY 4.0 corpus and its original whole-clip good/bad labels. Keep participant groups separate during fitting, selection and retrospective audit. Existing audit participants have already been inspected, so report them as retrospective data rather than a fresh test cohort.

Whole-clip labels supervise whole-clip diagnostics. They are not copied onto every frame, repetition or fault timestamp. Search for suitable openly licensed rep/keypoint datasets when a new training task requires those labels; preserve their annotation granularity and license/provenance. Dataset access forms or new labeling requests are not part of this workflow.

## 3. Compare pose backends on identical evidence

Extract pinned Full and Heavy landmarks on the same original source frames and timestamps. Preserve all MP33 image/world coordinates, visibility and presence. Cache each backend separately and require a matching model for reference and live-source replay.

Measure usable critical evidence, reference calibration, self-reconstruction, model disagreement, output changes and research inference time. These provide autonomous diagnostics; higher confidence or agreement is not independently proven correctness. Existing 15 FPS footage cannot yield extra evidence by requesting 30 FPS.

Start with the existing 48-video high-cadence development subset. Use reference-only model selection as an experiment: prefer the current Full backend when its reference calibrates; consider Heavy when Full cannot prepare that same reference. Freeze the chosen backend for the whole reference/live pair. Do not select it using the source clip label or whichever source outcome looks better. Offline selection availability is reported separately from Android integration.

## 4. Train candidate quality models automatically

Fit several model families rather than assuming one will win. The first cycle compares regularized logistic regression, extra trees and gradient boosting. Inputs are exercise-relevant observed angle/distance statistics, temporal variation and availability masks. Use one aggregate per labeled video to avoid counting overlapping windows as independent training examples.

Fit imputation and scaling inside each training fold. This imputation is a classifier operation on scalar statistics, not replacement of missing pose coordinates. Choose the model using grouped training folds, select alert thresholds on separate validation subjects, freeze it, then score the historical audit subjects. Record unsupported clips, per-view results and regressions as well as improvements.

Keep whole-clip research models separate from causal live rep qualification. A model trained on an entire video includes future observations relative to a live frame and cannot simply be installed as an immediate fault detector. A future causal temporal model must train on appropriately timed evidence or clearly identified weak supervision, and be evaluated as that task.

## 5. Generate controlled oracle motions and faults

Create motions with known mathematical outcomes: clean completion, nearly correct range/form, clearly severe deviation, partial reversal, missing turnaround, grey-zone movement, brief transient, stationary input, isolated spikes and starting mid-rep. Vary source cadence, timestamp jitter, noise and movement speed.

Oracle labels come from how the test motion was constructed, not from the engine being optimized. Keep different seeds and frame rates for parameter selection and checking. Confirm no duplicate/rest counts, no acceptance of missing endpoints, correct fault latching, near-correct acceptance and bounded fault-confirmation time.

These tests can optimize engineering behavior autonomously. They cannot turn a generated joint trajectory into an independently validated real-camera form example.

## 6. Search parameters automatically

Search a bounded, reproducible configuration set. The first cycle evaluates 27 combinations of acceptable allowance, fault confirmation and required excursion. Rank good/near-correct acceptance subject to zero generated bad/uncertain acceptance, no false counts, no hard rejection of sub-dwell transients, and rejection of generated clearly bad cases. Prefer smaller changes from current defaults when results tie.

Freeze the selected candidate before checking unseen generator seeds/frame rates. Then replay it through both Full and Heavy real-video caches. Compare the same source/reference pairs, including failures. Keep synthetic precision, clip-label diagnostics, reference availability and real rep precision as distinct measurements.

Do not globally deploy a wider tolerance just because generated acceptance increases. If real recordings show a large unexplained increase in accepted outputs, loss of calibration, or increased weak-bad-clip acceptance, retain it as an experimental candidate and continue automated diagnosis. Whole-clip labels alone cannot identify which individual accepted reps were wrong.

## 7. Use automatic agreement carefully

Find original frames where both pose models supply the required visible-side features and agree within a declared measurement tolerance. Mark these as model-agreement evidence, with model/source hashes and timestamps. Keep disagreement and unavailable intervals explicitly uncertain.

Agreement may support consistency training or automatic example prioritization. It does not become a correct-form label. Two models can share the same error; no script may mark pseudo-labels as expert reviewed or feed them through the independent-label scorer under a fabricated provenance flag.

## 8. Verify, compare, retain or reject

For each experiment record the fitting data, participant split, configuration, model/code hashes, seeds, outputs, failure categories and keep/reject decision. Inspect representative timestamped source frames and overlays. Run core boundary checks, appropriate JVM tests, generated mechanism tests, Python QA and training-specific checks.

Promote only changes supported by the actual task being measured. Candidate models and automatic parameter files can be delivered for reproducible research even when they fail promotion. Preserve the current working app when a candidate shows mixed or insufficient evidence. This is an automatic experiment decision, not a request for a person to approve training.

Repeat with the next largest measured failure source: reference-bout selection, unstable geometry, phase tracking, exercise/view-specific feature sets, then causal temporal learning. Bridge and lateral-raise policies need suitable source data before claiming learned performance; the wider catalogue must not inherit a metric measured on four exercise classes.

## Accuracy claims under this workflow

The objective remains higher real-world accuracy, including the 98% counted-rep precision target and high good-rep acceptance. The work proceeds without new human labeling. Use independent existing rep-level annotations where available to evaluate that target. With only whole-clip labels, generated examples and teacher agreement, report the demonstrated metrics and leave actual live-rep precision unmeasured. That limitation does not prevent further autonomous training or tuning, and it must not be hidden by calling self-consistency “98% human accuracy.”

## First cycle commands

```powershell
# Isolated pinned research runtime; no new labels or reviewers required.
& test-artifacts/exercise-knowledge/training-venv313/Scripts/python.exe scripts/extract_autonomous_pose_candidates.py
& test-artifacts/exercise-knowledge/training-venv313/Scripts/python.exe scripts/train_autonomous_quality_candidates.py
.\gradlew.bat :core-motion:qualifiedRepAutotune
.\gradlew.bat :core-motion:qualifiedRepVideoReplay '-PqualifiedReplayInput=test-artifacts/exercise-knowledge/autonomous-heavy-cache' '-PqualifiedReplayOutput=test-artifacts/exercise-knowledge/autonomous-heavy-replay'
.\gradlew.bat :core-motion:qualifiedRepVideoReplay '-PqualifiedReplayInput=test-artifacts/exercise-knowledge/rep-world-feature-cache' '-PqualifiedReplayOutput=test-artifacts/exercise-knowledge/autonomous-full-candidate-replay' '-PqualifiedReplayConfig=test-artifacts/exercise-knowledge/autonomous-parameter-search.json'
.\gradlew.bat :core-motion:qualifiedRepVideoReplay '-PqualifiedReplayInput=test-artifacts/exercise-knowledge/autonomous-heavy-cache' '-PqualifiedReplayOutput=test-artifacts/exercise-knowledge/autonomous-heavy-candidate-replay' '-PqualifiedReplayConfig=test-artifacts/exercise-knowledge/autonomous-parameter-search.json'
python scripts/summarize_autonomous_pose_experiments.py
```

Full/Heavy are documented in the [official MediaPipe model guide](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker). Grouped model selection uses [GroupKFold](https://scikit-learn.org/stable/modules/generated/sklearn.model_selection.GroupKFold.html) with explicit performer groups. Each selected asset, dataset and runtime remains pinned in the experiment evidence.
