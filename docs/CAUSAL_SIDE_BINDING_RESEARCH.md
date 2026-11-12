# Causal active-side research

This experiment tests a deliberately declared **interchangeable unilateral shoulder-abduction variant**, separately from the frozen UI-PRMD RIGHT-side profile. It cannot replace the original profile's scores, earn readiness points or establish camera accuracy. The APK, production defaults and raw MediaPipe weights are unchanged. The rejected quality classifier is not used.

The research implementation is in test sources only. It processes original bilateral driver angles with the same confidence, model, preprocessing, timestamp and subject identity requirements. Both sides must first occupy the uploaded reference's ready endpoint for the existing 100 ms confirmation. Exactly one side must then pass the existing departure threshold while the other remains at ready. That separation must persist for the existing 60 ms milestone confirmation, before either side reaches the far endpoint. The first selected mapping is locked for the session. No per-rep arm switching, later best-verdict selection, peak search, label input or companion-form score selects the side.

Both fixed mappings consume the observed prefix before locking so the selected engine retains genuine ready and path evidence. Their outputs are suppressed until locking. Both moving, missing opposite-side driver after ready, unstable departure, an early far endpoint, provenance failure, context change or evidence gap before binding blocks the session. Explicit reset is required after a blocked session. After locking, the selected original engine still handles every evidence/context interruption and clear fault. Missing evidence or a better-looking opposite arm cannot change the mapping. Single-side scope does not establish bilateral symmetry or detect faults outside the measured features.

The experiment uses development subjects 01–04 and already-opened validation subjects 05–07 only. Existing source/export hashes, publisher labels, 100 Hz times and all 160/120 rep denominators remain fixed. Squat retains its original LEFT profile. Subject 01/02 reference selection remains independent of source outcomes. A fixed-side control is rerun with the same current compiler as the candidate. Final subjects 08–10 remain forbidden. The candidate and protocol, including source hashes, must be committed before executing the validation replay. Validation is now selection data; it is not a new held-out test.

The original RIGHT-side variant and all rejected quality-head reports remain intact. Even if active-side handling recovers movements, clinical exercise labels may identify faults outside the two measured angles. More counts alone do not justify promotion. Precision, correct-rep recall and coverage must all be reported, alongside any previously accepted good reps lost. A successful research result would still require an explicitly authorized product variant, independent validation and native parity before app integration.

## Frozen trial results

Candidate and protocol were committed as `cb6396c1d6886ea283a05650d2bdff25218e54e1` before the four development/validation fixed/causal replays. Source hashes are checked before replay. Only test-source code changed; the production comparator and app remain at their prior implementation. The fixed-side development rows match the prior compiler `/5` replay. The validation control matches the frozen compiler `/4` rows apart from deliberately versioned reference keys. No earlier report was overwritten.

| Cohort / mapping | Correct counted | Incorrect counted | Counted-event precision | Good-rep recall | Assessable coverage |
|---|---:|---:|---:|---:|---:|
| Development, fixed profile | 31/80 | 27/80 | 53.45% | 38.75% | 50.63% |
| Development, causal research variant | 31/80 | 27/80 | 53.45% | 38.75% | 50.63% |
| Validation, fixed profile | 13/60 | 7/60 | 65.00% | 21.67% | 30.83% |
| Validation, causal research variant | 14/60 | 7/60 | 66.67% | 23.33% | 33.33% |

The selection consistently locks RIGHT for subjects 01–06 and LEFT for subject 07, on both publisher labels. Subject 07 locks at 3,010 ms on the correct recording and 880 ms on the incorrect recording. This is causal driver evidence, not a choice using the later correctness result. No previously counted good rep is lost; one good count is added; no additional incorrect count is added. These are small research-cohort observations, not a population precision estimate or APK improvement. The changed variant does not replace the original RIGHT-side score.

Binding resolves a scope issue but leaves form comparison weak. Subject 07 now emits nine attempts on the correct recording and ten on the incorrect recording; eight correct attempts remain unassessable for `AMBIGUOUS_FORM`. Subject 05's ten correct shoulder attempts are also unassessable for that reason. Subject 06 still has six incorrect shoulder reps accepted. The original two selected-side angles cannot be assumed to measure every publisher-labelled fault. The candidate fails the unchanged precision, recall and coverage requirements and is **not promoted or sent to final testing**. Readiness remains **42.5/100**; human live-camera precision remains unknown.

Two new original-marker contact sheets were inspected at the lock time, first emitted attempt's maximum active-shoulder angle and completion, in XZ and YZ. Every displayed sample has its original acquisition timestamp and all 39 observed markers, with no repair. They confirm subject 07's moving left arm and nearly stationary right arm; they do not supply new form annotations. The existing label and all missing/unassessable outcomes remain in the denominators.

Verification passes 212 core-motion JVM cases including seven side-binding tests, 14 adapter JVM and 34 app JVM cases (260 total, no failures/skips), 53 Python checks, all 2,688 frozen constructed controls and 124 real-timestamp controlled corruptions. The latter remain mechanism tests, not human-rep precision. No new Android runtime suite or camera performance claim is made for this test-source-only experiment. Existing native evidence remains scoped to the unchanged app.

Reproduce with the recorded byte-identical candidate source, verified existing exports and research Python environment:

```powershell
./gradlew.bat :core-motion:test --no-parallel
# Run once per scope (development, validation) and mode (fixed, causal):
./gradlew.bat :core-motion:uiprmdSideBindingReplay --no-parallel '-PuiprmdSideBindingScope=development' '-PuiprmdSideBindingMode=fixed'
python scripts/evaluate_uiprmd_side_binding.py
python scripts/audit_uiprmd_side_binding.py
python -m unittest discover -s scripts/tests -v
```

`models/uiprmd-side-binding.provenance.json` binds the paired reports, raw-source read receipts, timestamped plots, functional checks and immutable checkpoint copies. The next investigation should quantify phase-conditioned elbow/reference differences and missing fault features on development and already-opened validation, preserving absolute evidence and the original target. Do not erase neutral-pose differences or widen tolerances until there is independent evidence that doing so preserves clearly incorrect-rep rejection. Final subjects 08–10 stay sealed.
