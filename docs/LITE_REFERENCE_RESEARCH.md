# Lite model reference-only screen

28 October 2026. This prospective experiment tests a different existing open pose model on the original sixteen complete uploads. The [official MediaPipe pose guide](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker) publishes separate Lite, Full and Heavy bundles. The [publisher model card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20BlazePose%20GHUM%203D.pdf), page 2, states Apache License 2.0 for these models. Its published speed or keypoint results are not Senpai accuracy, mobile performance or evidence of better form counting.

The versioned Lite `float16/1` asset is 5,777,746 bytes, GCS generation `1682624736756847`, publisher MD5 `04a75ddf7c811ac7a1a4523266dd7d88` and CRC32C `Y2/V6A==`. All checks pass before use; local SHA256 is `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a`. Original response metadata and the complete model card are preserved under ignored `lite-model-research/`. The downloader never writes into app model assets or replaces the Full/Heavy bundles. A new research model is evaluated; existing network weights are not trained or edited.

Freeze code, model/acquisition receipts, original RGB/coarse caches and the verified compiler `/7` 32-choice registry before inference. A fresh Lite VIDEO graph processes each complete original recording at the existing 66 ms PTS cadence and 960-pixel edge cap. Keep all 33 image/world landmarks and both image/world visibility/presence, including withheld detections. Same-frame coarse Full torso evidence must satisfy the existing 0.65 confidence/in-image gate; centre agreement stays within max(0.05, half a coarse torso length). No confidence, coordinates, timebase or missing point is repaired. Raw Lite points stay separate from confidence/person-admitted observations.

Use a distinct Lite model SHA and preprocessing ID throughout calibration; reject Full/Heavy identity masquerading as Lite. Existing Full/Heavy choices retain priority. Only previously unavailable sides may consider ordinary Lite, then bounded Lite, under unchanged cycle, noise, range, fault, dwell, confidence, coverage and >=80% reconstruction gates. No source outcome, speed, whole-clip label or held-out subject chooses the model or fit. The fixed denominator remains 32 and final subjects 08–10 stay sealed.

The first reference-only screen contains 64 trials and earns no readiness points by itself. Any newly usable side requires coherent source extraction/replay, source and independent regression, original timestamped RGB/MP33 review, native model identity/parity and an APK before production promotion. If no new side passes, preserve the measured failure and leave the verified app untouched. No camera-FPS, physical-device, universal-mobile or human-form precision claim follows from this screen. Engineering readiness starts at 50/100.

## Outcome: rejected, no additional reference side

Code and 81 source/input receipts were frozen in `115130b` before inference. All sixteen complete references yield **7,191 original observations**: 6,780 raw Lite poses are retained, and 6,041 observations pass the unchanged current-person agreement gate. These are raw/admission counts, not good-rep recall or human accuracy. The extraction takes 56.63 seconds with three desktop workers; this is not a phone result or a controlled speed comparison with another experiment.

Both ordinary and bounded calibration produce the same nine ready reference/side configurations, all already served by Full/Heavy. Each fit also has twelve missing-feature, ten insufficient-cycle and one weak-reference result. Across the **64 trials, zero additional sides recover**, so Lite remains excluded from production. No existing selection is lost or replaced; fixed coverage stays **12/32**. No source/independent replay is warranted for this failed reference screen.

Six original RGB frames and twelve model diagnostic panels were inspected at original 6,000/10,000 ms for front squat, side squat and side push-up. Independent decoding verifies that all six displayed RGB hashes equal the images actually inferred by Lite. The overlays retain raw confidence and world coordinates; differences in pose shape do not become new form labels or establish anatomical accuracy. The initial pose card warns about scale, viewpoint, occlusion and out-of-scope metric-depth use; no such limitation is declared solved by this trial.

Verification passes **242 core-motion JVM and 124 Python cases**, including Lite model/pipeline isolation, raw-confidence integrity, publisher CRC32C known vectors and scope checks. Production main sources match verified `1b52bcf`; all **1,336** scored receipts and the fixed rubric still verify at **50/100**. No app model asset, backend choice, acceptance threshold, existing network weight, APK or final subject changes. Existing native and causal/corruption evidence remains preserved rather than represented as fresh runs of Lite. There is no mobile performance or human live-rep precision gain.

`models/lite-reference.provenance.json` binds **142 immutable receipts** in `lite-reference-checkpoint-115130b`, including the publisher model/card/acquisition metadata, original RGB/coarse inputs, all raw Lite MP33, the frozen protocol, calibration outcomes, source/QA hashes and verification logs. The rejected model is not an Android asset. Continue eligible source acquisition or a separately justified reference/variant-generalization change; do not lower qualification gates or choose a faster model from source outcomes to manufacture coverage. Independent final subjects 08–10 remain sealed.

Reproduce in a fresh checkout of the frozen source with preserved input files; completed outputs deliberately refuse replacement:

```powershell
python scripts/fetch_lite_reference_model.py
python scripts/extract_lite_reference_candidate.py --workers 3
./gradlew.bat -I scripts/lite-reference-research.init.gradle :core-motion:liteReferenceScreen :core-motion:verifyMotionCore --no-parallel
python scripts/plot_lite_reference_candidate.py
python -m unittest discover -s scripts/tests -v
```
