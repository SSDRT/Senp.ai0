# Current CameraX probe result

28 October2026. Frozen probe `3dcc8b7`. The actual production camera activity ran on the same owned API35/x86_64/4KB runtime used for the static stage profile. The installed main APK is byte-identical to the verified viewport-guard delivery; no model, inference threshold, reference selection or production source changed.

The short diagnostic confirms that the slowdown is present in the actual activity, despite fast isolated static-input processing:

| Observed camera-window measurement | Result |
|---|---:|
| Window /delivered results |60.233s /78|
| DeliveredFPS |1.295|
| Analyzer-to-result p50 /p95 |610.81 /1208.64ms|
| Native-detect p50 /p95 |354.96 /748.62ms|
| Paired unattributed analyzer p50 /p95 |260.42 /679.37ms|
| Native graph recreations during window |0|
| Continuity resets during window |67|
| Model mismatches /analyzer errors /crash-or-ANR log matches |0 /0 /0|
| Maximum observed PSS |259.81MiB|

Every measured frame declares an UNKNOWN capture clock. Capture-to-result latency is therefore **unmeasured**. Neither analyzer duration nor native-detect time may be substituted. The60-second window is also ineligible for either ten-minute performance/memory gate. Short PSS observations do not establish the required first/last two-minute windows. Readiness remains **50/100**, and human live-rep precision remains unknown.

The camera warmed for30 seconds, paused at CREATED for750ms and produced at least three additional results after the post-resume baseline within the unchanged20-second deadline (baseline41, afterward44). All callback records, including warmup/resume and outliers, are preserved. Original timestamps remain monotonic, analyzer records do not overlap, and the existing KEEP_ONLY_LATEST queue contract remains capacity1. Full model identity matches the reference throughout. A passing instrumentation case means these diagnostic/ownership checks passed, not that performance gates passed.

Inspected three original lossless camera inputs at result offsets0.610,30.482 and55.082 seconds with their complete raw33-landmark predictions. The virtual room contains no exercising human; all three snapshot predictions are BLIND. These images provide no human-form correctness labels. The synchronous observer exported three images/raw records; its measured export work totals about1.118s and stays in observed delivery timing. No frames were removed to improveFPS or a percentile.

The static API35 native-detect p95 values of20.42–29.94ms do **not** translate to current camera throughput. Active camera/preview execution, input preparation, scheduling or other runtime work remains a possible cause. The camera's native-detect interval itself is much slower; zero graph recreations rule out repeated native graph construction as the observed explanation. These measurements do not isolate a single component or justify changing model fidelity, confidence or rep checks.

Verification: **145 Python tests pass**, including three short-window/clock/lifecycle guards, and one isolated real-activity native case passed. Main source/APK and the normal31-case native inventory remain unchanged; the prior complete native/scoped evidence is preserved rather than represented as rerun. No validation/final skeletal data, annotations, training, new delivery APK, optimization or performance points are claimed. Original frames, raw predictions, timing records, runtime identity, actual instrumentation transcript, logs and checksums remain bound in `models/live-camera-probe.provenance.json`.

Next investigate runtime contention with a separately frozen controlled diagnostic before changing production execution. Avoid another unchanged ten-minute soak while the capture clock remains UNKNOWN and throughput is below the gate. Independent form discrimination/recall remains a separate milestone requirement; a virtual-camera speed test cannot satisfy it.
