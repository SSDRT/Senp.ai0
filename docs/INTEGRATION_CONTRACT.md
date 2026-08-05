# Wave 1 integration contract

## Ports owned by this lane

| Port | Input responsibility | Output responsibility |
|---|---|---|
| `VideoDecoder` | Decode one `VideoSource` using timestamp sampling. | A non-empty `DecodedVideo` with the requested role, ordered timestamps, explicit duration, and pure-JVM frame buffers. |
| `PoseEstimator` | Run the configured model/thresholds for every sampled timestamp. | One ordered `PoseFrame` per decoded frame, same timestamps, all 33 landmarks. |
| `MotionProcessor` | Apply the named normalization/profile behavior. | Timestamped validity, features, and angles for the same role. |
| `PhaseDetector` | Apply profile-specific phase/rep detection. | Ordered end-exclusive phase segments for the same role. |
| `AlignmentEngine` | Compare both motion/phase series using the full configuration. | A non-empty monotonic alignment path plus genuine or uncertain problem windows. |
| `AnalysisCache` | Read/write by the complete versioned `CacheKey`. | `CacheLookup.Hit`/`Miss` and storage of request-neutral `CachedAnalysis`. |
| `PipelineStage` | Optional common shape for additional integration stages. | A typed `StageResult` identified by `PipelineStageId`. |

Core ports never expose Android, MediaPipe, OpenCV, URI framework, bitmap, matrix, or renderer types.

## Failure and cancellation rules

Adapters return the most specific `AnalysisFailure` subtype they can provide. Role-specific failures identify source versus reference. Cache failures identify read versus write. Orchestration converts unhandled exceptions into `AnalysisFailure.Unexpected` with the active stage.

Coroutine cancellation is not converted into a successful return value: `CancellationException` propagates to the caller, and no cache write occurs. `AnalysisFailure.Cancelled` is reserved for adapters that intentionally represent cancellation as a typed domain result.

A cache write failure makes the analysis invocation fail even though computation finished, because returning success would falsely claim the result was persisted under the requested cache contract.

## Versioning rules

Any behavior-affecting change increments the field that identifies it:

- serialized key shape: `CacheKey.CURRENT_SCHEMA_VERSION`;
- serialized result shape: `AnalysisResult.CURRENT_SCHEMA_VERSION`;
- orchestration/algorithm behavior: `pipelineVersion`;
- model bytes: `modelSha256`;
- model topology/selection: `modelVariant`;
- detection/presence/tracking gating: `PoseThresholds` values;
- timestamp sampling/resizing: `SamplingConfiguration`;
- coordinate/feature normalization: `normalizationVersion`;
- exercise-specific features, phases, thresholds, or labels: `exerciseProfileVersion`.

Do not silently reuse a cache entry after any of those inputs change.

## Contract invariants

- Timestamps, not frame numbers, drive every temporal decision.
- SHA-256 values are exactly 64 lowercase hexadecimal characters.
- `PoseFrame.landmarks` is exactly the ordered index range `0..32`.
- Image and world coordinates, visibility, and presence remain available at the core boundary.
- Sampled frame, pose-frame, and feature timestamps are strictly increasing; phase segments are ordered and non-overlapping.
- Alignment paths are monotonic in both videos and never repeat an identical point.
- Confidence and severity values are finite and constrained to `[0, 1]`.
- Problem deviations are finite and non-negative; peak deviation is not below mean deviation.
- Stage timings use monotonic time; provenance timestamps use wall time without assuming wall-clock monotonicity.
- Cache hits bypass analysis adapters but use current request metadata and current invocation timings.
- Only complete success payloads are cached.

## Composition guidance

Create one application composition root that supplies concrete adapters, cache, clocks, and an engine version. Call `AnalysisPipeline.analyze` from a caller-owned coroutine scope and handle `AnalysisOutcome` exhaustively. Keep adapter lifecycle management outside the domain contracts; adapters may be long-lived, but they must be safe for the concurrency chosen by the caller.
