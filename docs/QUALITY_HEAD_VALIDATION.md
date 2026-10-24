# Quality-head validation: candidate rejected

The candidate frozen in commit `8a330b7` fails its validation criteria. It is **not promoted to the app**. Raw MediaPipe weights, production confidence/range/fault checks and the uploaded-reference target remain unchanged. Readiness remains **42.5/100**, not 70. Live-camera human-rep precision is still unmeasured.

## What was evaluated

The exact logistic C=1 fits, 64-feature ordering and 0.75 veto threshold were committed before opening validation data. Subjects 05–07 contribute 12 original continuous recordings and 120 publisher-labelled repetitions: 60 correct, 60 incorrect, squat and shoulder abduction. Subject 01's correct development recording is the fixed reference for each movement. There is no validation fitting, threshold search or source-label reference selection.

All 120 segmented repetitions align to unique contiguous original excerpts within the previously fixed 1e-6 mm text-export tolerance; no clips are joined, poses repaired or timestamps stretched. The exporter logs each requested and successful validation read. Separate readers reject final-test subjects 08–10 before reading their trajectories. No final-test trajectories were opened.

The quality head sees only geometric observations between an engine attempt's start and completion. It scores every emitted attempt, including unmatched events; publisher episode bounds and correctness labels are used only by the unchanged evaluator. Missing events and unassessable attempts remain in the denominators. No veto creates a count or upgrades an unknown attempt.

## Results on the three reserved validation subjects

| Result | Correct counted / 60 | Incorrect counted / 60 | Counted-rep precision | Correct-rep recall | Assessable coverage |
|---|---:|---:|---:|---:|---:|
| Existing reference engine | 13 | 7 | 65.00% | 21.67% | 30.83% |
| Frozen quality veto | 6 | 0 | 6/6 counted events | 10.00% | 30.83% |

The six accepted events contain no observed false positive in this validation sample, but **6/6 is not a demonstrated 100% real-world accuracy**. The filter discards seven of thirteen previously counted correct reps. It fails recall >=70% and coverage >=80% by large margins, so it cannot pass the independent-label gate or proceed to final test. This supersedes any inference that the 78.95% development precision necessarily generalizes to new people.

| Variant | Baseline correct / incorrect counts | Candidate correct / incorrect counts | Candidate correct-rep recall |
|---|---:|---:|---:|
| Deep squat | 8 / 1 | 1 / 0 | 1/30 = 3.33% |
| Standing shoulder abduction, frozen RIGHT-side profile | 5 / 6 | 5 / 0 | 5/30 = 16.67% |

## Diagnosed limitations

- On the correct squat recordings the existing engine emits 22 unassessable attempts. Nineteen have `INSUFFICIENT_RANGE` and twelve have `AMBIGUOUS_FORM`; reasons overlap. The additional classifier then removes seven of the eight accepted correct squats. This is a serious cross-person generalization failure of the experimental head, on top of reference-match limitations.
- Correct shoulder recordings yield fifteen unassessable attempts and ten missing events. Subject 07 contributes no events on either label under the frozen RIGHT-side profile. Original marker views and bilateral angle ranges show substantial left-arm movement with an almost static right-arm proxy. On the correct recording, left shoulder q95-q05 is 110.21 degrees while right is 2.05 degrees. This identifies a laterality/scope mismatch to investigate; it does not justify relabelling, silently flipping or dropping the subject. The [publisher README](https://zenodo.org/records/23104965/files/README.md) names shoulder abduction without guaranteeing the same active side in every recording.
- A whole-rep publisher label cannot supervise the exact time a fault first becomes visible. This head operates after causal completion and supplies no new immediate joint-specific coaching evidence.
- Clinical marker trajectories and binary acquisition observability remain separate from camera MP33 confidence and anatomical joint-centre accuracy. Correctness against a broad clinical exercise label is also different from close imitation of one uploaded reference.

Six timestamped two-plane marker contact sheets were generated and inspected, covering both variants and both labels for subjects 05, 06 and 07. They show original markers and missing samples, with no reconstruction. Their hashes and read receipts are preserved in the evidence bundle. The first-rep peak uses the frozen selected-side driver; subject 07's near-static right arm is retained rather than replaced by a convenient left-arm peak.

## Next tuning sequence

1. Audit active-side semantics on development and already-opened validation recordings. Prototype an explicitly declared mirror/laterality path for symmetric exercises, preserving raw evidence and a distinct provenance identity. Keep this frozen RIGHT-side result and all its denominators. Do not select sides by which one makes a correctness label pass.
2. Diagnose reference-to-person range and companion-feature differences using original frames and reference-only calibration. Separate movement targets from variant scope. Require complete causal cycles and the existing reconstruction gate; no blanket widening of confidence, range or fault tolerances.
3. Replace broad whole-attempt quantiles with phase-conditioned, reference-relative fault descriptors where evidence supports them. Develop subject-disjoint fitting and an out-of-distribution check before allowing a quality veto to remove correct counts. No new per-frame labels may be invented from whole-rep labels.
4. Record all further use of subjects 05–07 as validation-based selection. Final subjects 08–10 remain sealed until a committed candidate passes the frozen precision, recall and coverage criteria on validation. Then perform the separately frozen final test once. A skeletal pass would still earn only skeletal-domain points.

## Verification and reproduction

All **41 Python tests** and **202 core-motion JVM tests** pass, with no skipped/failing cases. The new Kotlin tests reject incomplete/duplicate cohorts and final-test scope before recording files are read. The shared research replay refactor produces the **byte-identical** original development replay (`f4c8e010153cd0bae3f016b1e6065c20243985f5372f8212658f81b681496acd`). This protects the earlier frozen development comparison.

Run, in the byte-identical source checkout and recorded research environment:

```powershell
python -m unittest discover -s scripts/tests -v
./gradlew.bat :core-motion:test :core-motion:uiprmdDevelopmentReplay --no-parallel
python scripts/prepare_uiprmd_validation.py
./gradlew.bat :core-motion:uiprmdValidationReplay --no-parallel
python scripts/evaluate_uiprmd_validation.py
python scripts/plot_uiprmd_validation.py
python scripts/audit_uiprmd_validation.py
```

`models/uiprmd-quality-validation.provenance.json` binds results, source files, logs, models and access receipts. Raw-byte hash checks intentionally fail closed if source newline conversion or artifact replacement changes bytes. Research models are not Android assets. No new APK or Android/native accuracy claim is attached to this research-only change; the prior production APK remains associated with engine commit `01b83e4`.
