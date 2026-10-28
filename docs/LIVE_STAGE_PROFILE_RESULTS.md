# Processing stage profile result

28 October2026. Frozen experiment `af566c7`, export-only retry `2bd815d`. Production main code and the installed app APK are byte-identical to the verified viewport-guard delivery `872c9f7`. Model weights, thresholds and reference selection are unchanged. This is a diagnostic result, not a speed improvement or accuracy gain.

The historical compiler5/API36 camera run still has793 completed observations over600 seconds,1.32 deliveredFPS, and an UNKNOWN capture clock. Its analyzer duration p50/p95 is622.89/1295.49ms; native-detect p50/p95 is325.83/691.68ms. Pairing durations **before** computing residual percentiles gives unattributed analyzer p50/p95 of279.77/796.03ms. Subtracting the two p95 values would incorrectly report603.81ms. Unattributed time includes other processing, input conversion and scheduling; this does not locate the bottleneck. Absolute capture-to-result latency remains unmeasured.

The new isolated API35/x86_64/4KB profile uses the three original virtual-camera JPEGs and the existing upstream static pose regression image. Every case retains eight warmup and24 measured replays, with alternating processor timing order and generated67ms model timestamps. All128 timing rows are preserved;96 are in the predeclared measured window. One prepared input and complete33-landmark raw result per case, plus the unchanged original source images, remain available for review.

| Static case | Native detect p50 /p95(ms) | Generic update p50 /p95(ms) | Update with qualified counting p50 /p95(ms) |
|---|---:|---:|---:|
| Recorded virtual start |18.08 /20.42|0.36 /0.47|0.72 /1.16|
| Recorded virtual middle |19.71 /22.92|0.36 /0.60|0.68 /1.20|
| Recorded virtual end |15.48 /21.65|0.35 /0.95|0.72 /1.11|
| Upstream static pose |24.80 /29.94|2.38 /3.51|3.72 /5.17|

Input preparation p95 is0.71–1.36ms and other estimator work p95 is0.42–0.69ms. Native detect includes the estimator's owned-input/image-builder work; it is not a pure neural-kernel timer. Generic and qualified updates are alternative whole processor calls, not additive stages. All three virtual-scene cases produce BLIND status in their24 measured samples; the upstream pose case produces VALID status in24. These are static regression outputs, not human-rep labels.

These short, quiet, static-input measurements demonstrate that the current production components can execute faster than the historical full camera pipeline on this different emulator/API context. They do **not** prove the actual camera bottleneck, compare identical host/OS conditions, establish delivered cameraFPS, or validate a latency optimization. The profile excludes CameraX conversion, preview/UI contention, real camera admission and sustained memory. No timing result selected a model or altered pose evidence.

One isolated native profiling case passed. The first Gradle-connected run also passed, but its app data disappeared before export; its XML and failed pull are preserved and no stage timings were recovered from it. The retry used the same frozen APKs via direct instrumentation and exported the original data before shutdown. Both task-owned emulators stopped cleanly. The actual retry transcript is retained; no replacement JUnit XML was manufactured. **142 Python tests pass**, including three new duration/clock/scope guards. The normal31-case native suite and production APK remain unchanged; they were not rerun or replaced by this isolated profile.

Inspected all four source images and four prepared-input/raw-output pairs, plus the stage distribution plot. Repeated images and virtual scenes provide no new form annotations. The existing2,531 readiness receipts revalidate at **50/100**; human live-rep precision remains unknown. No new app delivery, independent-label/final-subject access, training, deployment or performance points are claimed.

Next freeze a short diagnostic on the **actual current CameraX activity on this same API35 runtime**, using its existing opt-in diagnostics and the same reference-selected Full model. Measure analyzer/native durations, delivered frames, timestamp-source declarations and lifecycle recovery; keep camera clocks with UNKNOWN source incomparable. That matched current-runtime camera probe can establish whether the old slowdown persists before any optimization is proposed. A short probe cannot replace the fixed ten-minute performance/memory gates, and no analyzer-duration surrogate may replace capture-to-result latency.
