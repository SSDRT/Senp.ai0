# Live pose implementation and validation — 2026-10-22

Latest verified engine checkpoint (28 October 2026): compiler `/8`, implementation `6d1386b`, adds the confirmed-clear preparation guard. All 292 scoped JVM and the same 30 native cases on each of APIs 35/36 pass, alongside 124 Python checks, 2,688 controls and 124 corruptions. Existing references and qualified camera attempts remain intact. Readiness is 50/100; this is not a measured human-rep accuracy increase. See `PREPARATION_FAULT_RESEARCH.md` and `models/preparation-fault-readiness.evidence.json`; earlier dated sections retain their original results.

The project is set up in `D:\projects\senpai` on `codex/live-pose-accuracy`, based on production commit `74aa4d5`. Changes are local to this branch. No remote branch was pushed and no PR was opened.

## Implemented behavior

- Both live camera paths share capture admission and bitmap preparation. Duplicate, backward, sub-millisecond duplicate and cadence-exceeding frames are dropped before conversion. Accepted timestamps remain capture timestamps. At most one frame is inferred at a time; CameraX retains only the latest waiting frame.
- Camera analysis requests 640×480 with supported-resolution fallback. Preparation caps the long edge at 640 pixels, preserves aspect ratio, applies camera rotation, and delegates vendor row/pixel-stride handling to CameraX `toBitmap`. Preview and analysis use the same aspect strategy and explicit display rotation. Back camera is preferred; front camera is available as fallback with display-only mirroring. Anatomical IDs are preserved.
- Live quality checks require at least six confident, in-frame major body joints and two torso anchors. Face/hand confidence alone cannot validate an absent body. Missing confidence stays missing. Blind frames retain their current observations and never borrow old reliable coordinates.
- A causal, timestamp-driven 1 Euro filter is implemented in pure Kotlin/JVM, with independent image/world units, aspect-correct image coordinates, per-joint confidence resets, and reset on missing people, long gaps or geometry changes. `estimateDetailed` preserves raw output alongside the candidate output, delegate, timing, fallback and reset diagnostics.
- Additional smoothing is **off by default** in the live adapter. MediaPipe VIDEO already applies temporal processing; the optional candidate uses 3 Hz minimum cutoff, image beta 80, world beta 5 and 1 Hz derivative cutoff. Full/CPU and the original 0.5 detection/presence/tracking thresholds remain the baseline until independent labels support a different choice.
- The native tracker is recreated after capture gaps over 500 ms or changed input dimensions. The first result after recreation carries a continuity break so consumers cannot score a seamless transition across unrelated tracks.
- GPU is explicit opt-in, with initialization/inference CPU fallback and diagnostic reasons. Creation, inference and close must use the same analyzer thread. Activity teardown queues close on that executor, clears the analyzer, unbinds the camera and drains cleanup. Native runtime load failures are typed.
- MediaPipe Tasks 0.10.32 recycles a bitmap wrapped by a closed MPImage. The live adapter now gives MediaPipe a temporary owned bitmap, preserving the caller's bitmap for inspection and CPU retry. A native regression test verifies this ownership contract.
- The existing push-up live activity is registered in the manifest, fixing its previously unresolvable launch from the validation app. Action recognizers, repetition rules, offline analysis and the video editor behavior were not changed.
- Model preparation now validates exact size and SHA-256 even for existing same-size assets. Downloads are verified in a temporary file and atomically replaced. Upstream native-test assets have recorded hashes and provenance; binaries are excluded from Git.
- Gradle is pinned to 8.14.3 with its official distribution checksum. The original 8.13 download repeatedly timed out; the tested 8.14.3 runtime was already available. Hashed JSON fixtures now keep LF bytes on Windows, fixing an existing provenance-test failure caused by `core.autocrlf=true`.
- The validation-app instrumentation runner is ordered after pose-library instrumentation to prevent two suites from stealing activity/camera focus on the same emulator.

## Verification results

| Check | Result |
|---|---|
| Pure core, integration and runner JVM checks; Android debug/release unit tests | 300 test executions, zero failures, zero skipped |
| Core dependency/import boundaries | Pass |
| Motion deterministic artifacts and synthetic microbenchmark | Pass |
| Pose/video/Codex adapter lint | Pass; pose lint reports no issues |
| Native pose instrumentation | 9/9 pass: packaged model integrity, missing/mismatched model, blank and positive pose, canonical coordinates/confidence/world values, timestamps, ownership, owner thread, close, geometry/gap reset, delegate fallback and replay evidence |
| Live CameraX launch / pause / resume / teardown | 1/1 pass on the available emulator |
| Python QA suite | 28/28 pass in Ubuntu 24.04 WSL, including three new accuracy-scorer tests |
| APK assembly | Pass, debug validation APK produced |
| Native ABI inspection | arm64-v8a, armeabi-v7a, x86 and x86_64 present |
| 64-bit ELF load segments and APK native-library ZIP alignment for 16 KB | Pass, also checked with Android `zipalign -c -P 16 4` |
| Deliberate same-size model corruption | Detected; preparation re-fetched and restored pinned SHA-256 |
| Root `check` | Blocked by one existing video-editor Compose lint error, described below |

The native host is the Windows `Medium_Phone_API_36.1` AVD, which reports Android API 36, x86_64, 4,096-byte pages, and SwiftShader OpenGL ES 3.0. The GPU test encountered the native OpenGL ES 3.1 requirement and successfully exercised CPU fallback. This does not characterize physical GPU performance.

The existing Python harness's executable fake shell scripts produce WinError 193 under native Windows Python. The complete suite passes under WSL; the three new scorer tests also pass under native Windows Python. The external Fedora 21-video corpus is absent from this Windows checkout. Both available remote terminal connectors returned HTTP 404, so that host/corpus could not be used.

`check` reaches a pre-existing `UnusedBoxWithConstraintsScope` error in `validation-app/src/main/kotlin/ai/senp/validation/ui/screens/VideoEditorScreen.kt:608`. That file is unchanged on this branch. The full app lint report also contains 51 warnings. The engine-specific check set passes; no lint baseline or suppression was introduced to conceal this failure.

An initial combined instrumentation run failed when the camera activity lost focus to the simultaneous library runner. Adding runner ordering and rerunning sequentially resolved this. A first native replay also exposed the bitmap ownership defect described above; the corrected implementation passes the replay and caller-ownership checks.

## Tuning evidence and interpretation

`core-motion:livePoseBenchmark` exercises the production filter against a deterministic synthetic trajectory at 10, 15, 30 and 60 FPS, each with regular and irregular timestamps. It uses 12-second runs, seed 20261022, ±0.02 normalized-coordinate noise and a 0.8 Hz motion signal. It compares raw data and twelve cutoff/beta combinations (104 rows total).

| Profile | Static jitter RMSE reduction across cases | Moving RMSE / raw RMSE across cases |
|---|---:|---:|
| Fixed 3 Hz, beta 0 | 27.6–65.9% | 2.60–2.87 |
| Adaptive 3 Hz, beta 20 | 21.9–57.5% | 0.98–1.26 |
| Optional candidate: 3 Hz, beta 80 | 13.2–41.8% | 0.84–1.05 |

The stronger static reduction comes with larger moving error. The chosen optional candidate bounds this synthetic tradeoff, but even it has a worst-case 5.2% moving-RMSE increase. It does not pass every proposed release gate, particularly the low-FPS static-reduction target. World-coordinate beta is configurable and has reset/unit coverage, but is not independently optimized by this 2D sweep. These are filter tests against synthetic truth, **not measurements of human pose accuracy**.

The native replay uses 24 timestamped, slightly translated versions of the upstream pose image, including three all-black frames. All 21 positive frames were valid and all three blank frames were blind; the first returning frame reacquired the person. Timestamped raw/red and optional-filter/green overlays were inspected as a contact sheet: the skeleton follows the visible body and disappears on blank frames. The replay JSON includes raw and filtered landmarks plus inference latency. These are upstream model regression/replay checks, not an independently labelled exercise video.

The final warmed replay measured 28.1 ms p50 and 48.2 ms p95 inference on this emulator, with a 132.5 ms first inference. An earlier run under different host load measured 60.3/88.0 ms. Both reports are supplied to show the variability; this is not a controlled phone-performance benchmark. These timings include input ownership copying and native inference; they exclude camera exposure, camera queueing, preparation, mapping, UI scheduling, thermal endurance and real-phone behavior.

The new `qa/senpqa/live_accuracy.py` scorer supports independent MP33 image-coordinate annotations, exact capture timestamps, torso-normalized PCK@0.05/0.1, joint coverage, absent-person false-valid rate, and elbow/knee angle MAE. Missing predictions/low-confidence visible joints remain PCK failures. Ground-truth attribution and torso scale are required; duplicate timestamps/IDs are rejected. The scorer does not turn upstream expected predictions into human labels.

## Run and reproduce

On this machine, `run-local.ps1` is an ignored convenience launcher with the installed Java 21 and SDK locations. It is also supplied as `Run-Senpai.ps1` alongside the deliverables. It sets process-local environment variables; no global Java or SDK settings are changed.

```powershell
cd D:\projects\senpai
.\run-local.ps1 :validation-app:assembleDebug
.\run-local.ps1 :android-pose-mediapipe:check :core-motion:verifyMotionCore checkCoreBoundaries
.\run-local.ps1 :core-motion:livePoseBenchmark
.\run-local.ps1 :android-pose-mediapipe:connectedDebugAndroidTest :validation-app:connectedDebugAndroidTest --no-parallel
python scripts/verify_apk_compatibility.py validation-app/build/outputs/apk/debug/validation-app-debug.apk --output test-artifacts/apk-compatibility.json
```

Run Python QA on Windows through the installed WSL distribution:

```powershell
wsl -d Ubuntu-24.04 -- bash -lc 'cd /mnt/d/projects/senpai && PYTHONPATH=qa python3 -m unittest discover -s qa/tests -v'
```

Run independent-label scoring when that corpus is available:

```bash
PYTHONPATH=qa python -m senpqa.live_accuracy --truth labels.json --predictions replay.json --output test-artifacts/accuracy.json
```

The scorer's module docstring defines the input schema. Labels use MP33 IDs, normalized xy, explicit visibility, frame `person_present`, independently measured `torso_length_px`, and image width/height. Evaluate per subject/view/device subgroup as well as aggregate metrics. Use a held-out subject split before promoting extra smoothing or changing model/threshold defaults.

Generated repository evidence stays ignored under `test-artifacts/`, `build/`, and module build directories. The delivered evidence archive contains test XML, successful/failed check logs, the full synthetic sweep, compatibility report, native replay and overlays. The APK is a debug **validation harness**, not a production release.

## Compatibility and remaining release gates

The implementation supports the existing **Android API 26+** build target and packaged ABIs, with CPU as default. Static 16 KB alignment passes; execution on a 16 KB Android runtime is still unverified. No iOS implementation exists in this repository. Phones below API 26, particular camera/driver combinations, every mobile GPU and sustained thermal behavior are not certified by these results.

The detailed physical-device and independently labelled-corpus matrix remains in `LIVE_POSE_TUNING_PLAN.md`. Real-world PCK, angle error, low-light/occlusion accuracy, subgroup fairness, multi-person identity continuity, capture-to-result latency, memory/thermal endurance and universal mobile compatibility remain unproven. No model weights were fine-tuned and no real-world accuracy percentage is claimed. The implemented branch provides the corrected live path, optional tuned filter, measurement tools and reproducible available-host verification needed for that next evidence-based promotion.
