# Independent skeletal development checkpoint

The uploaded reference remains the movement target. This checkpoint fixes a provenance bug in reference self-reconstruction and introduces an independent, continuous-repetition development benchmark. It does **not** establish live-camera accuracy, train landmark-network weights, pass the independent readiness gate, or raise readiness to 70.

## Compiler fix

The sequence compiler accepted a declared `RepEvidenceFingerprint.preprocessingId`, but its internal causal self-replay omitted that ID. The replay silently used the default MP33 camera pipeline ID. Consequently a valid alternative measurement pipeline failed the runtime mismatch gate during its own calibration, producing `LOW_SELF_RECONSTRUCTION` even for a clean reference. Two regression tests reproduced this failure before the fix.

Self-replay now passes the declared ID explicitly. Live mismatched IDs still fail closed. Compiler policy is `qualified-rep-compiler/3`; runtime reference schema remains `qualified-reference-reps/5`. Reference keys are intentionally invalidated. Confidence, path, fault, dwell, range, reconstruction and visibility thresholds are unchanged. The MP33 product pipeline continues to use its original ID.

## Measurement and provenance

The [original UI-PRMD data descriptor](https://pmc.ncbi.nlm.nih.gov/articles/PMC5773117/) records Vicon at 100 Hz in millimetres and lists 39 **position markers** in Table 2. Its Vicon joint-angle table has a different ordering and different entities. Kinect's parent-relative 22-joint skeleton is a separate representation. This benchmark uses Vicon position markers directly, avoiding Kinect reconstruction and conflicting Euler-order assumptions in the publisher's visualisation code. Vicon's own [marker trajectory documentation](https://help.vicon.com/download/attachments/673091964/Vicon%20Nexus%20Reference%20Guide.pdf) describes global XYZ marker components; the development marker positions and representative XZ/YZ plots are consistent with lab-global coordinates.

`uiprmd-vicon39-marker-angle/1` derives the same selected-side angle definitions used by the rep engine from explicitly labelled marker proxies. The hip proxy is LASI/RASI, shoulder LSHO/RSHO, knee LKNE/RKNE, ankle LANK/RANK, elbow LELB/RELB, and wrist the midpoint of the two observed wrist markers. These surface markers are **not anatomical joint centres**. All original 39-marker rows remain in the verified, ignored publisher archives; no MP33 joints, MediaPipe visibility/presence or camera confidence are invented. The backend hash identifies the marker schema/geometry protocol, not a trained landmark model.

Finite nonzero observations receive binary acquisition observability; missing/zero/nonfinite markers and degenerate rays mask the affected angle. A missing wrist marker cannot be repaired by averaging it with a valid marker. There is no smoothing, interpolation, skeleton scaling, bone-length correction or time warping. Development data include 3,165 all-zero marker samples; these stay missing. No body transform or model certainty is inferred from this binary observability.

Unit tests check known angles, rigid-transform/scale invariance, marker ordering, degenerate/missing geometry, and segmented alignment. Raw development limb-marker spans were inspected (approximately 246–349 mm upper arm, 360–476 mm shin across subjects); these are diagnostics and never constraints applied to coordinates. Original standing/peak markers and angle traces for correct s01/s04 and incorrect s02 were inspected. These checks validate the adapter's declared geometry, not its equivalence to anatomical joint centres or camera estimates.

## Continuous labels and split

The publisher's full and segmented files are from the [PDDL dataset release](https://zenodo.org/records/23104965). For subjects 01–04, both variants and both correctness classes, all **160 original rep excerpts** align contiguously to their full recordings. Text exports differ by at most `5.000000000005e-7 mm` on a few coordinates; alignment requires every coordinate/frame to agree within a fixed absolute `1e-6 mm` bound, with no relative tolerance or resampling. Each excerpt must have one unique match and all ten episodes must remain ordered and nonoverlapping. The full recordings, including interruptions, endpoint dwell and missing observations, are replayed uncut at their original 10 ms acquisition intervals.

The frozen split stays development 01–04, validation 05–07, final test 08–10. Only development trajectories have been read. Reference subject 01 is selected for source subjects 02–04; source subject 01 uses reference subject 02. This choice is fixed without looking at source outcomes. Original labels, rep boundaries and source correctness are never passed into the production rep engine.

Evaluation matches emitted attempts to original rep intervals by temporal intersection-over-union >=0.25, greedily greatest overlap, one-to-one, with stable index ties. Matching is independent of correctness and verdict. Missing/unassessable events stay in recall and coverage denominators. An unmatched or duplicate qualified event is a false positive. Labels apply to complete repetitions and do not identify a joint fault or its onset time.

## Development result — not a held-out accuracy estimate

| Measurement | Deep squat m01 | Right shoulder abduction m07 | Combined |
|---|---:|---:|---:|
| Correct / incorrect labelled reps | 40 / 40 | 40 / 40 | 80 / 80 |
| Correct reps counted | 26 | 5 | 31 |
| Incorrect reps counted | 9 | 18 | 27 |
| Qualified-rep precision | 74.29% | 21.74% | **53.45%** |
| Correct-rep recall | 65.00% | 12.50% | **38.75%** |
| Assessable coverage | 65.00% | 36.25% | **50.63%** |

Across 160 reps, 58 were counted, 23 rejected, 70 unassessable and nine had no emitted attempt. No incorrect labels were dropped. All 16 reference/source recording runs were replayed after the pipeline fix; before it, all 16 were withheld by the erroneous self-replay mismatch. That change is benchmark plumbing, not a 0-to-100% accuracy gain. Every reference uses the unmodified >=80% causal reconstruction requirement.

The readiness requirement for independent evaluation is >=90% precision, >=70% correct-rep recall and >=80% assessable coverage on a frozen subject-disjoint **final test**, across at least two variants and three test subjects. Development fails all three numerical gates and is ineligible for points regardless. Actual live-camera human-form precision remains unmeasured. The fixed readiness score is still 42.5/100 when the existing regression/native receipts pass; development metrics do not change that rubric.

## What the failures imply for tuning

- Current qualified rules compare a driver and one quality angle per selected side. They cannot identify faults in unmeasured planes, wrist/head alignment, bilateral symmetry or trunk compensation. A full-body label can therefore disagree with a correct selected-side match. Keeping these negatives exposes the limitation.
- Correct subjects also differ from the single uploaded reference. For example the development shoulder marker-proxy maximum ranges about 119.5–148.5 degrees among correct recordings. Source subject 03's incorrect maximum is about 113.1, close enough to subject 01's reference to count some incorrect reps, while the wider correct source ranges are withheld as ambiguous. A dataset correctness label and strict imitation of a particular uploaded target are not interchangeable.
- The clinical shoulder-abduction variant is not automatically equivalent to a bodybuilding lateral raise, and marker proxies are not clinical joint angles. Do not replace the uploaded target with a universal 90-degree shoulder rule or interpret all published correct examples as identical trajectories.
- A preliminary development-only body-plane probe did not cleanly separate correctness classes. It was not promoted, used to remove negatives or used to pick thresholds. Widening reference tolerances to count more good reps would not resolve the false positives and is not justified by this result.

Next, investigate additional **directly observed** reference-relative body-plane/symmetry features and cross-person calibration evidence. Test each candidate on development before selecting it on 05–07. Preserve raw confidence/provenance and range/fault safeguards; don't tune on the untouched 08–10 subjects. A skeletal-only classifier must not be shipped as a camera-calibrated model, and no learned score can silently override qualified-rep evidence gates. Do not open the final test until a complete candidate and evaluation protocol are committed.

## Verification checkpoint

The promoted compiler fix passed 241 scoped JVM cases, 22 native suite cases on the Windows API 36 x86_64 / 4 KB-page emulator, 2,688 generated mechanism controls and 124 controlled corruptions. All eight Full/Heavy × standard/bounded-fit × capped/complete camera replays preserve the preceding compiler's counts, verdicts, fault outputs and reference choices. Complete coverage stays 12/32 distinct reference/sides (38/96 source runs); capped coverage stays 10/32 (30/96). There are no camera accuracy gains credited to this fix.

Thirty-two Python research/geometry/evaluation tests pass. The separate 36-case shell-based QA suite passes in WSL Ubuntu 24.04; its POSIX mock executables cannot run as native Windows processes. APK signature, ZIP alignment and static 16 KB ELF/ZIP checks pass; a real 16 KB runtime and physical handset remain untested.

The full 22-case native suite passed. A later nine-case export run passed eight cases but the Heavy camera launch test produced only two frames within its unchanged 20-second deadline (three required). Its isolated retry passed, including model identity and pause/resume; both traces are retained. This is an observed timing instability, not a sustained-FPS result or proof of a resolved performance issue. No timeout was widened. All nine distinct export cases have passing evidence, including the new custom-pipeline guard, but the initial failure must remain disclosed. No 10-minute performance point is claimed.

## Reproduce

Use Python with NumPy for `scripts/prepare_uiprmd_development.py`, `scripts/evaluate_uiprmd_development.py` and the `test_uiprmd*.py` tests; Matplotlib is additionally required for `scripts/plot_uiprmd_development.py`. Run `:core-motion:uiprmdDevelopmentReplay` between export and evaluation. The exporter verifies publisher archive SHA-256 receipts before reading and refuses held-out trajectories. Reports and plots live under ignored `test-artifacts/exercise-knowledge/uiprmd/`; small hashes/results are committed under `models/`. The Gradle replay checks every exported-record hash and refuses anything outside development.
