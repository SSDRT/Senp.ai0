# Completed-attempt quality-head experiment

This experiment fits a small additional classifier using the existing UI-PRMD publisher labels. It does not train MediaPipe, modify raw marker coordinates or change the Android engine. It can only veto an already qualified completed attempt. It cannot create counts, bypass reference/range/confidence gates or identify the onset of a joint fault from whole-rep labels.

The input is the unchanged compiler `/4` continuous replay from engine commit `01b83e46f4fd6c5c53f12372f568e557b6e8774b`. All 160 original repetitions remain in the metrics: 80 correct and 80 incorrect, two variants, four development subjects. Temporal matching uses the existing label-blind one-to-one IoU protocol. There are 151 matched emitted attempts; missing events remain in denominators and unmatched qualified events remain false positives.

## Measured development results

Each movement has its own model. Four leave-one-subject-out folds ensure a person's trajectories do not fit the model that predicts that person. The 64 inputs are quantiles and observed fractions of 16 geometric descriptors, using observations only up to causal completion. Median imputation and scaling fit training folds only; raw pose evidence is never filled. Scores are uncalibrated classifier outputs.

| Operating point | Correct counted / 80 | Incorrect counted / 80 | Counted-rep precision | Correct-rep recall | Assessable coverage |
|---|---:|---:|---:|---:|---:|
| Existing reference engine | 31 | 27 | 53.45% | 38.75% | 50.63% |
| Logistic C=1, veto score >=0.50 | 20 | 5 | 80.00% | 25.00% | 50.63% |
| Logistic C=1, veto score >=0.75 | 30 | 8 | 78.95% | 37.50% | 50.63% |
| Logistic C=1, veto score >=0.90 | 31 | 14 | 68.89% | 38.75% | 50.63% |

At 0.75 this removes 19/27 previously counted incorrect reps (70.37%) while retaining 30/31 previously counted correct reps (96.77%). Precision improves by 25.50 percentage points. These are development skeletal results, not live-camera precision or a 70/100 milestone. Whole-attempt classification accuracy on the 151 matched attempts is 74.83%, a separate metric with a different denominator.

Three model families/configurations (logistic C=1, logistic C=0.1, random forest) and four development thresholds were examined. An initial feature pass is preserved in the ignored `quality-head-history/initial` snapshot; it must not be substituted for the current report. The chosen 0.75 threshold is a development choice, not a preregistered threshold for those development results. All six full-development fitted models are research artifacts, not production assets. Scikit-learn is BSD-3-Clause; UI-PRMD is PDDL 1.0 with source attribution in the acquisition protocol.

## Validation boundary

`models/uiprmd-quality-validation-protocol.json` freezes the C=1 model, 0.75 veto threshold, feature schema/source hashes, fitted model hashes and fixed subject-01 correct references before opening validation trajectories. Subjects 05–07 are validation only. Subjects 08–10 remain sealed; a candidate must satisfy validation criteria before a separate frozen final-test evaluation. Validation uses original continuous recordings and original aligned rep segments, not concatenated clips. No validation examples fit models or imputation. Every archive/member read must have a SHA-256 receipt.

The experiment cannot raise recall above the reference engine's ceiling because it only removes counts. Neither configuration meets the frozen precision >=90%, recall >=70%, coverage >=80% requirements. No readiness points are earned and no APK change is promoted. Current readiness stays **42.5/100**; live human-rep precision is unmeasured.

Verification: all 39 Python acquisition, geometry, matching, split, scoring and quality-head checks pass. Production sources and APK are unchanged, so prior production/native receipts remain associated with `01b83e4`, not with retraining these experimental models. Reproduce with the pinned research environment and `python -m unittest discover -s scripts/tests -v`, then `python scripts/train_uiprmd_quality_heads.py`. Local model and result SHA-256 receipts are in `models/uiprmd-quality-development.provenance.json`.
