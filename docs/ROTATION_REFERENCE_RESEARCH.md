# Quarter-turn reference extraction screen

28 October 2026. Test a new input representation against the original sixteen complete reference recordings. The pinned MediaPipe Full IMAGE detector consumes exact 90° / 270° clockwise RGB quarter-turns, without cropping, resampling, colour changes, temporal smoothing or new network weights. This is a hypothesis that a different orientation may recover original pose evidence; it is not an established improvement or a camera rotation recommendation.

Keep the established 66 ms original decoder PTS, 960-pixel edge cap and complete duration. The current coarse Full VIDEO torso must first satisfy the existing 0.65 confidence/in-image test. Inverse-mapped torso centres must agree within the unchanged max(0.05, half a coarse torso length) bound. This is limited current-frame track agreement, not biometric identity. Missing evidence remains missing; no preceding/future frame supplies a crop or joint. Raw results from unbound detections remain in the audit but cannot qualify repetitions.

Retain all 33 original input-frame image/world landmarks, image visibility/presence and world visibility/presence. Canonical image coordinates invert the exact quarter-turn and image z scales by rotated/original width (original height/width). Metric x/y invert the same rigid roll, preserving z and geometry; no anatomical joint, bone constraint or depth is reconstructed. Keep raw and canonical coordinates separately. Landmark order and confidence never change, and outside-image points are not clamped. Kotlin independently checks every inverse transform and current-person admission before calibrating.

Freeze code, original RGB/coarse caches, model, original manifest and the verified `/7` 32-choice reference registry before inference. Existing choices keep priority. Only unavailable sides may consider new candidates, in fixed order ordinary-CW90, ordinary-CW270, bounded-CW90, bounded-CW270. Every confidence, range, dwell, minimum-cycle, coverage, noise and >=80% reconstruction gate stays unchanged. No source outcomes, whole-clip label success, independent validation or final subjects select a rotation. The fixed reference denominator stays 32.

This initial screen changes only research extraction/test code. A reference self-reconstruction gain is not human precision or an automatic readiness gain. Any candidate that recovers a side still requires a coherent source pipeline, source/causal/independent regression, original timestamped RGB/MP33 QA, native evidence and a new APK before production promotion. If no new sides recover, preserve the failed experiment and leave the verified engine untouched. Readiness begins at 50/100; final subjects 08–10 stay sealed.

The [official pose guide](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker) distinguishes normalized image landmarks from metric hip-centred world coordinates. The [original world-landmark graph documentation](https://github.com/google-ai-edge/mediapipe/blob/v0.10.32/mediapipe/modules/pose_landmark/tensors_to_pose_landmarks_and_segmentation.pbtxt) explains the shared topology and different coordinate units. Quarter-turn inverse transforms are coordinate bookkeeping, not additional accuracy evidence.

## Outcome: no additional usable reference side

Source/protocol were committed in `d06fa88` before inference. Both rotations complete on all sixteen references, retaining 7,191 original timestamps each (14,382 slots total). Extraction takes 206.11 seconds with three desktop workers; this is not phone or sustained-camera performance. All 128 calibration trials pass the independent coordinate/person mapping audit and use unchanged production gates.

| Input rotation | Raw model outputs retained | Same-current-person observations admitted | Ready sides: ordinary / bounded |
|---|---:|---:|---:|
| CW90 IMAGE | 5,848 | 5,267 | 4 / 4 |
| CW270 IMAGE | 5,786 | 5,246 | 6 / 5 |

All ready sides are already served by the established backend registry. **Zero additional reference/side configurations recover**, so both rotations are rejected for promotion. Existing 12/32 choices remain unchanged. The 128 outcomes comprise 52 insufficient-cycle, 46 missing-feature, nine weak-reference, two insufficient-excursion and 19 ready outcomes; these overlapping model/fit trials are not independent videos or human repetition labels. No source or independent-label replay follows a failed reference-only screen.

Six original RGB frames and eighteen raw-pose diagnostic panels at original 6,000 / 10,000 ms were inspected for front squat, side squat and side push-up. At the 10,000 ms push-up frame, the CW90 raw result extends toward a different part of the scene and is withheld by current-person agreement; CW270 remains bound. Other displayed pose differences do not supply new form labels, validate monocular 3D anatomy or justify confidence/range changes. Projection/feedback warnings remain in the original extraction log and are not interpreted as calibrated geometry.

Verification passes **238 core-motion JVM and 121 Python cases**, including four independent Kotlin and six Python quarter-turn/mapping/identity/scope cases. An initial preflight compile used the wrong test import; it was corrected to this module's Kotlin test API before the freeze, and both logs are retained. Production main sources remain identical to verified `1b52bcf`; all 1,336 existing scored receipts and the fixed rubric revalidate at **50/100**. Existing native, causal and corruption evidence is preserved rather than represented as a fresh run of this research pipeline. No APK, model weight, acceptance threshold, final-subject access or readiness point changes.

`models/rotation-reference.provenance.json` binds 152 immutable receipts under `rotation-reference-checkpoint-d06fa88`, including original RGB/coarse inputs, raw local and canonical MP33, source freeze, all outcomes, original-frame QA and tests. Continue eligible new reference acquisition or a separately justified representation/generalization change. Do not repeat these rotations with looser gates or send them to the sealed final holdout to select a winner.

Reproduce in a fresh checkout of the frozen source populated with its preserved input files. Existing output paths deliberately refuse overwriting:

```powershell
python scripts/extract_rotation_reference_candidates.py --workers 3
./gradlew.bat -I scripts/rotation-reference-research.init.gradle :core-motion:rotationReferenceScreen :core-motion:verifyMotionCore --no-parallel
python scripts/plot_rotation_reference_candidates.py
python -m unittest discover -s scripts/tests -v
```
