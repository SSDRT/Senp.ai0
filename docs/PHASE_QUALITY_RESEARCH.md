# Phase-specific completed-attempt quality research

27 October 2026. Frozen development-only hypothesis: whole-attempt quantiles discard when a geometric deviation occurs. Replacing them with original engine phase-specific geometry may better distinguish existing publisher labels across people. This is a separate candidate from the rejected scalar plane guard and whole-attempt quality head. No production code, raw landmark weight, confidence, reference target, range, fault, path or count gate changes.

Use the exact compiler `/7` development replay, fixed cross-person references and subjects 01–04 only. Generate passive phase diagnostics with the existing parity-checking observer. Original 100 Hz Vicon39 geometry supplies 16 descriptors; each has a median and observed fraction in six engine-defined regions: outbound/return × ready/middle/far, giving 192 inputs. Ready is progress <=0.10, far >=0.85, matching the existing boundaries. Do not normalize progress to the source peak, use episode boundaries as inputs, repair missing markers or read frames after causal attempt completion. Empty bins remain missing. Engine branch identity is causal; whole-rep labels do not become per-frame fault labels.

Fit exactly one logistic C=0.1, balanced, seed 17 candidate per movement/fold with a fixed bad-score veto threshold of 0.75. Use four leave-one-subject-out folds; imputation/scaling fit only the other three subjects and original matched whole-rep labels. Predict every emitted held-subject attempt, including unmatched events. Apply scores only to already qualified completed attempts. Keep unassessable/missing events and all 160 publisher repetitions in denominators. A veto can remove counts but cannot improve the existing engine's recall ceiling of 31/80. Scores remain uncalibrated research outputs.

Freeze code/input receipts and commit the protocol before executing the diagnostic export and fitting. Do not sweep parameters after viewing outcomes. Report precision, good-rep recall, assessable coverage and every changed attempt. Promotion requires retaining every previously counted good rep, no new incorrect counts, and the fixed >=90% precision / >=70% recall / >=80% coverage development screen. Failure stops this candidate before validation/final access; no app model or APK is produced. Validation requires a separate committed protocol if warranted. Final subjects 08–10 remain sealed. Development cannot earn independent-label readiness points regardless of its metrics.

This trial tests whether temporal descriptors provide useful rejection evidence without losing current good counts; it cannot solve reference-to-person range mismatch by itself. Keep its outcome separate from MediaPipe camera accuracy. The source data remains under the original UI-PRMD PDDL licence, with attribution and acquisition receipts in the existing protocol. NumPy and scikit-learn are open-source research dependencies already pinned in this workspace.

## Frozen development result: rejected, no validation access

Code/protocol/input receipts were committed in `07da3f9` before passive evidence export and fitting. The current comparator reproduces all baseline attempt fields with and without its observer. All 16 original development recordings and 160 publisher-labelled repetitions remain included. Eight fold models score all 151 emitted attempts using subject-disjoint predictions; in this cohort all emitted attempts have an original temporal match. Fitted model bytes, scores, source reads and training-subject rosters are retained. No parameter sweep or validation/final read follows the result.

| Development skeletal measurement | Existing reference engine | Frozen phase veto |
|---|---:|---:|
| Publisher-correct reps counted / 80 | 31 | 31 |
| Publisher-incorrect reps counted / 80 | 27 | 13 |
| Counted-event precision | 53.45% | 70.45% |
| Good-rep recall | 38.75% | 38.75% |
| Assessable coverage | 50.63% | 50.63% |

The candidate removes 14/27 previously counted incorrect reps (51.85%) without losing a previously counted correct rep. The 17.01 percentage-point precision difference is a **skeletal development observation**, not live-camera precision or a 70/100 readiness result. All 70 unassessable and nine missing repetitions remain unchanged; scores never upgrade them. The candidate still fails precision >=90%, recall >=70% and coverage >=80%, so its decision is `REJECTED_AFTER_DEVELOPMENT`. No validation/final access, app model, production change or new APK is authorized by this result. Engineering readiness remains **50/100**.

Six original timestamped Vicon39 views were generated and inspected: the first vetoed incorrect squat and the highest-score retained correct shoulder attempt, at causal start, fixed temporal midpoint and completion. All displayed markers are directly observed with zero missing samples. These views provide context and source QA, not new expert fault labels or proof of the classifier's reasoning. `models/uiprmd-phase-quality.provenance.json` binds 111 immutable receipts in `phase-quality-checkpoint-07da3f9`.

Verification passes 234 core-motion JVM and 115 Python cases, including seven new phase/timebase/scope safeguards. Production main sources match verified `1b52bcf`; all 1,336 existing scored receipts and the frozen rubric revalidate at 50. Earlier native and causal/corruption proofs remain scoped to that unchanged production source and are not rerun or attributed to this research head. No RGB video changes occur. Final subjects 08–10 remain sealed.

The result supports phase-specific descriptors as a possible future rejection input, but another veto cannot solve the measured 31/80 recall ceiling. Next work must resolve eligible reference evidence and reference-to-person range/variant generalization while preserving the demonstrated target and existing gates. Do not widen thresholds, add artificial endpoint dwell, infer fault-onset labels or tune this head on the final cohort to manufacture a passing result.

Reproduce from the frozen byte-identical source and preserved acquisition, in a fresh output directory (the original directory and models intentionally refuse overwrite):

```powershell
./gradlew.bat -I scripts/phase-quality-research.init.gradle :core-motion:phaseQualityEvidence :core-motion:verifyMotionCore --no-parallel
python scripts/train_uiprmd_phase_quality.py
python scripts/plot_uiprmd_phase_quality.py
python -m unittest discover -s scripts/tests -v
```
