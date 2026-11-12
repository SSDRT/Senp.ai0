# Live pose engine tuning and implementation plan

Branch: `codex/live-pose-accuracy`; base: `74aa4d5`; scope: live Android pose inference and camera plumbing only.

## Baseline and decisions

The current app uses CameraX KEEP_ONLY_LATEST and synchronous MediaPipe VIDEO tracking on one analyzer executor. Keep that bounded ownership model: an asynchronous API alone does not increase accuracy. Retain all 33 anatomical landmark IDs, image/world coordinates, visibility, presence, and real capture timestamps. Preserve raw inference separately from filtered output. Do not retrain a network, fabricate missing joints, force bone lengths, change action recognition, or change offline analysis.

Use pinned MediaPipe Tasks Vision 0.10.32 / Pose Landmarker Full v1 (Apache-2.0) and CameraX 1.5.2. Full/CPU remains the compatibility baseline. GPU is an explicit opt-in with initialization and inference fallback to CPU, with delegate diagnostics and single-thread ownership. Model changes require separate labelled evidence; downloading a larger network does not establish accuracy.

Sources reviewed 2026-10-22:

- [MediaPipe Android pose guide](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker/android)
- [Google's Android pose sample](https://github.com/google-ai-edge/mediapipe-samples/tree/main/examples/pose_landmarker/android)
- [1 Euro filter algorithm and original paper reference](https://github.com/casiez/OneEuroFilter)
- [CameraX analysis and backpressure](https://developer.android.com/media/camera/camerax/analyze)

## Execution steps and acceptance checks

1. **Set up and baseline.** Clone into `D:\projects\senpai`, verify repository identity, create branch, locate Java 17/21 and SDK, run existing tests before changes. Use the available Windows emulator because the Fedora `senp_api35` host mentioned in AGENTS.md is not present here. Record exact commands and failures.
2. **Capture correctness.** Centralize live frame preparation and scheduling for both camera paths. Drop duplicate/backward capture timestamps; never invent time. Respect right-angle rotation, aspect ratio and RGBA row padding through CameraX conversion. Bound analysis resolution; prefer back camera and fall back to any available camera; mirror only front-camera display coordinates. Keep capture/inference on a single executor and close every image/bitmap.
3. **Conservative quality and adaptive filtering.** Add a pure JVM causal 1 Euro stabilizer, using elapsed seconds, separate image/world units, per-joint confidence resets and whole-frame reset on missing people, geometry change and long gaps. Gate on visible in-frame body joints and torso anchors so twelve face/hand points cannot validate a missing body. Preserve raw coordinates and confidence; never carry old points through occlusion. Check jitter, moving-target error, reacquisition, missing confidence, irregular FPS, long gaps and dimension changes with deterministic data.
4. **Tracker recovery and compatibility.** Recreate temporal tracking after long gaps or input geometry changes. Add explicit CPU/GPU selection and fallback diagnostics. Cap accepted inference cadence without accumulating a queue; keep default camera timestamps. Avoid claims of universal hardware support; retain the existing Android API 26 minimum and inspect shipped native ABIs/16 KB compatibility. No iOS adapter exists.
5. **Artifact integrity.** Verify downloaded model size AND SHA-256 from provenance before packaging. Use a temporary download and atomic replacement so interrupted or corrupted files cannot become accepted assets. Verify a same-size corruption is detected.
6. **Automated validation.** Run the full JVM suite, core boundaries, Android unit tests/lint/build, and native instrumentation on the available emulator. Add real-model tests for no person, positive pose, monotonic time, reset, close and model mismatch. Capture timestamped input/pose overlays, raw/filtered outputs and latency diagnostics. Use upstream fixtures with pinned hashes and provenance; do not mistake upstream expected model outputs for independently annotated human truth.
7. **Reproducible tuning evidence.** Run a deterministic parameter sweep against known synthetic truth, report static jitter and dynamic RMS error at multiple FPS, and retain a reproducible report task. Compare raw, fixed low-pass and adaptive settings. Keep quality/latency tradeoffs explicit. Record real-model fixture checks separately from synthetic filter gains.
8. **Review and delivery.** Review the diff, commit cohesive changes locally, document setup/run commands, exact passing checks, evidence locations and outstanding device gates. Leave a clean branch and runnable debug APK if build succeeds.

## Real-world release gates (cannot be substituted by synthetic tests)

Before claiming improved real-world pose accuracy, evaluate an independently labelled, subject-disjoint exercise corpus: front/side/back views, portrait/landscape, partial occlusion, full disappearance/re-entry, fast movement, low light, loose clothing, varied body proportions/skin tones and multiple people. Split tuning/holdout by subject and video. Measure visible-joint PCK@0.05/0.1 (torso-normalized), angle MAE, false-valid rate, tracking-loss/reacquisition time, temporal jitter, p50/p95 capture-to-result latency, dropped frames, peak memory and sustained thermal performance. Report coverage alongside error so rejecting hard frames cannot inflate accuracy.

Suggested promotion gates relative to the same raw Full/CPU baseline: no >1 percentage point PCK regression on any declared subgroup, >=20% static jitter reduction, <=1 additional accepted frame of dynamic lag, no stale valid output after disappearance, p95 result age <=150 ms on the supported reference device tier, and stable 15-minute operation. These are target gates, not results or guarantees.

Physical-device matrix: API 26/29/33/35+, low-memory Android Go, arm64 Qualcomm/Adreno, MediaTek/Mali, Exynos, front-only tablet, foldable rotation, CPU-only and GPU failure, camera permission denial/revocation, pause/resume, and 16 KB-page Android. Record ABI/OS/model/delegate for every run. Emulator tests cover only that emulator. iOS needs a separate Swift/MediaPipe adapter plus equivalent lifecycle/camera/device tests and is outside this Android engine branch.

## Execution outcome

Implementation and available-host verification are complete; see `LIVE_POSE_VALIDATION.md` for commands, evidence and limitations. The sweep selected an optional 3 Hz / image-beta 80 candidate. Additional smoothing remains disabled in the default live adapter because the available evidence does not justify applying another filter over MediaPipe's native VIDEO smoothing. Capture admission, body quality gating, ownership fixes, tracker resets and CPU compatibility behavior are enabled. The independent labelled-corpus and physical-device release gates above remain open; no real-world accuracy percentage or universal-mobile claim is made.
