# Generic reference-action validation

This validation lane is deliberately separate from the generic action engine. It does not infer action states, train/fine-tune a model, or invent action scores. It prepares deterministic pose-level perturbations, locks real-video cases, normalizes future adapter outputs, and computes benchmark metrics after a concrete core adapter exists.

## Inputs and boundaries

- Timestamps are integer milliseconds and remain the temporal source of truth.
- Synthetic perturbations operate on existing MediaPipe-33 pose extraction JSON; they do not re-encode video.
- Real media stays under the external 21-video corpus. Git stores only manifests, hashes, diagnostic summaries, and small fixtures/plans.
- One reference is evaluated relative to itself. Exercise filenames are selection hints, not universal biomechanics truth.
- A future adapter receives `senp-reference-action-validation-adapter/1` requests and writes `reference-action-normalized-result/1`. This keeps the harness independent of the core implementation API.

## Reusable commands

Validate every real-video path and SHA-256:

```bash
python3 sync-v2-validation/tools/reference_action_validation.py manifest-check \
  --output test-artifacts/reference-action-validation/manifest-check.json
```

Generate a small deterministic MediaPipe-33 contract fixture:

```bash
python3 sync-v2-validation/tools/reference_action_validation.py generate-fixture \
  --output test-artifacts/reference-action-validation/synthetic-base.json
```

Materialize pose perturbations. The optional repetition window must be one visually/core-confirmed repetition in reference timestamps; the harness intentionally refuses to guess it:

```bash
python3 sync-v2-validation/tools/reference_action_validation.py materialize \
  --pose-json /path/to/reference_pose_extraction.json \
  --rep-window-ms 1200:2800 \
  --unrelated-pose-json /path/to/unrelated_pose_extraction.json \
  --output-dir test-artifacts/reference-action-validation/poses
```

Stage the full benchmark before the new core exists:

```bash
python3 sync-v2-validation/tools/reference_action_validation.py run \
  --pose-json /path/to/reference_pose_extraction.json \
  --unrelated-pose-json /path/to/unrelated_pose_extraction.json \
  --output-dir test-artifacts/reference-action-validation/run
```

Without `--adapter-executable`, `summary.json` is `STAGED` and contains no action-model scores. With an adapter, the harness invokes the executable once per materialized case with a JSON request path as its only argument, validates normalized results, computes metrics, and writes a machine-readable pass/fail summary.

After the parent/core lane produces one normalized result per real manifest case (`<case-id>.json`), aggregate the real corpus without changing the core API:

```bash
scripts/reference-action-validation evaluate-real \
  --results-dir test-artifacts/reference-action-validation/real-results \
  --output test-artifacts/reference-action-validation/real-summary.json
```

The real evaluator gates exact same-video controls, aggregates the two visually unrelated cricket negatives into a false-positive rate, keeps filename-selected exercise wrong-vs-reference pairs report-only, and records missing gating cases instead of silently treating a partial corpus as a pass.

## Normalized adapter result

Each adapter result must contain:

- `schema: "reference-action-normalized-result/1"`
- `case_id`
- result-level `classification`: `ACTION`, `NO_ACTION`, `SUPPRESSED`, or `UNCERTAIN`
- result-level confidence in `[0,1]`
- compiled profile `state_ids` and `legal_transitions`
- timestamped observations with `state_id`, optional per-observation classification/confidence
- optional non-negative `repetition_count`
- localized deviations with `start_ms`, `end_ms`, `kind`, confidence, and optional landmarks
- optional timestamped cue keys
- explicit boolean capabilities such as `mirror_invariant`, `viewpoint_invariant`, and `live_cues`

The adapter is only a normalization seam. It may call whatever final core API lands; the validation lane does not prescribe core classes or engine behavior.

## Metrics and gates

The current deterministic suite defines these exact benchmark checks:

| Metric | Gate |
|---|---|
| Reference self reconstruction | `classification == ACTION` |
| Reference state coverage | unique observed compiled states / compiled states `>= 0.90` |
| Reference self deviation count | exactly `0` deviations when the reference is compared with itself |
| Legal transition order | legal distinct-state edges / all distinct-state edges `>= 0.999` |
| Absolute start offset | +30 s candidate has identical distinct-state path and confidence delta `<= 0.05` |
| Tempo invariance | at least 80% of configured `0.5x, 0.75x, 1.25x, 1.5x, 2.0x` cases remain `ACTION`, state coverage `>= 0.80`, legal transition fraction `>= 0.95`, and confidence drop versus self reconstruction `<= 0.15` |
| Reverse direction discrimination | reverse traversal must be non-`ACTION`, or lose `>= 0.15` confidence, or have legal-transition fraction `< 0.80`, or emit an explicit deviation |
| Injected geometry deviation | at least one deviation overlaps `>= 25%` of the injected window |
| Missing repetition | recognized repetition count delta is exactly `-1` when a known one-repetition window is supplied |
| Extra repetition | recognized repetition count delta is exactly `+1` when that known repetition window is duplicated |
| Pause/hold | remains `ACTION`, creates no repetition inflation, and confidence drop is `<= 0.15` |
| Occlusion suppression | `>= 0.70` of observations in the blind window are `SUPPRESSED`/`UNCERTAIN`, with zero `>= 0.70`-confidence deviations through that blind window |
| Mirror invariance | only gated if adapter declares support: `ACTION`, state coverage `>= 0.80`, confidence delta `<= 0.10` |
| Viewpoint yaw ±15° | only gated if adapter declares support: `ACTION`, state coverage `>= 0.80`, confidence delta `<= 0.10` |
| Unrelated-motion false-positive rate | false positives / unrelated negatives `<= 0.10`, where a false positive is `ACTION` with confidence `> 0.30`; the single synthetic negative therefore requires `0/1`, and the current two real cricket negatives require `0/2` |
| Live cue stability | staged until live cue output exists; offline replay gate is at most `1.0` cue-key switch per second, followed by a real live-camera jitter check |

The synthetic tempo transform scales timestamp spacing while preserving the exact pose sequence. Mirror swaps left/right landmark semantics and reflects image/world X. Yaw rotates world coordinates around the hip center without fabricating a camera projection. Geometry and occlusion injection are localized to declared timestamp windows.

## Real-video matrix and visual evidence

`sync-v2-validation/fixtures/reference-action-real-video-manifest.json` covers all eight available exercise families twice: wrong-vs-reference and same-reference self-control, and every one of the five cricket files appears in a classified generic-action case. Together those cases reference all 21 locked corpus videos. Same-reference controls and visually unrelated negatives are gating; filename-selected exercise wrong-vs-reference pairs and ambiguous/mixed cricket scenes are report-only until stronger temporal annotation exists, because reference-relative validation must not turn filename semantics into universal biomechanics truth.

Visual inspection on 2026-08-09 used timestamped representative frames from every exercise clip and all five cricket clips. Important observations:

- Every named exercise wrong/reference pair visibly belongs to the same movement family, although crop, performer, viewpoint, codec, and environment differ.
- `Right videos/Biceps_curl_right.mp4` is tutorial-style and visibly includes both red-X and green-check segments. It must not be treated as framewise universal correctness truth.
- The Jofra Archer clip and `videoplayback.1775553436742.publer.com.mp4` both visibly show bowling, but cross-person/style/viewpoint differences make that pair exploratory rather than a correctness golden.
- `videoplayback.1775553006338.publer.com.mp4` and `videoplayback.1775553258598.publer.com.mp4` visibly show batting/crease actions, so they are suitable unrelated-motion negatives for a Jofra bowling reference.
- `videoplayback (1).mp4` visibly contains both a batter and a bowler in the same play; it is retained only as a non-gating multi-person/mixed-scene subject-selection stress case, not a clean negative.
- Jofra-vs-Jofra self-control is the safest positive non-exercise generic-action case.

Generated overview images live under ignored `test-artifacts/reference-action-validation/visual-review/` and are not committed.

## Pose extraction provenance

`reference-action-pose-coverage-baseline.json` records reusable API35 pose diagnostics from the existing 16-case exercise matrix. All 16 exercise cases were available; every sampled source/reference frame in those artifacts was detected and not marked unusable.

`reference-action-cricket-pose-coverage-baseline.json` records a fresh API35 extraction performed through `scripts/sync-v2-validation-adapter` at 15 analysis FPS / 640 px long edge for one bowling reference and one batting negative. The Jofra reference produced 281/281 detected usable sampled frames, and the batting negative produced 172/172. Existing Sync-v2 output from that drive run is explicitly not a generic-action score.

These coverage numbers only establish that pose evidence exists and is suitable to feed the future action core. They do not establish recognition accuracy.
