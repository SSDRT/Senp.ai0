# Critical-joint viewport source screen results

28 October 2026. Research freeze `b2fba71`, protocol `models/in-image-source-protocol-v3.json`. This is a comparison-only test-source candidate; production remains engine `6d1386b`, compiler `/8`, with unchanged model weights and APK.

The reference screen preserves all 12/32 existing usable sides, with the same backend and fit choices and no added sides. On the fixed 48 camera clips / 96 source sides, the frozen reference-only selection permits 38 source-side runs; the other 58 stay explicitly unavailable. Each eligible case was replayed under original and comparison-only in-image admission, giving 76 variant traces. The unequal 38-case selected cohort is a consequence of the fixed cross-person pairing, not a new denominator or an independent test set.

All 38 original runs reproduced their preserved production reference key, attempts, faults, five counts and timestamped stage/progress/disqualification/unavailability trace values exactly. Candidate/source pipeline identity was explicitly paired with its research reference and checked on every frame. Initial count bookkeeping and pipeline-identity harness defects were corrected in separately committed protocols; both invalid runs remain preserved. Their zero masked counts are not candidate measurements. No source outcome chose a backend, fit, input or threshold.

| Engine outputs, not expert rep correctness | Original | Research viewport mask |
|---|---:|---:|
| Qualified events | 31 | 31 |
| Engine CORRECT / ACCEPTABLE | 0 / 31 | 0 / 31 |
| Rejected attempts | 61 | 61 |
| Unassessable attempts | 185 | 183 |
| Qualified intervals with identical start/end/verdict | — | 31 / 31 |
| Qualified intervals withdrawn, added or shifted | — | 0 |

Weak whole-clip metadata divides those qualified outputs into 24 from good-labelled clips and seven from bad-labelled clips in both versions. These labels do not identify individual correct or incorrect repetitions. No human-rep precision, recall, false-positive reduction or training gain can be computed from this screen. Qualified attempt visibility/ambiguity diagnostics can differ even where their interval and verdict stay identical.

Six source-side runs change an attempt/fault/diagnostic record. Subject001 side push-up LEFT loses two short unassessable attempts and one early-reversal rejection in the initially clipped-hand region. Subject005 side push-up LEFT changes one ambiguous unassessable attempt to an engine rejection after the admitted reference curve changes; it is not a newly verified form error. Subject018 side push-up good LEFT splits a rejected interval with an additional unassessable interval. Remaining changes affect nonqualified timing/reasons or visibility/ambiguity diagnostics in subject001 front press RIGHT (good and bad clips) and subject018 bad side push-up LEFT. All six appear in the paired report, including losses. Net unassessable reduction is an engine segmentation consequence, not improved coverage accuracy.

Inspected **18 original timestamped RGB frames and 36 pinned raw Full/Heavy overlay panels**, covering all six changed runs. The subject001 push-up at 1067/3733 ms shows clipped/near-border wrist evidence, with the 6400 ms in-image comparison. Other cases include visible in-frame movement, border-adjacent foot predictions and end-of-clip preparation/exit. Thus not every source diagnostic change is caused by a clipped source wrist: two reference curves change too. Neither the raw overlays nor weak labels supply new anatomical or form ground truth.

Verification passes **250 core JVM and 134 Python tests**; the source audit checks every original event/trace and the pipeline guard. The previous 1,796 committed readiness receipts revalidate at **50/100**. No production/main source, acceptance threshold, raw MP33 evidence, confidence, model weight or frozen holdout changed; no independent labels, final subjects or new human annotation were accessed. Existing native/runtime and controlled-corruption evidence is preserved, not rerun for this test-only experiment. No new APK was built.

The candidate is **compatible with this selected-reference camera screen, pending production verification**. It has no measured accuracy or readiness gain yet. A next promotion proposal should keep admission limited to the qualified-rep path, version compiler/cache identities, preserve the raw envelope and generic action/quality paths, and run all eight camera configurations plus causal, corruption, independent development/previously-opened validation, native parity and APK checks. Explicitly test all-anchors-outside and missing-paired-image cases so a 2D fallback cannot qualify a 3D repetition. Keep final UI-PRMD subjects 08–10 sealed until a separate, defensible independent-label candidate and protocol are frozen. That independent precision/recall/coverage requirement remains essential to reaching 70.
