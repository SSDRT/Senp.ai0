# Android video + pose engine

This lane provides headless Android components only. `validation-app` is an emulator-only diagnostic harness and is not a product frontend or composition root.

## Modules

- `pose-contracts`: platform-neutral pose DTOs, typed outcomes, and the stable 33-landmark schema.
- `android-video`: sequential `MediaExtractor`/`MediaCodec` decode with metadata rotation, presentation-timestamp sampling, and bounded YUV-to-ARGB conversion.
- `android-pose-mediapipe`: MediaPipe Pose Landmarker Full adapter in `VIDEO` mode.
- `validation-app`: test-only APK that runs the two modules against local videos and writes contact sheets, pose overlays, and JSON diagnostics.

## Model acquisition

The model binary is deliberately not checked into Git. Fetch and verify it with:

```bash
./scripts/fetch_pose_model.sh
```

The script downloads the pinned official `pose_landmarker_full/float16/1` artifact, verifies its exact byte length and SHA-256, and writes it to ignored `local-models/pose_landmarker_full.task`. Full provenance and license information is recorded in `models/pose_landmarker_full.provenance.json`.

The Android adapter uses `com.google.mediapipe:tasks-vision:0.10.32`. This is the newest inspected official AAR that directly includes the `x86_64` JNI library required by the project emulator, alongside phone ABIs. Later inspected AARs (`0.10.33`, `0.10.35`, and `1.0.0`) contain no JNI libraries and declare no separate native dependency in their published POMs.

## Video integration

```kotlin
val decoder = SequentialVideoDecoder(
    DecodeConfig(targetFps = 15.0, longEdgeCapPx = 640),
)

val result = decoder.decode(videoFile) { frame ->
    // frame.timestampMs is normalized to the first decoded presentation timestamp.
    // frame.presentationTimeUs retains the container presentation timestamp.
    consumeSynchronously(frame)
}
```

`DecodedFrame.argb8888` is one reused decoder-owned output buffer. The callback must consume it synchronously; call `copyPixels()` only when a frame must outlive the callback. This keeps memory bounded to codec-owned images, a two-image queue, one capped ARGB output array, and two capped integer coordinate maps reused across frames.

The decoder:

- decodes sequentially rather than seeking once per sample;
- applies right-angle track rotation explicitly during YUV conversion;
- downsizes after orientation so the oriented long edge is at most 640 pixels by default;
- samples from presentation timestamps at 15 FPS by default, without frame-index assumptions;
- rejects duplicate or backward decoded timestamps;
- reports typed source, container, codec, timeout, cancellation, timestamp, and consumer failures;
- records decode, conversion, buffer-depth, frame-count, and presentation-time diagnostics.

## Pose integration

```kotlin
val estimator = MediaPipePoseEstimator.create(
    context = context,
    source = PoseModelSource.Asset("pose_landmarker_full.task"),
    config = MediaPipePoseEstimator.Config(
        detectionConfidence = 0.5f,
        presenceConfidence = 0.5f,
        trackingConfidence = 0.5f,
    ),
)

estimator.use {
    val outcome = it.estimate(
        PoseInputFrame(
            timestampMs = frame.timestampMs,
            width = frame.width,
            height = frame.height,
            argb8888 = frame.argb8888,
        ),
    )
}
```

The adapter configures Pose Landmarker Full with one pose, `VIDEO` running mode, and segmentation disabled. Detection, presence, and tracking thresholds are separate settings. Every successful `PoseFrame` contains all 33 image and world landmarks in neutral schema order, including visibility and presence. MediaPipe classes do not appear in `pose-contracts`.

`PoseOutcome` distinguishes detected poses, no-person frames, and unusable tracking. Unusable tracking is typed as either a landmark-count mismatch or insufficient landmark confidence. Pose timestamps must strictly increase.

## Validation harness

Build and install the diagnostic APK:

```bash
./gradlew :validation-app:assembleDebug
adb -e install -r -t validation-app/build/outputs/apk/debug/validation-app-debug.apk
```

The activity accepts these intent extras:

- `video`: required absolute path readable by the app;
- `label`: optional evidence-directory name;
- `capture_ms`: optional comma-separated timestamps, for example `0,1800,3600`.

It writes `summary.json`, timestamped raw frames, timestamped pose overlays, a two-row contact sheet, and a `COMPLETE` marker beneath the app external files directory. The output is test evidence only.

## Limitations

- Rotation support is intentionally limited to 0, 90, 180, and 270 degrees.
- Output scaling uses nearest-neighbour sampling to avoid an additional full-frame allocation. On an x86 emulator the Kotlin YUV conversion is the dominant cost; phone hardware should be measured independently.
- Codec support still depends on the device's `MediaCodec` inventory. Unsupported codec/profile combinations return a typed failure.
- Pose image coordinates may legitimately fall slightly outside `[0, 1]`; they are preserved rather than clipped.
- The current API is synchronous and single-estimator-threaded by design, matching MediaPipe `VIDEO` tracking semantics and timestamp ordering.
