# Mirrored source calibration preserves the demonstrated reference side

Compiler policy `qualified-rep-compiler/5` fixes the orientation used for causal reference self-reconstruction. Previously, requesting `MIRRORED` source matching also applied that mapping while replaying the original reference. A unilateral upload with reliable motion on the demonstrated side could fail calibration because its opposite side was stationary or missing.

Self-reconstruction now uses the original reference orientation. The returned reference still retains the requested source mirror mode, and its key includes that configuration. Live source matching, fault feature names and reported observed side still map to the opposite side in mirrored mode. This is an explicit configuration; it does not automatically flip an incorrectly performed movement or replace the uploaded target.

Three regression tests reproduced the bug with the former orientation logic and pass with the fix. Constructed unilateral references reconstruct four cycles for every supported policy and both sides. Live tests count the correctly mirrored source, reject a clear opposite-side companion-feature fault, withhold the wrong arm, and retain provenance/missing-feature rejection. Native instrumentation repeats the unilateral calibration and mirrored-source fault checks in the APK. These controls are not independently labelled human-rep accuracy.

The new compiler deliberately invalidates reference keys; runtime reference schema remains `qualified-reference-reps/5` and backend-selection policy remains `/2`. No confidence, range, fault, path, minimum-cycle or reconstruction threshold is changed. Original MP33 image/world values, visibility, presence and capture timestamps remain intact.

All eight Full/Heavy × ordinary/bounded-fit × capped/complete fixed-input replays preserve prior reference selection, source counts, intervals, verdicts and faults. Complete coverage stays 12/32 reference/side configurations; capped stays 10/32. This fix does not increase the frozen direct-mode coverage score. The current registry is `mirror-calibration-summary`; older `reference-continuity-*` artifacts remain preserved.

Original timestamped press, curl, push-up and squat frame sheets were inspected again, including the 23–30 second press reference and the 10.9–12.2 second source interval. No image or landmark extraction was replaced. Source-frame context remains necessary to distinguish setup/rest from exercise motion; weak whole-video labels still do not identify individual bad reps.

Separately, ten-node finer curves were tried under a declared reference-only priority after all established six-node fits. They recovered no additional reference sides, so no new fallback is promoted. See `models/fine-reference-curve-experiment.json` and the preserved research report.

This resolves a specific calibration defect, not cross-person clinical correctness or live-camera precision. Readiness remains below 70 and the independent-label gate remains unmet. The prior rejected skeletal quality head is still excluded from the app. Final-test subjects 08–10 remain sealed.
