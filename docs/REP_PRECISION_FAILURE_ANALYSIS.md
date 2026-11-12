# Why precision is unmeasured, and what changed after a5fbd94

22 October 2026. Same branch: `codex/live-pose-accuracy`. Device coverage is outside this tuning task.

**Current real-world qualified-rep precision is unknown.** There are no independently reviewed per-rep/reference-relative labels for this replay corpus. The 98% number is a release target, not a measured starting point. No honest percentage below 98% can be substituted without that ground truth.

Precision means: of all repetitions the engine counts, what fraction independently qualify as correct or acceptably close to the selected uploaded reference? It is different from pose landmark error, reference calibration availability, good-rep recall, and test pass rate. An engine counting almost nothing could have high precision and still fail the product requirement; the existing 90% eligible-good acceptance target must also pass.

## What 12/96 meant

The corpus has 48 source videos, each replayed for two selected sides. References are reused across source videos. There are only **16 distinct reference videos / 32 reference-side configurations**, not 96 independent demonstrations. The reference videos come from two people; the source corpus uses three previously seen participants. Neither the calibration nor the new improvements establish generalization to fresh people.

| Calibration result | a5fbd94 replay runs | Current replay runs | a5fbd94 unique reference/sides | Current unique reference/sides |
| --- | ---: | ---: | ---: | ---: |
| Usable | 12 | 20 | 4 | 7 |
| Insufficient usable cycles | 42 | 26 | 13 | 8 |
| Missing critical samples | 24 | 32 | 8 | 11 |
| Failed reference self-replay | 14 | 10 | 5 | 3 |
| Weak/noisy curves | 2 | 6 | 1 | 2 |
| Insufficient excursion | 2 | 2 | 1 | 1 |
| Total | 96 | 96 | 32 | 32 |

Availability rose from **12.5% to 20.8%**: +8.3 percentage points, or 66.7% more replay configurations. Unique usable reference/side coverage rose from 12.5% to 21.9%. These percentages measure availability only.

## Causes found in code and actual source frames

1. **The driver range could change during curve fitting.** Progress was initially defined from one pair of reference endpoints, then the final comparator recomputed those endpoints from fitted phase-bin medians. In narrow bins the old regression was disabled, which could shrink the excursion. A right-arm curl reference initially produced six acceptable reps when replayed over its full timeline, yet the calibration gate matched only one of six selected cycles because completion times had moved. Driver fitting now uses its exact phase derivative, preserving its endpoint definition. The 300 ms self-replay matching limit remains unchanged.

2. **Setup movement could define the workout range.** In one press reference the person picks up weights before pressing. Whole-clip low-percentile shoulder angle was about 61°, while repeated press bottoms were much higher. Treating the setup pose as the required return position prevented workout cycles from completing. Repeated extrema now estimate the exercise range from adjacent supported observations and sustained reversals. At least three observed minima and maxima are needed by default; whole-clip percentiles remain the fallback. The normal cycle, direct-endpoint, confidence, noise and self-replay gates still apply. This improves the candidate range but does not make every press reference usable.

3. **Some requested measurements are absent.** In frontal push-up recordings, the required shoulder–hip–ankle body-line measurement has no samples meeting the confidence threshold. Many right-side tests on side-view footage likewise have an occluded far arm or leg. These cannot certify full selected-side form. Required-feature preflight now reports that evidence gap explicitly. Consequently the missing-feature count increases while the misleading insufficient-cycle count decreases; that reclassification is not a regression or a precision gain.

4. **Visible motion does not guarantee reliable inferred 3D angles.** Confidence reflects estimated landmark observability, not a calibrated probability of correct geometry. Signals contain rapid jumps and missing driver intervals, notably one frontal squat reference. Some surviving references cannot consistently reproduce their own selected cycles. Other curves fail the existing angle-variation limits. These references remain withheld.

5. **The current features are incomplete.** Each initial policy has one driver and one other selected-side angle. This cannot establish every technique error or distinguish all exercise variants. Reference-relative disagreement between two people can reflect real range/variant differences or measurement bias; whole-clip labels do not resolve which.

The audit's basic screen—at least 90% simultaneous required-feature visibility at confidence 0.65, and at least 20° driver q10–q90 span—passes 12/32 reference-side configurations. Seven currently calibrate; the other five fail self-replay (two), curve reliability (two), or cycle count (one). This is a post-hoc engineering screen, not expert eligibility or an accuracy denominator. The full 96-run report remains available and no failed runs are removed from it.

All 16 reference clips were also requested at 33 ms extraction spacing. They are 15 FPS source videos; this added no original frames and was not adopted as a claimed improvement. No duplicated or interpolated images were used.

## Changes and validation

- Exact driver endpoint preservation, robust repeated-extrema estimation, and clearer missing-feature failures are implemented. Acceptance allowances, confidence/coverage requirements, minimum cycle count and the 80% self-replay requirement were not loosened.
- Three added regression cases cover narrow endpoint bins, long setup movement, and isolated spikes/missing reversals. Existing state-machine and live-session tests remain in place.
- The 1,152 generated rep scenarios still pass. They verify mechanics; they do not establish camera precision.
- Across newly available and previously available replay runs, current outputs are 1 correct + 11 acceptable, 43 rejected, and 123 unassessable. Because availability changed, comparing these totals directly with the previous 3 accepted / 39 rejected / 84 unassessable would be misleading.
- On the **same 12 previously usable runs**, accepted outputs changed from 3 to 2; rejected outputs remain 39 and unassessable outputs 84. Event boundaries also change and some movements no longer open an attempt. These are behavioral differences requiring independent review, not proof of improved quality. More calibration successes alone must not qualify this engine for release.
- Native landmark replay continues to match JVM counts, verdicts, attempt boundaries and progress. A revised expected fixture reflects the new curves and explicitly compares invalidation reasons across adapter identity conventions. The fixture is an implementation parity target, not a human annotation.
- Actual source frames and angle/confidence traces were inspected, including weight pickup, alternating curls, frontal push-ups and a noisy frontal squat. The evidence package retains both baseline and current replay summaries/traces.

The pose landmark CNN weights were **not retrained**. These changes tune reference calibration and the causal decision engine. Written exercise rules cannot, on their own, supervise image-to-landmark accuracy or supply independent per-rep truth.

## Work required to pursue 98% precision

1. Independently review complete source intervals against the uploaded reference, including attempts absent from predictions. Label correct, acceptable, clearly incorrect and unassessable reps, rep boundaries, fault onset, visible side and variant. Two independent reviewers and qualified adjudication are required by the prepared scorer. Current 96 tasks are still empty; they are development data, not a held-out test.
2. Use development/validation participants to diagnose each error category separately: missed/duplicate cycles, near-correct rejection, false acceptance, wrong phase, model geometry error, and visibility failure. Tune per-exercise/view thresholds and reference curve estimation against those labels, with a penalty for missed good reps and false acceptance. Do not optimize only the number of accepted reps.
3. Compare stronger free pose backends and temporal quality methods on exactly the same reviewed examples before selecting one. Preserve actual timestamps, observed confidence and uncertainty. Train a small temporal quality model only when per-rep/fault labels support it; do not turn existing clip labels into invented fault timestamps. Avoid adapting a user's repeated errors into a new reference target.
4. Freeze the candidate and test on fresh people, fresh uploaded demonstrators and unexposed recordings. Report precision, eligible-good acceptance, bad acceptance/rejection, abstention, calibration coverage and fault latency by exercise/view, with participant-grouped uncertainty. Repeat tuning on validation data if the release criteria fail; keep the fresh test set isolated.

The target is at least 98 counted reps truly qualifying per 100 counted, while also accepting at least 90% of eligible good reps. A small sample or self-replay cannot substantiate that result. No 98% guarantee is made before the independent test passes.

## Research-data check

The original [UI-PRMD paper](https://pmc.ncbi.nlm.nih.gov/articles/PMC5773117/) provides joint-position/angle data from Vicon and Kinect for rehabilitation movements. Such data can test skeletal assessment methods but cannot establish this app's smartphone image-to-pose accuracy. Its sensor geometry and movement coverage differ from the live pipeline.

[Fitness-AQA's authors](https://github.com/ParitoshParmar/Fitness-AQA) describe form assessment for back squat, overhead press and barbell row, and require an access request with non-commercial dataset terms. It was not downloaded, used for training, or treated as unrestricted production data. Public research resources can supplement a suitable benchmark; they cannot make the current unreviewed outputs into ground truth.

## Reproduce the audit

```powershell
.\gradlew.bat :core-motion:qualifiedRepCalibrationAudit
.\gradlew.bat :core-motion:qualifiedRepVideoReplay -PqualifiedReplayOutput=test-artifacts/exercise-knowledge/qualified-video-replay-v5
python scripts/export_qualified_rep_fixture.py --replay-directory test-artifacts/exercise-knowledge/qualified-video-replay-v5
```

The ignored audit output includes all 32 unique configurations, required-feature samples, angles/confidences, failure messages, and self-replay attempts. Diagnostic counterfactuals disable only the self-replay gate to explain its failures; they are explicitly marked and are never returned by the production compiler or included as usable replay results.
