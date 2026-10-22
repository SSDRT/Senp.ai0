# Reference-based rep acceptance: implementation and evidence

Date: 22 October 2026. Branch: `codex/live-pose-accuracy`. Continues from `20d334a` / engine `e69cc57`. Scope is the native reference-video comparison engine and its existing live integration.

The new experimental mode counts completed repetitions that pass the selected visible-side reference policy. It keeps the original movement recognizer and raw count separately. It can invalidate an ongoing attempt after a confirmed visible deviation, refuse partial cycles and withhold judgment when evidence is insufficient. **It has not achieved the proposed 98% precision / 90% good-rep acceptance targets on independent human labels and remains opt-in, with `releaseQualified=false`.**

**Implemented behavior**

- `QualifiedRepCompiler` prepares reference-specific milestone and angle curves. The raw-reference path selects directly observed complete start–turnaround–return cycles; setup, rest, missing endpoints and incomplete candidates cannot substitute for full cycles. Default minimum is three usable cycles.
- Phase curves are fitted separately for outbound and returning motion. Within-bin movement is detrended before calculating robust variation/noise bands. The implementation fits scalar measurement targets; it does not modify any original landmark, bone length or confidence.
- Noisy or poorly reconstructing references refuse counting. Required reference features need at least five inlier samples, adequate confidence/visibility and bounded variation. Selected cycles undergo causal self-replay; at least 80% must pass the fitted policy. This is a self-consistency gate, not independent form accuracy.
- `QualifiedRepEngine` uses observed milestones and independent per-attempt quality history, rather than trusting the legacy repetition counter or learned quality-classifier scores. A form error need not be accepted by the detailed legacy phase matcher to be noticed by this engine.
- Completed outcomes are `CORRECT`, `ACCEPTABLE`, `REJECTED` and `UNASSESSABLE`. The accepted count is correct + acceptable. Stable attempt IDs, endpoint hysteresis and required return confirmation prevent duplicates. Static holds and unsupported exercises do not produce invented rep counts.
- A severe confirmed fault is latched for the current attempt and immediately exposed as `activeDisqualified`; subsequent recovery does not restore that attempt. Small deviations can remain acceptable. Grey-zone deviations, insufficient coverage and missing mandatory endpoints remain unassessable.
- A shallow movement is disqualified only after an observed reversal. The engine does not predict a failed rep while a person is still legitimately descending. Good reps count at completion, never at onset.
- Critical endpoint evidence must be directly observed on at least two samples. Raw evidence, local confidence, compatible model/preprocessing identity and continuous timestamps are required. Repaired coordinates cannot certify a turnaround. Gaps, context changes, model mismatches and interruptions prevent qualification.
- The original generic reference workflow and legacy count remain available. In an explicitly enabled new session, the existing counter/status bindings show reference-accepted reps, the observed side, disqualification or inability to assess. Reference calibration runs off the UI thread. No new exercise picker, automatic workout recognition or frontend redesign is included.

**Initial policy scope**

| Exercise ID | Phase driver | Other required selected-side reference angle |
| --- | --- | --- |
| `bodyweight_squat` | Knee | Hip |
| `biceps_curl` | Elbow | Shoulder |
| `push_up` | Elbow | Shoulder–hip–ankle body line |
| `overhead_press` | Shoulder | Elbow |
| `glute_bridge` | Hip | Knee |
| `lateral_raise` | Shoulder | Elbow |

These are six experimental configurations, not six independently qualified exercise products. The visible side must be explicit. Alternating curls count the selected side, not a paired left/right total. All original MP33 landmarks and image/world coordinates remain intact; the first acceptance policies use selected angles. Additional unobserved faults, other limbs, load, contact, muscle activation and fine spine shape are not certified.

`REFERENCE_MATCH` follows the uploaded demonstration. `REFERENCE_WITH_TECHNIQUE_GUARDS` additionally uses resolvable source-guided angle targets for the selected side, such as a push-up body line or curl upper-arm position. Unknown variants, unresolved guide phases and conflicting targets refuse that guarded configuration. Guidance does not overwrite the original reference profile. Existing feedback follows the selected policy mode; choosing reference matching does not silently apply guidance targets to its count.

**Current engineering defaults**

| Setting | Value | Interpretation |
| --- | ---: | --- |
| Local confidence | 0.65 | Minimum for each required observed measurement; not a correctness probability |
| Minimum reference cycles | 3 | Directly observed usable cycles in raw-reference calibration |
| Minimum reference excursion | 20° | Suppresses nearly stationary driver calibration |
| Nominal / acceptable / reject allowance | 5° / 12° / 30° | Outside learned phase bands, widened for measured variation and selected source guidance |
| Maximum reference MAD / band width | 12° / 60° | Noisy critical ranges refuse calibration |
| Reference self-replay acceptance | 80% | Required over selected calibration cycles; not human accuracy |
| Required / nominal excursion | 85% / 95% | Relative to learned driver excursion; engineering candidates, not universal anatomical standards |
| Clearly incomplete excursion | Below 65% at confirmed reversal | Produces an early partial-range disqualifier |
| Fault confirmation | 160 ms + at least two observations | Separate from existing 240 ms coaching persistence |
| Ready / return confirmation | 100 ms | Requires a visible start and return |
| Far-endpoint confirmation | 60 ms + at least two observations | No missing-frame interpolation certificate |
| Reversal confirmation | 80 ms | Plus at least 12% excursion back from the supported peak |
| Maximum observation gap | 250 ms | Terminates the active attempt conservatively |
| Required visible fraction | 90% | Time-weighted critical-feature coverage over the attempt |
| Maximum grey-zone exposure | 10% | More exposure makes the attempt unassessable |
| Attempt timeout | 30 seconds | Ends without silently creating a completed acceptable cycle |

All thresholds are configurable and unqualified pending reviewed reference-relative rep labels. A selected cycle at 90% excursion can be acceptable without being labelled a nominal full-range match. A single-frame progress spike cannot invent a supported peak. No continuous adaptation learns repeated mistakes as a new target.

**Uploaded-reference/live API**

The existing preparation flow now retains the reference video/model fingerprint and raw extraction. The acceptance calibration is separate from generic action compilation, because their goals differ. The generic compiler remains unchanged. A lower-level overload also accepts a previously calibrated `ActionProfile`; the raw-reference overload is the path that performs cycle selection and causal self-replay.

Core example:

```kotlin
val fingerprint = RepEvidenceFingerprint(referenceVideoSha256, poseModelSha256)
val request = QualifiedRepConfig(
    side = RepObservedSide.LEFT,
    mode = RepPolicyMode.REFERENCE_MATCH,
)
when (val compiled = QualifiedRepCompiler.compile(referenceSpatialFrames, "biceps_curl", fingerprint, request)) {
    is QualifiedRepCompilation.Ready -> {
        val counter = compiled.reference.newEngine()
        // For each strictly increasing source timestamp:
        val decision = counter.evaluate(rawSpatialFrame, actualPoseModelSha256,
            RepEvidenceFingerprint.RAW_WORLD_PIPELINE, captureContext)
        // decision.counts.qualified; decision.activeDisqualified; decision.finalizedAttempts
    }
    is QualifiedRepCompilation.Unavailable -> {
        // Surface the typed reason; do not guess a compatible reference.
    }
}
```

Android `ReferenceActionSessionEngine.compileQualifiedReference(prepared, exerciseId, config)` uses retained raw reference poses. `newLiveProcessor` accepts the resulting `qualifiedRepReference` and matching `repEvidenceFingerprint`. Each update needs the estimator's raw pose and actual model SHA in addition to the existing stabilized pose. `finishQualifiedReps` terminates a partial session without inventing a successful rep. Reset clears the session counters/history.

An internal launch can opt in using `enable_qualified_reps`, `exercise_technique_id`, `qualified_rep_side` (`LEFT`/`RIGHT`) and `qualified_rep_policy` (the policy enum name). A current prepared reference is required. The Activity is not exported; these are internal integration options, not an external ADB shortcut. No automatic 54-exercise classifier or selection UI was added. The regular launch remains generic until the calling integration selects the experimental policy.

Fingerprints bind reference video SHA, model SHA, a declared raw-world pipeline ID and declared view. Runtime checks enforce the model/pipeline identity and reset on capture-dimension context changes. They do not independently prove the camera viewpoint, actual person identity, every preprocessing option or physical-device accuracy. A single-pose estimator still cannot establish robust multi-person identity.

**Verification**

- 369 JVM variant test executions / 320 distinct cases passed, including 18 new core acceptance/calibration cases and four new live-session cases run in debug/release.
- 14 distinct native instrumentation cases passed: eleven existing pose/model cases, two existing app cases and a new real-source-landmark parity case.
- 34 Python QA cases passed, including six scorer tests. Incomplete review, whole-clip/template labels, duplicate accepted events, mismatched provenance, unavailable predictions and previously seen participants renamed as fresh test data are handled explicitly.
- 1,152 generated causal scenarios passed across six exercises, 10/15/30/60 FPS, timestamp jitter, noise and 80/160/240 ms fault confirmation candidates. At 160 ms, 96 clean/near cases accepted, 96 severe/partial cases rejected, 96 missing/grey cases withheld, 48 transient cases avoided hard rejection and 48 stationary cases produced no count. These are mechanical tests using generated known motions, not camera or human accuracy.
- Maximum default fault-confirmation delay in those generated scenarios was 220 ms from the first qualifying measurement. This excludes camera/inference/display time and is not proof of the proposed 300 ms end-to-end latency target. All three tested dwell choices passed their mechanical scenarios; the sweep does not establish a human-data optimum.
- The existing 648 exercise-policy mechanism cases and 1,200 persistence-sweep scenarios were rerun. Module checks, core boundaries, debug build and both native suites passed. The unchanged root frontend lint issue (`UnusedBoxWithConstraintsScope`, `VideoEditorScreen.kt:608`) remains outside that scoped pass.
- APK static ABI/ELF/ZIP alignment checks and `zipalign -c -P 16 -v 4` passed. API 36 x86_64 / 4 KB-page emulator only; physical ARM phones and actual 16 KB runtime behavior remain unverified.

**Real-video development replay**

Re-extracted 48 existing licensed Mendeley V3 videos at original source-PTS intervals of at least 66 ms, preserving all 33 image/world landmarks and visibility/presence. Subjects 001, 005 and 018, four exercise classes, good/bad clip labels and front/side views yielded 16,633 original observations, up to the first 24 seconds per video. This replaces approximately 2 FPS with approximately 15 FPS for this explicit subset; it does not synthesize frames. Original source-video hashes and pinned Full-model identity are retained.

The old generic compiler did not yield usable cyclic acceptance profiles on the initial 96 side-specific trials. Direct observed-cycle compilation initially enabled more references; noise and self-reconstruction checks then withheld unstable references. Final development results:

| Result over 48 videos × two selected sides | Runs |
| --- | ---: |
| Calibrated and replayed | 12 |
| Insufficient usable reference cycles | 42 |
| Missing required reference feature samples | 24 |
| Failed causal reference self-reconstruction | 14 |
| Weak/noisy reference | 2 |
| Insufficient robust driver excursion | 2 |

The 12 replayed runs produced three reference-acceptable outcomes, 39 rejected outcomes and 84 unassessable outcomes. **These are engine outputs, not precision/recall or expert validation.** The low coverage and cross-person discrepancies do not meet the intended good-rep acceptance goal. Thresholds were not relaxed merely to make more outputs appear acceptable.

The native numerical-parity fixture uses original landmarks from a real overhead-press reference/source pair. Android reproduces the JVM result: one acceptable, zero rejected and nine unassessable attempts, with three usable reference cycles. Expected outputs were generated by the implementation, not supplied by a human assessor; this checks deployment consistency only. The 1.69 MB compressed fixture is test-only and is not included in the user's main APK. Attribution and source hashes are in `models/qualified_rep_fixture.provenance.json` and the existing CC BY notice/license resources.

Sixteen actual source-PTS overlay frames and four causal trace plots were inspected. They expose setup movements, cropped arms, weak/blurred body regions, different observed movement amplitudes and reference-relative partial-range decisions. A clip labelled `push_up` visibly uses raised feet; a class label alone does not establish a matching exercise variant. The good/bad clip labels cannot establish the correctness of individual reference-relative decisions. No new expert timing annotations or fresh held-out participants were obtained.

**Annotation/scoring pipeline prepared**

`scripts/export_rep_annotation_tasks.py` exports 96 empty review tasks, including unsupported runs. Each task identifies source/reference hashes, selected side/view and the evaluated source interval. Reviewers must label every attempt in that interval, including attempts absent from engine proposals. Templates remain `complete_review=false`; source clip labels are not copied into rep labels. All current subjects are marked previously seen/development.

`scripts/score_qualified_reps.py` refuses templates and incomplete review. Reviewed input requires two distinct reviewers and expert adjudication. Ordered one-to-one interval matching keeps duplicate accepted events as false positives and includes unsupported/missing predictions in recall/coverage denominators. Precision, good/bad acceptance/rejection, unassessable acceptance and confusion counts are computed only for the reviewed scope. The scorer never automatically marks a release qualified. Independent onset-latency evaluation, clustered uncertainty and device qualification remain additional gates.

Reproduction:

```powershell
# Research Python requires pinned dependencies/runtime setup from the previous delivery.
python scripts/extract_qualified_rep_corpus.py
.\gradlew.bat :core-motion:qualifiedRepBenchmark :core-motion:qualifiedRepVideoReplay
python scripts/export_qualified_rep_fixture.py
python scripts/export_rep_annotation_tasks.py
python scripts/render_qualified_rep_audit.py
# After independently reviewing the full evaluated intervals, not the model proposals:
python scripts/score_qualified_reps.py --predictions test-artifacts/exercise-knowledge/qualified-video-replay/summary.json --annotations reviewed-reps.json --output evaluated-reps.json
```

Run extraction with the isolated research venv (MediaPipe 0.10.32 / FFmpeg / Pillow); the existing optional-drawing-export patch remains research-only. Generated videos, caches, traces and unreviewed tasks remain outside Git. The native fixture exporter is deterministic; regenerated fixture changes should be reviewed together with raw-source provenance and the replay trace, never interpreted as independently improving accuracy.

**Status against the strategy**

The per-attempt contract, direct reference-cycle compiler, phase-conditioned bands, visible-side state machine, fault latch, live integration, raw-evidence/model guards and regression/native tests are implemented. High-cadence extraction, diagnostic replay, independent-review tasks and the guarded scorer are prepared. The existing CNN and six unqualified learned quality models were not retrained from these weak labels or enabled as acceptance authorities.

Remaining qualification is material: independently reviewed reference-relative rep/fault labels; fresh held-out people/reference demonstrators; reliable view/variant/identity handling; confidence and threshold calibration against those labels; calibrated near-correct tolerances; sustained physical-phone tests; and per-exercise/view/device release gates. A single angle driver with piecewise phase curves does not resolve every ambiguous motion or monocular-depth failure. Holds, paired unilateral totals and qualification of the other catalogue variants are separate unfinished work.

The strategy's 98% qualified precision, 90% eligible-good acceptance, 2% maximum bad acceptance, 95% explicit bad rejection and 300 ms P95 end-to-end deadlines remain **targets, not achieved results**. The default app flow is preserved because this experimental layer has not passed those gates.
