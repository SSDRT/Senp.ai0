# Actual preview implementation comparison

29 October 2026 local date. Frozen experiment `f65fd95`, unchanged production engine `872c9f7`, compiler `/9`. The controlled API35 experiment observes a **23.56% higher mean delivered result rate** for CameraX `PERFORMANCE` than `COMPATIBLE` on this emulator. This is a diagnostic result; no production optimization or accuracy gain is promoted. Readiness remains **50/100**.

Both conditions retain the actual camera, preview, analysis, reference-selected Full estimator, input resolution policy, confidence gates and causal processor. Each fresh activity rebinds a fresh Preview and the same owned ImageAnalysis after setting its requested implementation mode. The fixed order is COMPATIBLE / PERFORMANCE / PERFORMANCE / COMPATIBLE, with15s warmup and45s measurement per block. Actual child-view classes are recorded at measurement start and end, so a fallback cannot count as successful SurfaceView selection. No per-call procfs census runs here.

| Block | Requested mode / observed view at both endpoints | Delivered results | Observed seconds | Results/s | Analyzer p95(ms) | Native p95(ms) | Paired residual p95(ms) |
|---|---|---:|---:|---:|---:|---:|---:|
|A1|COMPATIBLE / TextureView|62|45.151|1.373|1281.35|675.51|742.92|
|B1|PERFORMANCE / SurfaceView|72|45.275|1.590|982.13|592.63|483.73|
|B2|PERFORMANCE / SurfaceView|74|45.099|1.641|942.01|596.95|407.88|
|A2|COMPATIBLE / TextureView|56|45.095|1.242|1296.13|808.19|652.15|

The ratio compares arithmetic means of the two observed block rates. All264 measured results remain480x640 and BLIND, with UNKNOWN source clocks; capture-to-result latency stays unknown. Native graph recreation increases are zero. Continuity resets remain45/53/58/46. No model mismatch, analyzer error, crash or ANR is observed. Maximum observed PSS ranges255.71-268.60MiB. Repeated blocks show host variation; this one four-block experiment supplies neither a confidence interval over independent devices nor a universal speed claim. Residual durations remain paired per callback; subtracting unrelated p95 values would be invalid.

Four original timestamped camera inputs and their complete raw33-landmark outputs were inspected, with correct capture/result association and no human inferred from the empty virtual room. Four separately timestamped screenshots show a visible, correctly oriented preview and the same surrounding layout in both implementations. These images do not establish identical pixels between camera blocks or human overlay alignment. The emulator's animated television changes across captures. No static-input prediction is attached to a live camera image.

Observation remains intrusive: original snapshot exports take1259.38/215.90/310.37/493.64ms, and screen captures take2708.92/2234.64/1568.00/1361.74ms. Every callback and both export costs remain in the recorded delivery windows; there is no outlier removal or adjustment of the reported rates. Each original callback row, screenshot, camera PNG, raw prediction, view hierarchy and log is archived. The SurfaceView result supports a limited rendering contribution to the slowdown; it does not explain the much larger preceding exact-pixel quiet/active gap.

Verification passes **171 Python tests**, including fixed order/window validation, unknown-clock handling and explicit fallback/missing-view tests. The isolated native case passes in286.793s on the owned API35 x86_64/4KB runtime; its original verified main APK is byte-identical to the delivery APK. The normal31-case native suite is not rerun or claimed here. All2,531 production evidence receipts revalidate at50/100. Main source, model weights, thresholds, reference-only selection, raw MP33, labels and final holdouts remain unchanged; no delivery APK is issued.

This short emulator probe fails the sustained delivered-rate target and provides no comparable capture latency, ten-minute qualification, handset evidence or human-rep precision. It earns **zero readiness points**. A production preference for PERFORMANCE would need a separate prospective candidate, automatic fallback retention and native lifecycle/preview checks on both owned runtimes before promotion. The independent-label recall and form-discrimination gates also remain unfinished.
