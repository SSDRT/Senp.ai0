# Actual camera preview contention result

28 October 2026. Frozen experiment `678cb73`. Four fixed-order blocks used the original verified main APK (`57a4d201…`), reference-selected Full model, compiler9 and the same owned API35/x86_64/4KB emulator. No product optimization is promoted. Readiness remains **50/100**.

| Block | Bound use cases | Results /45s window | DeliveredFPS | Analyzer p95(ms) | Native detect p95(ms) | Paired unattributed analyzer p95(ms) |
|---|---|---:|---:|---:|---:|---:|
|A1|Preview+Analysis|59|1.311|1229.33|952.96|563.21|
|B1|Analysis only|73|1.618|916.66|728.16|410.23|
|B2|Analysis only|72|1.590|892.76|591.63|472.54|
|A2|Preview+Analysis|63|1.390|1072.84|673.32|554.83|

The analysis-only blocks' mean delivered rate is18.79% higher than the preview blocks' mean in this one fixed ABBA run. This is a **use-case ablation observation**, not an app speed improvement or precision result. Throughput remains far below5FPS, and native calls remain much slower than isolated static-input calls. Removing the concurrent Preview use case does not explain the entire slowdown. Native duration also varies between repeated A blocks. This trial does not isolate rendering, CameraX conversion, emulator scheduling or native-worker behavior as a single cause, and does not justify removing the product preview.

All267 measured results use480×640 prepared inputs and the correct model; all are BLIND on the virtual room. Four original timestamped inputs and their full raw33-landmark records were inspected. The animated television patch differs between blocks, so equal dimensions do not establish identical input pixels. No exercising human, reference imitation label or human-form precision is measured. The four synchronous image/raw exports total about1.790s and remain included in observed delivery; no outlier or slow frame is removed.

The analyzer owner stays at thread priority−2. Callback-to-callback thread CPU clocks are supported in all blocks. Their summed CPU/wall ratios range23.5–32.7%; these describe the owner thread's intervening work, not native-worker CPU, total process CPU, or the CPU fraction of the inference interval. These records cannot prove that the remaining wall time is scheduling delay. All raw CPU readings, thread IDs and timestamps remain available for separate attribution work.

No measured native graph recreation, model mismatch, analyzer error, crash or ANR is observed. Continuity-reset increases are44/50/48/49; reference/pose evidence continuity is not silently repaired to conceal slow delivery. Maximum observed PSS ranges257.70–281.71MiB. Every capture clock remains UNKNOWN, so capture-to-result latency stays null. Separate45-second windows cannot be concatenated into ten continuous minutes or valid two-minute memory windows; both sustained-operation gates remain unearned.

Verification: **156 Python tests pass**, including four ablation binding/order, clock, short-window and CPU-scope cases. The isolated native test passes all four blocks (278.346s overall test time). It is not a rerun of the normal31-case native inventory. The2,531 production readiness receipts revalidate at50. No skeletal validation/final subjects, new annotation, pose-network training, thresholds, model selection or raw MP33 transformations were used.

The first preflight rebuild omitted the optional Heavy asset. Its APK/log are preserved, and it was never installed. A corrected rebuild has identical ZIP entry contents to the verified main APK but different whole-APK packaging bytes. The experiment therefore installed the original immutable verified APK, checked by exact SHA before installation. The research test APK and actual instrumentation transcript are preserved separately. No new user APK is delivered or substituted.

Keep this measured partial contention effect and the unresolved slowdown. Another unchanged preview ablation or ten-minute soak cannot fill the clock/throughput gaps. A future controlled investigation could pair exact captured-pixel input and worker/owner timing under different runtime conditions; it needs a new freeze and cannot borrow these results as native-inference CPU attribution. Independent reference-relative form discrimination and good-rep recall remain separate priorities. Human live-rep precision is still unknown.
