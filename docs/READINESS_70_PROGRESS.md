# Readiness continuation checkpoint

Status on 24 October 2026: **42.5/100 under the frozen rubric; 70 has not been reached.** The persistent goal and the `senpai-readiness-to-70` thread heartbeat remain active. Do not change rubric weights or notify a milestone merely because more development work has been done.

## Completed in the first milestone run

- Froze `models/readiness-rubric.json` and added a receipt-checking scorer. The formal 42.5 baseline replaces an approximate subjective 40; no engine improvement is credited for that bookkeeping change. Immutable baseline receipts come from the prior `4d89989` delivery archive.
- Fixed a concrete reference-integrity error: cycles from multiple tracked identities can no longer be pooled to satisfy the cycle minimum. Missing/blank identities also fail closed. Compiler policy is `qualified-rep-compiler/2`, runtime reference schema remains `/5`, and all keys are invalidated deliberately. This is tracker provenance, not biometric person recognition.
- Replayed all eight combinations of Full/Heavy, standard/bounded fits and capped/complete references. Counts, intervals, verdicts, fault outputs, reference choices and calibration availability remain unchanged against the frozen `multipass-*` reports. See `readiness-identity-summary` under ignored research artifacts.
- Passed 239 scoped JVM tests, 21 Android suite cases, eight native export cases, 2,688 constructed controls, 124 controlled corruptions, 22 Python acquisition/scoring/research tests and 36 Python QA cases. Native inference uses the Windows API 36 x86_64 emulator. No physical-device or independent human-rep-precision claim is made.
- Downloaded and verified all four original UI-PRMD archives (969,527,269 bytes total) and the publisher README. Publisher MD5 and local SHA-256 receipts are in `models/uiprmd-acquisition.provenance.json`; raw archives remain ignored under `test-artifacts/exercise-knowledge/uiprmd`.
- Froze development subjects 01–04, validation 05–07 and final-test 08–10, with explicit squat/right-shoulder-abduction variants. Inspected 704 development measurement files only, accepting the publisher's comma and whitespace encodings and verifying 66-column Kinect / 117-column Vicon shapes. No validation or final-test trajectories were opened. No model was fitted and no readiness points were earned from downloading/inspection.

The first bulk transfer returned a truncated last range; the resumable downloader now retries truncated responses without appending incomplete bytes. It recovered the archive and passed the whole publisher checksum. Unit tests cover resumed ranges, wrong/missing ranges, corrupt/truncated data, retry integrity and holdout access. Do not silently overwrite corrupted files or treat partial downloads as fitting inputs.

## Next work, in order

1. Validate the independent skeletal measurement adapter **before fitting**. The publisher's coordinates are local to parent frames except roots, not global MP33 landmarks. The original visualization warns of lower-body reconstruction errors. Confirm joint parents, Euler conventions, roots and acquisition timebase from primary documentation; validate against known poses and source measurements. Never infer acquisition FPS from animation playback speed or fabricate camera visibility/presence.
2. Align existing segmented repetition boundaries to full recordings, retaining all original observations and interruptions. Do not concatenate cuts and invent missing endpoint dwell. Preserve every incorrect label even when its fault lies outside the current selected-side feature set; document scope errors rather than dropping negatives.
3. Develop on 01–04, select on 05–07, freeze a candidate and commit its evaluation specification before final-test access. Record all final-test reads. Skeletal results can earn only the skeletal independent-label points, never camera points.
4. Measure a real 10-minute live inference soak: p95 capture-to-result latency, FPS, queue bound, model identity, crashes and PSS windows under the frozen criteria. Keep device-specific measurements distinct from algorithmic correctness. Consider a second Android API runtime after the current suite is stable.
5. Audit reference-only extraction/calibration alternatives for the remaining 20 unavailable reference/side configurations. Preserve the 32-case denominator, original MP33/PTS, confidence gates and no-source-outcome selection. Do not relax gates merely to raise coverage.

Current datasets contain independently provided labels but **no completed independent evaluation**. Geometry-adapter validation, continuous segmentation alignment and held-out precision/recall/coverage are still outstanding. The honest readiness score remains 42.5, and actual live human-form precision is unknown. The user requested autonomous work without new human labelling; use the licensed existing labels rather than asking them to annotate recordings.

## Useful commands and paths

Run `scripts/score_readiness.py` for the captured baseline and use explicit current-engine receipts for later scores; it must never silently reinterpret old evidence as tests of a new commit. `scripts/fetch_uiprmd.py` is resumable and verifies whole archives before promotion. `scripts/inspect_uiprmd_development.py` refuses validation/test trajectory reads. The split/provenance rules are in `models/uiprmd-evaluation-protocol.json`.

The current reference-only registry is `test-artifacts/exercise-knowledge/readiness-identity-summary`. JVM session replay accepts `-PqualifiedReferenceReplayRegistry=readiness-identity-summary`; counterfactual replay now uses the matching prefix and registry. Preserve older `multipass-*` reports and baseline receipts. Stop the owned headless emulator after verification and keep the branch clean between checkpoints. Do not push, deploy, create another chat or delegate.
