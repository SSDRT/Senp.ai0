# Reference-cycle continuity

Compiler policy `qualified-rep-compiler/4` prevents a calibration cycle, ready dwell or repeated-extremum detector from carrying state across a spatial-segment boundary. It also disarms cycle selection when a critical feature disappears before departure, matching the existing live engine. Complete cycles from separate segments of the same tracked person can still contribute. References with multiple tracked identities remain unavailable.

Previously, the reference selector could retain five candidate cycles when one crossed a segment boundary or started after a brief loss of critical evidence. Those constructed references still qualified under the unchanged 80% reconstruction gate, including the noncausal fifth cycle in their learned curves. The fix excludes that cycle before fitting. The four continuously observed cycles remain, and all four reconstruct. This is evidence integrity, not an improvement in human-form accuracy measured by self-replay.

Repeated endpoint estimation now resets at the same boundaries. A constructed sequence of stationary segments at alternating joint angles used to resemble repeated extrema across the cuts. With segment provenance supplied, it yields no repeated movement endpoints. Actual boundary observations and timestamps are retained; no frame is dropped, repaired or retimed.

Five JVM tests cover a boundary within an attempt, a boundary within ready dwell, missing critical evidence before departure, complete valid cycles in separate segments, and stationary segments with artificial movement only between cuts. Three tests reproduced the cycle-selection problem before the fix. Native instrumentation repeats the three failing cases against the packaged engine.

There are no changes to the landmark networks, live state machine, selected-side feature definitions, confidence/coverage thresholds, angle allowances, path requirements, confirmation times, minimum three reference cycles or >=80% reconstruction gate. Reference keys are intentionally invalidated; the runtime schema remains `qualified-reference-reps/5`.

## Fixed-input evidence

All eight Full/Heavy × ordinary/bounded-fit × capped/complete replay configurations were regenerated from the original raw MP33 caches. The chosen backends/fits, counts, attempt boundaries, verdicts, faults and calibration availability match baseline `8225432` exactly. No reference is gained or lost.

| Reference cohort | Distinct usable reference/sides | Replayed source/side runs | Qualified outputs |
|---|---:|---:|---:|
| Capped | 10/32 | 30/96 | 18 |
| Complete | 12/32 | 38/96 | 31 |

These are availability and implementation-output counts, not human accuracy. The inspected original timestamped reference frames and overlays remain unchanged, including press, curl and push-up examples. No raw landmarks, original PTS or native raw-landmark fixtures were edited.

The independent skeletal development replay is unchanged: 31 correct and 27 incorrect repetitions counted from 80 correct / 80 incorrect labels, giving 53.45% development precision, 38.75% good-rep recall and 50.63% assessable coverage. It remains ineligible for the independent held-out gate. Validation subjects 05–07 and final subjects 08–10 remain untouched. No classifier or landmark network was trained.

The frozen readiness score remains **42.5/100** once the current-engine verification receipts pass. This fix earns no extra test-count points, calibration-coverage points or independent-accuracy points. The 70 milestone is not achieved.

## Verification and reproduction

Final JVM/native counts, APK checks, replay hashes and any failures/retries are recorded in `models/reference-continuity.provenance.json` and the delivery evidence archive. The Android runtime is the available Windows API 36 x86_64 / 4 KB-page emulator; the historical Fedora environment is unavailable. No physical-phone, sustained-performance or universal-device claim is made.

Use `:core-motion:verifyMotionCore`, the four qualified-rep mechanism/path/departure/evidence benchmarks, `:core-motion:qualifiedRepCounterfactualBenchmark`, scoped pose/session JVM tests and both connected Android suites. Session/counterfactual replay defaults now point to `reference-continuity-summary`; older reports remain intact. Run `:core-motion:uiprmdDevelopmentReplay` and `scripts/evaluate_uiprmd_development.py` for the unchanged development diagnostic. Do not inspect the frozen final split before committing a complete candidate and evaluation protocol.

The next accuracy work remains broader reference-relative measurements, evidence for cross-person range variation and independent validation. This continuity fix does not solve faults absent from the current two-angle policies or justify widening tolerances.
