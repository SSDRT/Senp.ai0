# Evidence behind withheld squat repetitions

25 October 2026. This checkpoint adds opt-in, passive comparator diagnostics and audits the existing squat failure. **No acceptance threshold, reference target, confidence gate or count is changed. Readiness remains 42.5/100.** No new APK is promoted. The previously delivered APK remains unchanged, and no new user-provided squat video has been received.

## Concrete implementation

An internal diagnostic engine can emit the actual source feature name, untouched raw angle, original feature confidence, confidence-gated observed angle, effective reference band, allowances, distance, causal branch and timestamp for each active-attempt observation. It records the branch the engine actually uses, including a compatible return observation before reversal confirmation. A missing/low-confidence value remains missing for comparison even when its raw value is retained for inspection. A single sample over a bound is not relabelled as a confirmed fault.

The public `newEngine()` path installs no observer, collects no diagnostic rows and retains its existing outputs. Five new JVM cases cover passive parity, all six policies/sides/mirrors/cadences, low-confidence raw values, mirrored fault features, prefix causality and rejection of final-test audit scope. These are engineering checks, not human precision measurements. No screen, cloud service or automatic video recording is introduced.

The offline skeletal audit reads only the already-opened development 01–04 and validation 05–07 exports, verifies their manifests/file hashes and retains all original labels, missing events and unmatched attempts. It compares diagnostic and ordinary engine outputs on every source observation and at session finish. All outputs match. Final subjects 08–10 remain unopened. The old fixed-side and rejected side-binding/classifier reports remain intact; the audit does not install either research candidate.

## Original camera squat replay

The Full/standard complete-reference replay of `subject_018_squat_good_front.mp4`, LEFT side, still has **one accepted and eleven unassessable attempts**. Its source/reference hashes, counts, intervals, reasons and faults match the preceding checkpoint. The existing whole-video `good` filename is not per-rep ground truth, so this is not an accuracy denominator.

| Measured diagnostic | Result |
|---|---:|
| Active-attempt samples with both compared angles observed above the existing confidence gate | 224/224 |
| Hip samples outside their actual acceptable reference band | 47/224 (20.98%) |
| Knee samples outside their acceptable angle band | 0/224 |
| Hip disagreement samples using outbound / return branch | 43 / 4 |
| Attempts carrying `AMBIGUOUS_FORM` | 11 |
| Attempts carrying `INSUFFICIENT_RANGE` | 8 |
| Attempts interrupted by context changes / end of recording | 2 / 1 |

Reason counts overlap. These are active-attempt samples only: they do not establish uninterrupted evidence over the whole video or remove the two context interruptions. Sample fractions are also not the engine's duration-weighted ambiguity fractions. Knee progress/depth can fail the unchanged 85% completion threshold even when the knee stays inside the broader acceptable angle envelope. Hip disagreement can independently prevent a compatible endpoint/path. Three isolated hip samples exceed the clear-deviation bound; they do not satisfy the original persistent-fault requirement, so no rejected count is invented.

This narrows the diagnosis beyond “the video must be unclear.” In this existing example, hip/reference disagreement and completion/context checks are the measured blockers. It does not establish the cause of the user's unshared video or prove any performer used incorrect form. Existing source frames and Full/Heavy raw-landmark overlays at original 6,000/10,000 ms were inspected again; no source image, PTS or MP33 observation was replaced.

## Independent skeletal diagnosis, kept separate

The current fixed-side results remain exactly **31 correct / 27 incorrect counts** on 160 development labels and **13 / 7** on 120 already-opened validation labels. No accuracy improvement is claimed for adding diagnostics.

On the 30 publisher-correct validation squats, 22 attempts are unassessable: 19 carry `INSUFFICIENT_RANGE` and 12 carry `AMBIGUOUS_FORM`, with overlap. Hip observations cross the acceptable curve in 15 of the 30 reps; small crossings do not necessarily withhold a rep. Knee observations cross their acceptable angle envelope in none of these reps, but completion against the deeper uploaded reference still fails for many. Subject 05's ten correct-labelled reps reach approximately 0.74–0.80 supported reference progress. Subject 07's last nine reach approximately 0.70–0.76. Their original labels remain correct; a clinical exercise label does not guarantee close imitation of the chosen reference's depth.

Development behaves differently: none of its correct squat attempts has hip samples outside the acceptable curve, while 16 correct reps cross the knee envelope. This change across people is evidence against simply learning a development-only feature veto or widening a single tolerance. On validation, the retained incorrect shoulder counts also remain; diagnosing a missing arm or a range mismatch does not solve all form faults.

A new contact sheet was generated and inspected from original Vicon39 source markers for reference subject 01 and validation subjects 05 and 07, publisher episode 2. It includes nine timestamped XZ/YZ views and the actual comparator bands. All displayed marker samples retain their original observations without repair. The source-read guards reject final subjects before archive access. Marker observability is binary acquisition evidence, not MediaPipe camera confidence or anatomical ground truth.

## Verification and next work

All eight original camera-input combinations are replayed into a new `evidence-audit-*` registry. The capped/complete references, Full/Heavy models and standard/bounded fits preserve all prior selections, counts, event intervals, verdicts, faults and reference keys. Complete coverage remains 12/32 references, covering the same four camera policies. No more readiness points are earned.

Verification passes **265 scoped JVM tests, 57 Python tests and 25 Android functional cases**, with no failures or skips. The Android run uses the existing Windows API 36 x86_64 emulator, including Full/Heavy model identity, raw-landmark parity and camera pause/resume checks. The opt-in ten-minute soak is excluded; no new sustained-performance result is asserted. All **2,688 frozen constructed controls and 124 controlled corruptions** pass. `models/reference-evidence-diagnostics.provenance.json` and the matching readiness evidence bind these results to immutable checkpoint copies and the committed implementation.

Use the recorded research environment and byte-identical source:

```powershell
./gradlew.bat :core-motion:uiprmdEvidenceAudit --no-parallel '-PuiprmdEvidenceScope=development'
./gradlew.bat :core-motion:uiprmdEvidenceAudit --no-parallel '-PuiprmdEvidenceScope=validation'
python scripts/analyze_uiprmd_evidence.py
python scripts/plot_uiprmd_evidence_audit.py
# The complete Full/standard camera replay supports an optional evidence export:
./gradlew.bat :core-motion:qualifiedRepVideoReplay --no-parallel '-PqualifiedReplayReferences=test-artifacts/exercise-knowledge/complete-reference-full-cache' '-PqualifiedReplayOutput=test-artifacts/exercise-knowledge/evidence-audit-complete-full-standard' '-PqualifiedReplayEvidence=true'
python scripts/audit_camera_squat_evidence.py
```

The next tuning experiment should distinguish reference-relative range/hip timing differences from faults actually covered by the existing independent labels, using phase-conditioned descriptors and explicit out-of-distribution checks. Any proposed person-offset normalization must preserve absolute endpoints, raw evidence and rejection of clearly wrong starting/turnaround poses; none is enabled here. Freeze a concrete candidate before further selection, keep the original validation denominators and do not open final subjects until all unchanged validation gates pass. A second emulator or extra synthetic tests cannot substitute for that independent-label requirement.
