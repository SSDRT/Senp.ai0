# Android video and MediaPipe adapter

This lane implements the canonical `ai.senp.core.pipeline.VideoPoseExtractor` boundary. The Android modules expose no duplicate pose, landmark, video-role, timestamp, or failure contracts; all retained output is from `core-contracts`.

## Modules

- `android-video`: sequential `MediaExtractor`/`MediaCodec` decoding, presentation-timestamp sampling, metadata rotation, long-edge scaling, and reusable YUV-to-ARGB conversion.
- `android-pose-mediapipe`: `AndroidVideoPoseExtractor`, MediaPipe Pose Landmarker Full in `VIDEO` mode, and direct mapping to canonical 33-landmark frames.
- `validation-app`: emulator-only harness that writes canonical JSON, timestamp-matched frames, overlays, contact sheets, and diagnostics. It is not a product UI or composition root.

The modules use the root version catalog and canonical toolchain: Gradle 8.13, Kotlin 2.0.21, AGP 8.9.2, Java 21, and compile/target API 35.

## Model acquisition

The model binary is intentionally excluded from Git. Fetch and verify the pinned official artifact with:

```bash
./scripts/fetch_pose_model.sh
```

The script writes `local-models/pose_landmarker_full.task` only after checking its exact 9,398,198-byte size and SHA-256 `5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1`. Provenance is recorded in `models/pose_landmarker_full.provenance.json`. Product builds do not bundle this binary: the app downloads the same pinned artifact on demand into private storage, verifies byte count and SHA-256, and then passes the verified file to the MediaPipe adapter. The local fetch script remains useful for Android instrumentation and engineering validation.

MediaPipe Tasks Vision `0.10.32` is pinned because its AAR contains the `x86_64` JNI runtime required by the API 35 emulator, in addition to phone ABIs.

## Canonical integration

```kotlin
val extractor: VideoPoseExtractor = AndroidVideoPoseExtractor(context)
val result = extractor.extract(
    role = VideoRole.SOURCE,
    source = VideoSource(videoUri, videoSha256),
    sampling = SamplingConfiguration(targetFramesPerSecond = 15, longEdgeCapPx = 640),
    model = PoseModelConfiguration(modelSha256),
)
```

`AndroidVideoPoseExtractor` accepts plain paths, `file:` URIs, and `content:` URIs. Content URIs are staged into the app cache and deleted after extraction. Both the input video and the installed/private model are checked against their canonical SHA-256 values.

The decoder:

- decodes sequentially rather than seeking per sample;
- retains real container presentation timestamps and normalizes canonical timestamps to the first decoded frame;
- explicitly applies 0/90/180/270-degree track rotation before long-edge capping;
- samples in timestamp space at 15 FPS by default;
- supports device-provided H.264 and HEVC `MediaCodec` decoders;
- rejects duplicate or backward timestamps;
- converts source, container, codec, timeout, timestamp, and explicit cancellation failures into `AnalysisFailure.VideoPose`.

The pose adapter configures one pose, `RunningMode.VIDEO`, segmentation disabled, and independent detection, presence, and tracking thresholds. Image xyz values are preserved exactly as doubles. World xyz, visibility, and presence remain nullable when MediaPipe does not supply them. No-person and unusable-tracking samples are retained as canonical blind frames with 33 placeholder landmarks and explicit validity reasons.

Coroutine cancellation is rethrown. Calling `AndroidVideoPoseExtractor.cancel()` requests adapter cancellation and returns a typed `VideoPoseFailureKind.CANCELLED` result at the next decoder boundary.

## Memory invariant

Decoding and inference form one synchronous streaming boundary. The decoder owns one capped ARGB array and reuses it. Its image is closed before the inference callback; the callback finishes before another frame is accepted. Pixel arrays never enter `core-contracts`, `PoseSequence`, JSON output, or caches.

`VideoPoseDiagnostics` declares `maxInFlightFrames = 1`. The collector measures the live callback depth and reports `peakInFlightFrames`; exceeding one throws immediately. Decoder diagnostics separately report the observed image-queue depth and output-buffer reuse.

## Emulator validation

```bash
./gradlew :android-video:connectedDebugAndroidTest \
  :android-pose-mediapipe:connectedDebugAndroidTest \
  :validation-app:assembleDebug
adb -e install -r -t validation-app/build/outputs/apk/debug/validation-app-debug.apk
```

Copy a video to the app-private directory and start the harness:

```bash
adb -e push sample.mp4 /data/local/tmp/sample.mp4
adb -e shell run-as ai.senp.validation mkdir -p files/input
adb -e shell run-as ai.senp.validation cp /data/local/tmp/sample.mp4 files/input/sample.mp4
adb -e shell am start -W -n ai.senp.validation/.ValidationActivity \
  --es video /data/user/0/ai.senp.validation/files/input/sample.mp4 \
  --es label sample \
  --es capture_ms 0,1000,2000
```

Evidence is written under the app external-files `evidence/<label>` directory:

- `canonical_pose.json`
- `summary.txt`
- timestamped raw and overlay JPEGs
- a two-row timestamped contact sheet
- `COMPLETE`

Generated evidence, videos, model binaries, APKs, and build output remain ignored or outside Git.

## Limitations

- Codec/profile availability is determined by the Android device or emulator; unsupported combinations return a typed failure.
- Rotation is intentionally limited to right angles.
- Nearest-neighbour output scaling avoids an additional full-frame allocation.
- MediaPipe `VIDEO` tracking is deliberately single-stream and sequential; one extractor instance rejects concurrent extraction.
- Emulator timings characterize the API 35 x86_64 runtime, not physical-phone performance.
