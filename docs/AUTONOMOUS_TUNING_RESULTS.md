# First autonomous training and tuning cycle

Follow-up: the reference-only backend experiment is now integrated in the experimental qualified-rep session. See [reference backend integration](REFERENCE_BACKEND_INTEGRATION.md) for production-seam replay, native verification and the APK scope. The results below remain the historical first-cycle record.

23 October 2026. Branch: `codex/live-pose-accuracy`. Baseline: `0f768e8`.

New manual labels, reviewer recruitment and new human recordings used: **zero**. The revised executable sequence is in `AUTONOMOUS_POSE_TUNING.md`. Existing licensed dataset labels were reused at their original whole-clip granularity. This run performs actual offline training, model extraction, automatic parameter search and real-video replay; it does not retrain the landmark CNN or establish 98% live-rep precision.

## Training on the existing labelled corpus

Read all 1,042 original coarse MP33 feature caches from Mendeley V3, DOI `10.17632/kgbb3yn47p.3`, CC BY 4.0. Of these, 1,030 have at least six observed features; 12 exclusions are recorded. No new frame/rep/fault labels were created from the video labels.

Fit four candidate families for each of seven exercise classes: two logistic-regression settings, extra trees and gradient boosting. Select among those 28 model/exercise combinations using four performer-grouped folds of the existing training participants. Fit imputation/scaling inside each fold, select an alert threshold using separate validation participants, then evaluate frozen models on historical audit participants. These audit participants were previously inspected and are not a new untouched test cohort.

One statistical example represents one whole clip. Models use relevant observed geometry summaries, coverage and coarse temporal variation. The 500 ms source sampling cannot validate immediate faults, and whole-clip aggregation includes future observations relative to a live frame. These models remain offline research artifacts.

| Exercise | Selected candidate | Retrospective audit clips | Clip-label ROC AUC |
| --- | --- | ---: | ---: |
| Standing side bend | Logistic C=0.1 | 24 | 0.410 |
| Bent-over row | Logistic C=0.1 | 18 | 0.407 |
| Biceps curl | Logistic C=1 | 24 | 0.736 |
| Push-up | Logistic C=1 | 18 | 0.840 |
| Overhead press | Extra trees | 24 | 0.736 |
| Squat | Extra trees | 24 | 0.674 |
| Overhead triceps extension | Gradient boosting | 28 | 0.546 |

ROC AUC is a ranking metric, not percent form accuracy. The newly covered clips differ from the previous model's reliable-window cohort. On exactly the shared covered audit clips, AUC changed as follows: side bend 0.500→0.375, row 0.733→0.500, curl 0.486→0.611, press 0.828→0.813, squat 0.599→0.648, triceps 0.630→0.660. Push-up had no supported earlier comparator. Improvements are mixed; no model is promoted just because one result is better.

None of the seven selected models obtained the predeclared validation alert operating point: at least 95% bad-clip alert precision, at least ten alerted validation clips and at least three alerted validation performers. All seven trained artifacts are saved with feature definitions and hashes; no alert threshold is enabled and none feeds live rep qualification.

## Full versus Heavy extraction

Processed the same 48 high-cadence development videos with the pinned Heavy landmark model. All **16,633 original source timestamps** match the existing Full extraction exactly. Source-video hashes and model identities were verified. Full SHA: `5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1`; Heavy SHA: `64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b`.

Heavy extraction took about 447 seconds using three desktop CPU workers. Recorded median inference was about 70.6 ms and P95 about 92.7 ms. These are concurrent research timings, not phone latency or a fair Full-versus-Heavy speed comparison; the older Full caches have no comparable timing field.

Both default backends calibrate 20/96 replay configurations and seven unique reference/sides, but five of those reference/sides overlap. A reference-only Full-first/Heavy-fallback simulation therefore covers **26/96 configurations and 9/32 unique reference/sides**. The selection uses reference calibration availability only and stays fixed for both reference and source. It does not use source labels, source scores or the outcome giving the most counts. This is an offline selection result, not new Android backend-switching behavior.

On the same 14 replay configurations available under both default backends, accepted outputs in good-labelled clips changed from 1 to 10, and in bad-labelled clips from 6 to 1. This is promising weak-label evidence. A bad-labelled clip may contain acceptable reps; these buckets do not establish which particular reps are correct.

Same-PTS source contact sheets and angle traces were inspected. They show substantial world-angle differences on similar image-space poses, missing evidence in blurred/cropped regions, and mirrors plus a rear-facing foreground person in a recording labelled front-view squat. Such input raises person/view ambiguity. More confidence or agreement must not be mistaken for verified geometry or correct person selection. This is a reason to prioritize identity/view and measurement consistency in the next autonomous cycle.

## Automatic parameter search

Searched 27 combinations using 10,692 generated training cases. Selection uses only training seeds/frame rates. Evaluate the frozen candidate and baseline on 396 separate generated checking cases each, for 792 checking executions total.

The generated optimum uses an acceptable allowance of 16° instead of 12°, 120 ms fault confirmation instead of 160 ms, and retains 85% required excursion. Tie-breaking favors the existing range setting when otherwise equal. These are experimental parameters, not new expert anatomical standards.

| Generated checking result | Current defaults | Candidate |
| --- | ---: | ---: |
| Oracle good/near-correct motions accepted | 105/108 | 108/108 |
| Oracle clearly bad motions accepted | 0/72 | 0/72 |
| Oracle clearly bad motions rejected | 72/72 | 72/72 |
| Oracle uncertain motions accepted | 0/72 | 0/72 |
| False counts in stationary/spike/start-mid controls | 0 | 0 |
| Brief sub-dwell transients hard-rejected | 0 | 0 |
| Maximum source-time fault-confirmation delay | 186 ms | 169 ms |

Good acceptance improved by 2.78 percentage points on that generator's checking cases. The same generator family supplies both partitions, so this is not camera generalization. Generated near-correct labels implement the declared engineering oracle, not independently assessed human technique.

## Real-video candidate replay

| Configuration | Replayed / 96 | Unique usable references / 32 | Counted outputs | Counted in good-labelled clips | Counted in bad-labelled clips |
| --- | ---: | ---: | ---: | ---: | ---: |
| Full, current defaults | 20 | 7 | 12 | 5 | 7 |
| Heavy, current defaults | 20 | 7 | 15 | 14 | 1 |
| Full, generated candidate | 24 | 8 | 26 | 14 | 12 |
| Heavy, generated candidate | 24 | 8 | 21 | 20 | 1 |

Rows with different availability do not have identical prediction denominators. Even on identical clips, whole-clip labels cannot validate each individual rep. The generated candidate raises accepted outputs on both clip-label classes under Full, so its synthetic improvement does not justify applying a wider allowance globally.

Decision: preserve the current live defaults. Retain the parameter file, seven trained models, Full/Heavy comparisons, agreement-only evidence and reference-backend simulation as reproducible experimental candidates. This decision is made from available automated evidence and does not wait for a new manual review step. The app remains at its existing build; there is no claimed new APK or deployed learned acceptance model in this research cycle.

## Verification and next autonomous steps

- Core checks and boundary verification passed; existing 1,152 generated mechanism scenarios passed.
- 36 Python QA cases passed, including source-outcome-independent backend selection checks.
- Two additional research-runtime tests passed, checking that missing observations do not become coordinates/velocities across gaps and that prediction cannot refit training imputation.
- All seven trained artifact hashes were verified. Full/Heavy extraction retains source hashes, original PTS, all image/world landmarks and confidence. Per-model caches and replay outputs remain separate.
- Sixteen matched model/source overlay observations and four angle traces were visually inspected. Model disagreement is retained as agreement-only evidence, without form labels.

Next priorities under the same human-free workflow: investigate mirror/person identity ambiguity, compare separately qualified image-space and world-space measurements, extend controlled reference-bout tests, and add causal temporal training only with task-compatible existing labels or explicitly weak supervision. Continue grouped evaluation and keep a frozen audit record. Existing independent rep/keypoint datasets may provide stronger evidence without requiring new people to label our recordings; self-generated agreement cannot replace their independence.
