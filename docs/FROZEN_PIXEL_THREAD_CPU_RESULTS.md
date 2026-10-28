# Original thread counters point to rendering work

29 October 2026 local date. Frozen experiment `6e58e46`. The new instrumentation preserves original per-thread kernel counters around the same fixed-pixel CameraX workload. It identifies a concrete rendering hypothesis for a subsequent controlled change, while retaining **50/100** readiness and the unchanged production engine `872c9f7`.

The measured kernel frequency is100 clock ticks/second. All512 attempted scans and256 timing rows are preserved;192 measured intervals contain7,512 matched stable-identity thread intervals. No measured birth, exit, recycled identity, unreadable entry or enumeration failure occurs. This is an observed census result, not a guarantee that arbitrary devices permit the same reads. Missing/unsupported synthetic cases remain unknown in the tests and adversarial check.

Across the96 measured active-camera brackets, the original name `RenderThread` accumulates18,620ms of observed CPU, about193.96ms per bracket, and is the largest individual name group in every active block. Other substantial observed groups include the probe owner `pool-2-thread-1` (12,650ms), `senp.validation` (8,450ms), `HeapTaskDaemon` (4,420ms) and binder threads. Original names such as `drishti_gl_runn` are preserved without assigning their work to a particular native operator. Names can be truncated and several threads can share one name; identity-level records remain available.

| Block | Observed stable-thread CPU total over24 brackets(ms) | Mean before+after scan wall cost(ms) |
|---|---:|---:|
|Room quiet1|570|8.30|
|Room active1|15,470|391.29|
|Room active2|17,820|355.72|
|Room quiet2|670|11.65|
|Positive quiet1|880|12.08|
|Positive active1|22,820|475.05|
|Positive active2|22,820|502.06|
|Positive quiet2|990|12.83|

**The observation overhead is substantial**, especially with the camera active. Thread deltas span non-atomic before/after scans and their overhead, rather than exactly the native call. These CPU totals cannot become a percentage of native inference, cannot be subtracted from wall time to infer waiting, and cannot prove that every thread/operator was observed. At100Hz, zero observed ticks also do not prove idleness. The profiled workload still shows approximately22.83×/19.89× active/quiet native mean-duration ratios, but those values are not a speed comparison against the preceding unprofiled experiment.

The evidence supports investigating the existing `PreviewView.COMPATIBLE`/TextureView rendering path with an explicit implementation-mode experiment. It does not prove that rendering is the entire bottleneck, justify changing pose quality, or authorize a production switch without testing. The next experiment should use ordinary actual-camera timing without per-call procfs scans, hold inference/analysis settings fixed, record the actual SurfaceView/TextureView chosen (including fallback), inspect original inputs and compare both repeated modes. Do not remove the product preview based on this census.

Verification passes **167 Python tests**, including seven parser/identity/read-race cases, plus an adversarial all-unreadable census analysis. The isolated native case passes in208.417s. Eight static-input/raw-prediction pairs, four separate original camera frames and the thread chart were inspected. All model pixels, generated model times, confidence/raw MP33 data and reference-selected Full identity retain the prior scope. No camera-source image receives a static prediction overlay, and no static status becomes a human-form label.

The original verified main APK is installed byte-for-byte; no main source, model weight, threshold, reference selection, native production inventory or delivery APK changes. No validation/final subjects or new annotations are accessed. The2,531 production readiness receipts revalidate at50/100. This profiled static-input workload provides no live deliveredFPS, capture-to-result latency, ten-minute qualification or independent rep precision. Human live-rep precision remains unknown.
