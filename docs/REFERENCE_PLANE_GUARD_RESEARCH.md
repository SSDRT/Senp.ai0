# Reference-only arm-plane guard: rejected after development

25 October 2026. The additional reference-plane experiment **does not justify promotion**. It withholds six previously accepted incorrect-labelled reps but also loses five accepted correct-labelled reps. The APK and production rules are unchanged. Readiness remains **42.5/100**, and actual live-camera human-rep precision remains unmeasured.

## Candidate and evidence boundaries

The hypothesis was that the existing shoulder/elbow policy misses movement outside the demonstrated arm plane. It follows the prior broader-feature diagnosis and inspection of already-existing validation visualizations; this is not a new blind validation hypothesis. The new quantitative experiment uses **development subjects 01–04 only**. No new validation trajectory or final subject is read by the plane-guard workflow. Source-read receipts verify development-only access.

The reference-only model derives the right upper arm's absolute angle out of the torso plane from original Vicon39 surface markers, using the existing tested geometric feature function. This measurement is invariant to rigid motion/scale; missing or degenerate anchors remain missing. It is not an anatomical joint-centre reconstruction or a MediaPipe-confidence estimate. Source and reference remain in the explicitly declared RIGHT-side variant; no side flipping is used.

The existing qualified engine compiles fixed development references 01 and 02 with all original gates intact. Their accepted causal self-replays provide reference observations only; self-replay is never treated as human-form truth. Each of eight outbound/return progress bins must contain at least three distinct self-cycles and five observations. Bands use reference-only 10th/90th percentiles and bounded MAD. Source labels, source counts and later verdicts cannot select the reference or fitted bands.

The added research guard retains the existing engineering allowances and temporal requirements: 12-degree acceptable allowance, 30-degree minimum clear-deviation allowance, 160 ms persistent fault confirmation, 250 ms maximum gap, 90% directly observed duration and 10% maximum ambiguity. Reference noise can increase its allowance by the same MAD formula; noisy/under-supported reference bins are withheld. These are existing comparator defaults, not newly established clinical standards. No pose is repaired, time-stretched or interpolated.

Only already-qualified shoulder attempts may be further withheld; an unknown/rejected/missing event cannot become qualified. Persistent clear disagreement would reject; sustained grey-zone disagreement withholds as unassessable. A preliminary unfrozen clear-only development screen removed no counts, and its raw output/model are preserved separately. The finalized `/2` candidate includes the original duration-weighted ambiguity semantics and is the reproducible trial below. No clear fault is confirmed in the changed outcomes: six incorrect counts become **unassessable**, not newly human-verified error detections.

## Results on all 160 original development repetitions

| Measurement | Existing engine | Additional plane guard |
|---|---:|---:|
| Correct-labelled reps counted / 80 | 31 | 26 |
| Incorrect-labelled reps counted / 80 | 27 | 21 |
| Counted-event precision | 53.45% | 55.32% |
| Correct-rep recall | 38.75% | 32.50% |
| Assessable coverage | 50.63% | 43.75% |
| Unassessable repetitions | 70 | 81 |

All original episodes, labels, unmatched events and denominators remain. Squat outputs are untouched; the guard cannot repair the existing squat range/hip failures. The five lost correct counts all come from subject 01 comparing with the fixed subject-02 reference. Their plane ambiguity spans approximately 19.0–34.2% of the attempt. Incorrect-labelled counts newly withheld include ambiguity as low as 10.3–14.3%. These overlapping disagreements are evidence against tuning one angle threshold to separate clinical correctness reliably across people.

The original reference target is preserved. A label for a broadly correct clinical exercise and close imitation of one person's reference plane are different objectives. Neither a neutral offset that erases a bad starting pose nor blanket tolerance widening is introduced to conceal that difference.

The candidate is frozen as **REJECTED_AFTER_DEVELOPMENT** in `models/uiprmd-plane-guard-protocol.json`, with model/result/source hashes. `validation_evaluation_authorized=false` blocks this failed candidate before any validation source access. Final-test access and validation refitting also remain forbidden. A future different candidate needs its own specification; do not edit this rejection into an apparent validation success.

## Verification and continuation

All **217 core-motion JVM tests** and **64 Python tests** pass, including seven new guard tests for dwell, gaps, missing observations, original threshold boundaries, reference-cycle support, no future-source dependence, no upgrading and failed-candidate access denial. The new Kotlin exporter reads only the two development references. Production source directories are byte-equivalent to the preceding verified engine, so no new Android runtime, sustained-performance or APK improvement is claimed. The previous 265 scoped JVM / 25 native / 2,688 control / 124 corruption checkpoint remains separately preserved.

One new original-marker sheet was generated and inspected: six timestamped XZ/YZ views for the first newly withheld correct and incorrect development attempts, with actual reference bands. Source markers, zero/missing handling and original acquisition times are retained; no new human labels are assigned. Frozen final subjects 08–10 remain unopened.

The next useful work is to evaluate a different representation or reference-only extraction/calibration method against explicit source/target scope. Do not repeat this scalar plane veto, reintroduce the rejected whole-attempt classifier, or send a candidate that fails development to the final holdout. Independent RGB data/licensing remains an open evidence gap; additional primary sources reviewed this round are recorded in `INDEPENDENT_RGB_DATA_REVIEW.md`.

Reproduce development only with the pinned source/environment and existing exports:

```powershell
./gradlew.bat :core-motion:uiprmdPlaneReferenceInputs --no-parallel
# Existing fitted reference models are immutable: omit --fit-references on a repeat run.
python scripts/evaluate_reference_plane_guard.py --scope development
python scripts/plot_reference_plane_development.py
python -m unittest discover -s scripts/tests -v
```

`models/uiprmd-plane-guard.provenance.json` binds the rejected result, both reference models, access receipts, original-frame QA and test records. This experiment earns no readiness points.
