# Synchronization Kernel v2 validation lane

This module is the independent validation/harness lane for the frozen Sync-v2 contracts. It intentionally contains **no production synchronization algorithm**. Its job is to make adversarial inputs, inspect candidate outputs, drive the immutable real-video corpus, render human-review evidence, and measure post-pose overhead once a concrete implementation is injected.

## Boundary

`sync-v2-validation` depends only on `:core-contracts` plus Kotlin serialization. The frozen contracts and production spatial/temporal/alignment modules are read-only from this lane.

Three local seams keep future integration narrow:

- `SynchronizationHarnessAdapter` wraps a concrete end-to-end Sync-v2 implementation.
- `SpatialHarnessAdapter` exposes canonicalized observations plus frozen spatial diagnostics for form-preservation inspection without changing the public contract.
- `TemporalHarnessAdapter` exposes the frozen `TemporalStructure` for independent segmentation/unit validation.

The CLI/media layer uses a separate executable adapter protocol (`senp-sync-v2-validation-adapter/1`) so real-video and performance validation can be wired after spatial/temporal integration without importing their production modules here.

## Synthetic suite

Generate deterministic timestamp-first machine-readable fixtures:

```bash
./gradlew :sync-v2-validation:run --args="generate test-artifacts/sync-v2/synthetic"
```

Outputs:

- `synthetic-suite.json` — all 36 frozen scenarios with canonical observations, truth tracks, motion-unit truth, spatial truth, and allowed synchronization outcomes.
- `scenarios/<id>.json` — one reproducible fixture per scenario.
- `coverage-matrix.json` — executable fixture/invariant coverage and staged production-integration state.

Default seed: `20260808`. A different seed may be passed as the final argument. Timestamps, not frame indices or nominal FPS, are the source of temporal truth. Each observation also carries concrete `human_pose` 3D landmarks (`x/y/z`) for spatial-lane injection; temporal oracle values live in a separate `synthetic_motion_truth` channel so a spatial adapter is not handed its expected answer as pose geometry. Viewpoint cases rotate those landmarks, mirror cases reflect them, camera-discontinuity cases change the view after an explicit unreliable gap, and body-proportion cases combine similarity-scale nuisance with non-rigid geometry changes.

The result validator is deliberately invariant-oriented. It checks identity, explicit unmatched material, reference-unit reuse, reliable interior direction consistency, open boundaries, rest/isometric/acyclic semantics, discontinuities, ambiguity, coverage, mirror/view diagnostics, required-channel refusals, and true-form preservation through the local spatial inspection seam. It does not prescribe a DTW path, phase-count golden, or coaching outcome.

To validate a frozen `SynchronizationResult` JSON after integration:

```bash
./gradlew :sync-v2-validation:run --args="validate same_video_self result.json report.json"
```

## Frozen 36-scenario matrix

Every row has executable deterministic fixture generation and executable invariant validation. Concrete production acceptance remains explicitly staged until the spatial/temporal branches are integrated.

| Scenario | Primary lane(s) | Fixture | Invariant evaluator | Production adapter |
|---|---|---:|---:|---:|
| same_video_self | correspondence | executable | executable | staged |
| different_fps | temporal/correspondence | executable | executable | staged |
| different_resolution | spatial/correspondence | executable | executable | staged |
| different_codec | correspondence | executable | executable | staged |
| rotation_metadata | spatial/correspondence | executable | executable | staged |
| yaw_elevation_viewpoint | spatial | executable | executable | staged |
| mirror | spatial | executable | executable | staged |
| side_selection_stability | spatial | executable | executable | staged |
| camera_movement_discontinuity | spatial/temporal | executable | executable | staged |
| start_mid_motion | temporal | executable | executable | staged |
| end_mid_motion | temporal | executable | executable | staged |
| one_reference_ten_source | temporal/correspondence | executable | executable | staged |
| ten_reference_one_source | temporal/correspondence | executable | executable | staged |
| two_reference_seven_source | temporal/correspondence | executable | executable | staged |
| multiple_sets_rests | temporal | executable | executable | staged |
| extra_source_action | temporal/correspondence | executable | executable | staged |
| missing_source_action | temporal/correspondence | executable | executable | staged |
| repeated_identical_phase | temporal/correspondence | executable | executable | staged |
| variable_speed | temporal/correspondence | executable | executable | staged |
| pause_hold | temporal | executable | executable | staged |
| very_slow | temporal | executable | executable | staged |
| very_fast | temporal | executable | executable | staged |
| static_isometric | temporal | executable | executable | staged |
| no_common_motion | truthfulness | executable | executable | staged |
| poor_pose_coverage | truthfulness/spatial | executable | executable | staged |
| short_occlusion | temporal/truthfulness | executable | executable | staged |
| long_occlusion | temporal/spatial/truthfulness | executable | executable | staged |
| person_leaves_reenters | temporal/spatial | executable | executable | staged |
| multiple_people_subject_ambiguity | spatial/truthfulness | executable | executable | staged |
| different_body_proportions | spatial | executable | executable | staged |
| true_form_difference | spatial | executable | executable | staged |
| reversed_video | temporal/truthfulness | executable | executable | staged |
| edited_spliced_video | temporal/spatial | executable | executable | staged |
| slow_motion_edit | temporal | executable | executable | staged |
| non_cyclic_activity | temporal/correspondence | executable | executable | staged |
| object_required_pose_only | truthfulness/channels | executable | executable | staged |

## Real-video corpus runner

The immutable corpus is resolved from `fixtures/real-video-cases.json` and its external manifest. The runner verifies manifest membership and SHA-256 by default; it never modifies media.

Regression targets:

- `biceps-wrong-right` — old alignment had visually verified opposite-phase correspondence errors.
- `legraise-wrong-right` — full-pose-coverage generic temporal regression, including raised-vs-flat leg mismatches.
- `pushup-wrong-right` — low-observation-coverage truthfulness/refusal target. Its corpus descriptor requires at least 0.65 analyzable fraction before a `SYNCHRONIZED` result is accepted; lower coverage must remain partial/refused.
- `biceps-right-right-control` and `legraise-right-right-control` — identity/same-video controls.

Prepare a case without pretending a production implementation exists:

```bash
python3 sync-v2-validation/tools/sync_v2_validation.py prepare-real \
  --case biceps-wrong-right \
  --output-dir test-artifacts/sync-v2/real/biceps-wrong-right
```

This writes `adapter-request.json` and reports `integration_status: STAGED`. To execute a concrete implementation, pass an executable wrapper:

```bash
python3 sync-v2-validation/tools/sync_v2_validation.py prepare-real \
  --case biceps-wrong-right \
  --output-dir test-artifacts/sync-v2/real/biceps-wrong-right \
  --adapter-executable /path/to/sync-v2-validation-adapter
```

The executable receives the request JSON path as its only argument and must write the requested normalized result. Normalized results contain synchronization status/confidence, source/reference analyzable fractions, mapped or unmatched timestamps, motion-unit IDs, direction/phase/state labels when available, unmatched units, refusal reason when applicable, and spatial diagnostics. The validator rejects scoring/coaching/problem-count fields, reliable opposite-direction mappings, non-monotonic per-unit timestamp mappings, and confident forced matches through explicit rest/unreliable holes.

## Human-verification artifacts

Render a deterministic mobile-first HTML report and PNG contact sheet from normalized mapping rows:

```bash
python3 sync-v2-validation/tools/sync_v2_validation.py artifact \
  --case legraise-wrong-right \
  --plan sync-v2-validation/fixtures/legraise-renderer-adversarial-review.json \
  --output-dir test-artifacts/sync-v2/artifacts/legraise-renderer-smoke
```

The checked-in review plan is **not a synchronization result**. It deliberately contains bad raised-vs-flat, opposite-state, opposite-direction, and unmatched rows so reviewers can verify the renderer makes these failure modes obvious instead of demonstrating fake algorithm success.

The artifact includes:

- a deterministic source-to-reference `timeline.svg`, including explicit `UNMATCHED` marks;
- raw source/reference frames selected by decoded presentation timestamp;
- source timestamp and reference timestamp or `UNMATCHED`;
- confidence, source/reference motion-unit IDs, direction, phase, state and reliability labels;
- mirror/side/view/global-scale diagnostics supplied by the adapter;
- a mobile-first HTML page plus a deterministic vertically stacked PNG contact sheet.

Non-uniform scaling or shear is outside the frozen spatial transform family and is rejected by the normalized-result validator; the page keeps spatial diagnostics visible near the top so global scale and form-preservation concerns can be reviewed beside the correspondence timeline and raw frames.

## Performance harness

Generate the staged scaling plan and summarize existing stage timing evidence without mislabeling legacy alignment as Sync-v2:

```bash
python3 sync-v2-validation/tools/sync_v2_validation.py performance \
  --output test-artifacts/sync-v2/performance/report.json \
  --stage-report /path/to/legacy/stage_report.json
```

The benchmark plan covers:

- 150/300/600/1200 post-pose sequence samples;
- coarse 10 FPS, typical 15 FPS, and denser 30 FPS analysis cadences;
- 1:10 reference-unit reuse and repeated 2:7 units;
- long idle/rest clips;
- peak RSS reporting.

`input_nominal_fps` is never treated as `analysis_fps`. Existing stage reports are labeled `legacy_wave5_evidence_only_not_sync_v2` and separate pose/preprocessing time from post-pose motion/phase/alignment time.

With `--adapter-executable`, the wrapper receives `post_pose_benchmark` requests and returns `post_pose_sync_ms`, `peak_rss_bytes`, and `total_pipeline_ms`. The ordinary target is post-pose Sync-v2 overhead within the 15–20% band of total pipeline time. The default is report-only. `--enforce-budget` converts >20% into a failure only when a concrete adapter is actually running.

## Validation commands

```bash
python3 -m unittest discover -s sync-v2-validation/tools/tests -v
./gradlew :sync-v2-validation:test
./gradlew :core-contracts:test
./gradlew checkCoreBoundaries
./gradlew check
```

Generated media, reports, and benchmark outputs live under ignored `test-artifacts/` / `benchmark-results/` paths. Keep the external corpus read-only and keep publish/PR steps outside this lane.

## Integration limitations

The spatial and temporal production branches are intentionally absent from this branch. Therefore all 36 fixture/oracle paths and invariant evaluators are executable now, while production Sync-v2 acceptance and new post-pose timing measurements remain staged until an adapter wraps the integrated implementation. The renderer smoke plan proves artifact observability only; it must never be cited as synchronization accuracy evidence.
