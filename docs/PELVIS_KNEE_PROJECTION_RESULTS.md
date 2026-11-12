# Pelvis-axis knee projection fails the development screen

29 October2026. Frozen candidate `2c62244` tests an explicitly named surface-marker knee projection on top of the rejected frontal-arm measurement. A small net increase in correct counts hides lost good counts and newly rejected correct-labelled motions. **Do not fit a quality head, open validation or promote this candidate.** Production remains `b03e340`, compiler `/9`, readiness **50/100**; the APK is unchanged.

All16 original full development recordings and160 publisher repetitions remain, with80 correct/80 incorrect labels, original100Hz times and fixed cross-person references. Both paired modes calibrate all16 configurations under unchanged thresholds. Every shoulder input and attempt is identical. The original marker coordinates and original ABC descriptors remain unchanged; only the research m01 knee slot uses the new pelvis-axis projection. Both discarded ray components and conditioning fractions are retained separately.

| Entire development cohort | Frontal-arm control with original knees | Same arms plus projected knees |
|---|---:|---:|
| Publisher-correct reps counted |52/80|53/80|
| Incorrect qualified outputs |28|28|
| Counted-output precision |65.00%|65.43%|
| Good-rep recall |65.00%|66.25%|
| Assessable episode coverage |63.75%|64.38%|

These are development skeletal-label results, not held-out or live-camera precision. The numerical differences are0.43 percentage points precision,1.25 points recall and0.63 points coverage. They earn no readiness points. Squat alone changes26 to27/40 correct counts, with nine incorrect counts unchanged; shoulder stays26/40 correct and19 incorrect counts. The full candidate still fails90% precision,70% recall and80% coverage.

There are24 changed matched verdicts. Five previously unassessable correct episodes become counted, **four previously counted correct episodes become unassessable**, five correct ACCEPTABLE verdicts become CORRECT, and **three publisher-correct episodes become REJECTED**. Those three s04 episodes8-10 were previously uncertain; the projected driver now triggers persistent far-side CLEAR_FORM_DEVIATION against its reference. Their original correct labels are retained. This is not evidence of new clinical faults, and the extra assessable verdicts must not be advertised as better human-form discrimination.

On incorrect episodes, three REJECTED verdicts become UNASSESSABLE, two CORRECT become ACCEPTABLE, one UNASSESSABLE becomes ACCEPTABLE and one ACCEPTABLE becomes UNASSESSABLE. One additional unmatched REJECTED output appears at the end of the s01 incorrect squat recording; it remains in the emitted-event data and is not assigned a fabricated publisher label or counted as another assessable original repetition. No incorrect qualified count or original episode is removed from evaluation.

Three fixed episode2 traces and nine original timestamped marker frames were inspected for s01 correct, s02 incorrect and s04 correct squats. The projected-minus-original angle varies through a cycle, rather than giving a constant body offset. In s04's reviewed episode, the projection deepens the far-side angle while the reference/source transformations differ. Thus this plane substitution does not resolve the demonstrated-range mismatch and can amplify it. All input masks, missing values and original boundaries remain intact. The retained components do not become anatomical flexion/valgus ground truth.

Verification passes **192 Python tests and259 core-motion JVM cases**, with no failures/errors/skips. New tests cover known plane angles, retained components, conditioning/missing/degenerate inputs, rigid transforms, immutable data, prefix causality, held-out refusal and explicit slots without fallback. The control reproduces the previous projection counts. Production main sources match `b03e340`; earlier native/control/runtime results retain their original scope and are not rerun or claimed as new research tests. All2,889 production receipts revalidate at50/100. No network weight, confidence, raw MP33, reference-only selection, range/fault gate, new annotation or delivery APK changes.

Retain the negative result and its full tradeoffs. Do not sweep the0.25 conditioning threshold, remove the lost correct episodes, weaken the far-side range guard or advance this projection to final subjects. Independent anatomical/measurement validation or source-independent generalization evidence is needed before another feature substitution can be treated as an accuracy improvement. The reviewed hip-centre-regression route was not used because required calibration/marker settings were not established; no default anthropometrics or predicted joints were inserted as observations.
