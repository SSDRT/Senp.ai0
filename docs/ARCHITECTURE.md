# Headless engine architecture

## Module graph

```text
core-contracts
      ↑
core-pipeline
      ↑
 core-cache
      ↑
headless-runner
```

- `core-contracts` owns immutable, serializable, timestamp-first domain values, typed failures/outcomes, result/provenance schemas, and the versioned cache-key contract. It has no project dependency.
- `core-pipeline` owns the narrow adapter ports and injected orchestration. It depends only on `core-contracts` and coroutines.
- `core-cache` provides a bounded, mutex-protected in-memory cache behind `AnalysisCache`. Persistent Android storage belongs in an integration module.
- `headless-runner` is a deterministic JVM composition root with fake adapters, an executable JSON result, and end-to-end tests. Production modules must not depend on its fakes.

The root `checkCoreBoundaries` task verifies this dependency direction and rejects Android, AndroidX, MediaPipe, OpenCV, AWT, HTTP-client, or networking dependencies and imports in production core source sets.

## Timestamp-first contracts

`TimestampMs` is authoritative throughout decoding, pose, features, angles, phases, alignment, and problem windows. Diagnostic frame indices are retained only for inspection. Durations and temporal bounds use explicit millisecond types or names; phase and problem windows use end-exclusive bounds.

A `PoseFrame` contains exactly 33 landmarks in index order `0..32`. Every landmark retains normalized image coordinates, metric world coordinates, visibility, and presence. Confidence loss and short-gap repair are represented by `FrameValidity`, rather than dropping landmark slots or inventing a second pose format. `ImmutableFrameBuffer` defensively copies bytes at ingress and egress so an adapter cannot mutate an accepted frame.

## Orchestration and cancellation

`AnalysisPipeline` receives every dependency through its constructor. A call owns its stage state and timing list, so one pipeline instance can safely handle concurrent calls when its adapters are thread-safe. Source decoding through phase detection completes before reference processing, limiting simultaneous frame retention.

Each adapter returns `StageResult`. Typed adapter failures remain intact; unexpected exceptions are mapped to `AnalysisFailure.Unexpected` with the active `PipelineStageId`. A real coroutine `CancellationException` is rethrown so structured cancellation propagates to the caller and no partial result is cached. An adapter may instead deliberately return `AnalysisFailure.Cancelled` when cancellation is a domain outcome rather than coroutine cancellation.

Stage timings use an injected monotonic clock. Provenance uses an injected wall clock; no ordering assumption is made between wall-clock reads because system time can be adjusted.

## Cache semantics

`CacheKey` schema version 1 includes:

- source and reference SHA-256 values;
- model SHA-256, model variant, and all three confidence thresholds;
- pipeline version;
- timestamp sampling FPS and long-edge cap;
- normalization version;
- exercise-profile version.

Its canonical form length-prefixes UTF-8 string fields before producing a stable SHA-256 ID, preventing delimiter or newline ambiguity. The cache stores only `CachedAnalysis`: the reusable payload, computation time, and producer engine version. Collection implementations are detached on store and lookup so adapter- or caller-owned mutable lists cannot alter an entry. On a hit, orchestration rebuilds `AnalysisResult` with the current request ID, request time, invocation timings, cache status, and serving engine version. This prevents request-specific metadata from leaking across calls.

Only a complete successful payload is stored. Failures and coroutine cancellations are never cached.

## Android integration

Android decoder and MediaPipe Pose Landmarker implementations belong in separate integration modules and implement `VideoDecoder` and `PoseEstimator`. Motion, phase, and alignment lanes implement their algorithms behind `MotionProcessor`, `PhaseDetector`, and `AlignmentEngine`; this lane intentionally does not duplicate those internals.

The main analysis path has no renderer, UI, Compose, cloud, coaching, or MP4-output dependency.
