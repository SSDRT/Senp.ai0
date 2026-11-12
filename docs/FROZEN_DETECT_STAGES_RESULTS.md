# Native-task body attribution under camera contention

29 October2026. Frozen experiment `f6a46ea` separates the bitmap/container work from the native task call inside the previous broad inference interval. The **native API call itself remains16.20–17.84× slower with CameraX active** on this emulator. Copy/container costs also rise, but do not explain most of the observed body duration. No optimization or model change is promoted. Readiness remains **50/100**, and human live-rep precision remains unknown.

## Scope and method

The installed main APK is byte-identical to the verified preview-preference delivery `b03e340`, compiler `/9`, SHA256 `888025d7c0b958704b1bfef37b4def36eb170324468058c1f6135592c30083d2`. The research test uses the same production estimator factory, reference-selected Full asset/options and owner priority−2. It accesses that fresh estimator's native graph on its owner thread, invokes only the partitioned native body, then closes the estimator on the owner. No mapped estimator update follows the direct calls. The camera controller's unused graph closes before measured sampling, so exactly one graph infers per condition.

For each of the two previously frozen inputs, the fixed order is quiet / camera active / camera active / quiet. Each fresh graph receives32 calls with generated67ms model timestamps: eight warmup and24 measured. The same prepared pixel hashes/dimensions are required across all four blocks, and original pixels remain unchanged. These static model timestamps are separate from actual camera-source timestamps. Model inputs are **not** the concurrent camera frames; this experiment cannot measure camera-input accuracy, capture latency or delivered liveFPS.

`System.nanoTime` boundaries partition the body into ARGB bitmap copy, MPImage build, `PoseLandmarker.detectForVideo`, MPImage close and explicit recycle/check. Every row's five intervals sum exactly to the containing interval. Owner CPU excludes native workers; process CPU includes all other application work. The native API interval includes packet/native graph work and scheduling, not just isolated neural operators. No per-call procfs census runs here.

## Original measurements

All256 rows, including192 measured calls, are retained. Below are per-block means in milliseconds; build and explicit-recycle intervals remain in the original records and body total.

|Input / block|Camera|Copy|Native API call|MPImage close|Complete body|
|---|---|---:|---:|---:|---:|
|Room1|Quiet|0.763|17.459|0.360|18.644|
|Room2|Active|46.538|300.215|23.523|373.106|
|Room3|Active|25.301|276.606|6.363|314.040|
|Room4|Quiet|0.634|14.873|0.297|15.851|
|Positive1|Quiet|0.636|19.083|0.450|20.223|
|Positive2|Active|34.215|483.494|25.572|543.553|
|Positive3|Active|17.919|233.304|9.020|260.529|
|Positive4|Quiet|0.656|25.164|0.431|26.302|

Active/quiet ratios of the two block means are17.84×/16.20× for the native call and19.92×/17.28× for the complete body, room/positive respectively. The native call accounts for80.46–89.55% of active body time. Removing a defensive bitmap copy would therefore leave the largest observed interval and would change ownership safety; this result does not justify that change. The differences between repeats also show substantial host variation. Do not compare these ratios with a prior experiment as an app speed improvement: the measured boundary and current preview preference differ.

Eight original static-input/native-result pairs were inspected with all normalized/world coordinates and optional native confidence fields preserved. Room snapshots contain no native pose; positive snapshots contain one complete33-point pose. Each input's four snapshot pose arrays are exactly equal. This is an execution-consistency control, not correct human form or rep precision. Four separately saved original camera frames, with their own capture timestamps, were inspected without attaching static-input predictions to them. The virtual room supplies no human-form labels. Snapshot export work occurs between timed calls and can affect surrounding scheduling; every call is retained without correction or outlier filtering. Capture clocks are not verified by this experiment.

## Verification, noise and limitations

The isolated native case passes in117.216s on the owned API35/x86_64/4KB runtime. Strict runner start/success inventory, APK hashes, actual runtime fingerprint and owned-emulator shutdown verify. All **225 Python tests pass**, including five new partition/domain regressions. All **2,889 production receipts** and the unchanged rubric revalidate at50/100. The normal31-case native suites and JVM suites are not rerun for this research-only change. No handset, actual16KB runtime, ten-minute qualification, original RGB form annotation, holdout access, new human label, delivery APK or trained network is claimed.

The first research build failed because the app test compile classpath did not expose the adapter's MediaPipe runtime API. A research-only init script exposes the existing exact0.10.32 API to `androidTest`; the subsequent build succeeds without changing the main APK. Both logs are retained.

The full log also contains **two emulator Bluetooth SIGABRTs**, PIDs1601/1918; the test/app runner PID1837 completes. Those system failures and crash-dump work remain in the record and observed scheduling. This is not an otherwise crash-free device run or a proof that CameraX alone caused every delay. No app crash/ANR appears in the retained completed test evidence. The owned emulator and idle Gradle daemon are stopped after collection.

Preserve this completed partition rather than rerunning it unchanged. A useful next runtime step is to audit the pinned CameraX conversion implementation, then prospectively compare a compatible input-format/conversion alternative only if pixel/rotation/ownership integrity can be verified. Do not remove the preview, change model fidelity, relabel unknown clocks or weaken evidence gates to obtain a speed score. Independent form discrimination and good-rep recall still require a separate qualifying improvement. See `models/frozen-detect-stages.provenance.json` for immutable receipts.
