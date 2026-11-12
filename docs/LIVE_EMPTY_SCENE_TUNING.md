# Avoid repeated native graph creation before any pose has been tracked

The camera benchmark exposed repeated model/tracker recreation while the virtual camera saw an empty room. The existing 500 ms gap rule recreated the native graph even when it had never produced a pose. Slow acquisition then caused another capture gap, paying the same initialization cost on the next frame.

The adapter now distinguishes logical continuity from native graph lifetime:

- Every long capture gap still clears stabilization and emits `CONTINUITY_BREAK`. Capture timestamps and confidence gates are unchanged.
- A graph that has **never produced any native pose landmarks** can continue detection after a gap when image dimensions are unchanged. It has no previous pose/filter history to carry into the new observation.
- Once a graph has produced any pose, including one the mapper later deems low-confidence, a long gap still recreates it. A later empty result does not clear this lifetime flag.
- Dimension changes and the existing GPU-error fallback retain full native recreation. Creation, inference and close remain on the analyzer owner thread.

This follows the [pinned MediaPipe graph](https://github.com/google-ai-edge/mediapipe/blob/v0.10.32/mediapipe/tasks/cc/vision/pose_landmarker/pose_landmarker_graph.cc): detection runs when previous pose rectangles are absent; stream smoothing is applied to pose landmarks. The absence of prior pose/filter history is an inference from this source, further checked against native output. It is not inferred merely from a low mapped confidence score.

Two JVM policy tests check the reset decisions. Native instrumentation verifies blank-gap continuity, zero native recreations before first acquisition, mandatory recreation after an earlier pose even if followed by an empty frame, and equality of all 33 acquired image/world coordinates, visibility and presence against a fresh graph within 1e-6. The existing positive-pose long-gap and dimension-reset tests also pass. No network weights, raw landmark coordinates, confidence thresholds or rep-acceptance rules are changed.

## Camera measurement and its limits

The opt-in camera instrumentation measures the actual reference-selected Full model through `LiveReferenceActionActivity`, CameraX and the native adapter. It verifies pause/resume before a separate uninterrupted ten-minute interval. It retains capture/arrival/result/native-inference timestamps, model hashes, reset counters, PSS samples, errors and original timestamped camera/MP33 snapshots. The diagnostics observer is absent during normal use; it adds no product UI or automatic recording.

The first warmup attempt delivered only six observations and failed its initial minimum-ten-frames assertion. That failure is preserved. The harness now requires at least one observation to begin measuring; the actual ten-minute duration, >=5 FPS and <=200 ms capture-to-result gates remain unchanged. Lowering a warmup precondition does not earn performance points.

The complete pre-optimization run on Windows / API 36 x86_64 with the Full model produced:

- 600,066.4862 ms measured duration, 125 delivered results, **0.2083 FPS**.
- **5,917.60 ms p95 analyzer-to-result**, versus 1,019.84 ms p95 native detect time. Detect timing alone omitted graph recreation and other processing.
- 124 logical tracker resets during the window; the old implementation recreated the native graph for each one.
- Peak total PSS 299.25 MiB; first/last post-warmup window means 237.67 / 235.12 MiB.
- No model mismatch, analyzer exception or crash/ANR found in the retained run records.

The post-optimization run uses the same AVD, model, camera path, scene, warmup and ten-minute criteria. Exact before/after results and original-file hashes are recorded in `models/live-pose-tuning.provenance.json` and the delivery evidence report. These are observed device-specific measurements; host load and emulator state can affect timings.

| Empty-scene measurement | Before | After |
|---|---:|---:|
| Measured duration | 600.066 s | 600.003 s |
| Delivered frames | 125 | 793 |
| Delivered FPS | 0.2083 | 1.3217 |
| p95 analyzer-to-result | 5,917.60 ms | 1,295.49 ms |
| p95 native detect | 1,019.84 ms | 691.68 ms |
| Native recreations during measured window | 124, inferred from the old reset implementation/counter | 0, directly recorded |
| Logical continuity resets during window | 124 | 612 |
| Peak PSS | 299.25 MiB | 273.55 MiB |
| First / last two-minute mean PSS | 237.67 / 235.12 MiB | 263.43 / 260.90 MiB |

Observed empty-scene throughput is 6.34 times higher and p95 analyzer-to-result is 78.11% lower. Logical gaps remain visible; the optimization does not bridge those 612 discontinuities. The post-run still fails the >=5 FPS criterion. It also cannot qualify capture latency because the sensor clock is UNKNOWN. The change applies to never-tracked graphs; performance while tracking an exercising person is not established by an empty-room run.

The selected virtual-scene camera reports `SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN`. Therefore **absolute capture-to-result latency is unverified**, even though analyzer arrival/result use a comparable system clock. The [Android timestamp contract](https://developer.android.com/reference/android/hardware/camera2/CameraMetadata#SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) supports direct comparison with elapsed realtime only for a REALTIME source. Neither short native inference time nor analyzer-to-result time is substituted for the frozen capture-latency gate.

CameraX's [KEEP_ONLY_LATEST contract](https://developer.android.com/reference/androidx/camera/core/ImageAnalysis#STRATEGY_KEEP_ONLY_LATEST) supplies the bounded queue/admission behavior; original samples additionally check nonoverlapping analyzer execution. The Python auditor refuses latency credit for unknown clocks, changed model identity, insufficient duration/rate, overlapping processing, or unsupported memory windows. Its tests include failing controls for each relevant condition.

An empty virtual room is not labelled exercise footage. Neither this optimization nor the camera benchmark establishes human-rep accuracy, low-light exercise quality, thermal behavior on a physical phone or the 70/100 milestone. The ten-minute performance category earns zero whenever its fixed gates fail, even if memory is stable or the new path is faster.

Functional verification: 253 scoped JVM, 25 Android functional and 49 Python cases pass, along with the frozen 2,688 generated controls and 124 controlled corruptions. Two complete ten-minute qualification runs fail the performance criteria and remain in the evidence bundle, together with the initial warmup failure. Native output-equality controls are separate from clinical labels. Readiness remains 42.5/100.
