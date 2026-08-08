# Wave 1 contract freeze

## Canonical build

Gradle 8.13, Java 21, Kotlin 2.0.21, AGP 8.9.2, compile/target API 35. Root `settings.gradle.kts`, `build.gradle.kts`, wrapper and `gradle/libs.versions.toml` are authoritative; downstream branches must not replace them.

## Memory invariant

`VideoPoseExtractor` owns decoding and inference as one streaming boundary. A decoded pixel buffer may exist only while its matching inference call consumes it; adapters must release or reuse it before accepting the next decoded frame. Pixel buffers never enter `core-contracts`, cache payloads, or `PoseSequence`. `VideoPoseDiagnostics.peakInFlightFrames <= maxInFlightFrames` is mandatory and tested.

## Wave 2 mapping

Implement `ai.senp.core.pipeline.VideoPoseExtractor` in Android modules. Map `ai.senp.pose.PoseLandmarkId` by index to `ai.senp.core.contracts.PoseLandmarkId`; map image xyz exactly; map world xyz when MediaPipe supplies it and otherwise `null`; preserve visibility/presence nullable values. Convert decoder and MediaPipe failures to `AnalysisFailure.VideoPose` and never expose Android/MediaPipe types. Remove the lane-local public `pose-contracts` DTOs after adapter tests pass.

## Wave 3 mapping

`core-motion` depends on `core-contracts`. Replace lane-local `PoseFrame`, `Landmark`, and `FrameValidity` at its public boundary with canonical types. Internal optimized types are permitted only as private implementation details. Return canonical `MotionSeries`; map VALID, REPAIRED, DEGRADED, BLIND and CONTINUITY_BREAK losslessly. Keep all millisecond algorithms and existing parity fixtures.

## Wave 4 mapping

`core-alignment` depends on `core-contracts`. Accept canonical `MotionSeries` and return canonical `AlignmentResult` plus `ProblemWindow`. Keep masked DTW, phase/rep behavior and validity suppression. Lane-local `ExerciseProfile` remains an internal algorithm configuration but its selected version is supplied by the canonical request.

## Prohibited duplication

No public duplicate PoseFrame, PoseLandmark, MotionSeries, AlignmentResult, ProblemWindow, VideoRole, timestamp, validity or failure DTO. No lane may replace root build files.

## Acceptance

Wave 2: real H.264 and HEVC clips on `senp_api35`, monotonically timestamped 33-landmark JSON, real contact-sheet overlays, and peak in-flight frames within the declared bound.
Wave 3: canonical contract fixture replay, 10/15/20 FPS invariance, blind-span preservation and existing parity suite.
Wave 4: canonical motion fixture replay, speed-only non-error, blind confidence zero, monotonic mapping and existing DTW suite.
