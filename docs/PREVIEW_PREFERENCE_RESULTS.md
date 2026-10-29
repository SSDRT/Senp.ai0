# Preview rendering preference verified on two runtimes

29 October2026. Implementation `b03e340` now prefers CameraX PERFORMANCE in both existing live camera activities, retaining the library's TextureView fallback. The preview, FIT_CENTER scaling, camera input, analysis resolution and model ownership remain present. No pose measurement, confidence, reference target, fault/range threshold or count rule changes. Compiler `/9`, raw pipeline `/1` and reference schema `/5` remain unchanged. Readiness is **50/100**, and human live-rep precision remains unknown.

The preceding frozen actual-camera ABBA comparison observed23.56% higher mean delivered results with PERFORMANCE on the API35 emulator. That diagnostic motivates this preference; it is **not a performance measurement of the newly packaged APK**, a human accuracy improvement or ten-minute qualification. No new performance points are credited here.

Candidate source and exact APKs were frozen before native execution in `c2f60c1`. The original31 native identities are retained. Existing three-frame/20-second camera deadlines remain unchanged, including Full push-up and reference-selected Heavy launch/pause/resume. Both camera activities set the implementation preference before their first surface-provider assignment. CameraX retains the platform/rotation fallback; no separate forced SurfaceView or rewritten camera capability is introduced.

| Verification | API35 | API36 |
|---|---:|---:|
| Adapter native cases |12/12|12/12|
| App native cases |19/19|19/19|
| Failures / errors / skipped native cases |0|0|
| Full push-up launch/resume actual preview |SurfaceView / SurfaceView|SurfaceView / SurfaceView|
| Selected Heavy reference launch/resume actual preview |SurfaceView / SurfaceView|SurfaceView / SurfaceView|
| Observed native page size |4KB|4KB|

Both runtimes are owned x86_64 emulators with fixed fingerprints and SwiftShader rendering. Eight original, separately timestamped display screenshots show visible, upright room previews and retained surrounding overlays before/after pause. These empty virtual-room images do not establish human skeleton-overlay alignment or rep correctness. Requested mode, actual view classes, stream state, dimensions, observation time and screen-export cost are archived. STREAMING alone is not treated as visible pixels. The automatic TextureView fallback is delegated to CameraX and permitted by the checks; no legacy-camera fallback was observed or claimed in these two runs.

Two native static-input replay contact sheets and their24-row original raw/filtered outputs per runtime were inspected. They retain the upstream fixture and explicit synthetic translations, blank frames and generated VIDEO timestamps. Positive/blank/native parity checks pass. These are mechanism checks, not recorded exercise-video ground truth, human precision or real-camera performance.

The first API35 attempt ended **before any native case**: Windows PowerShell treated an early disconnected-adb message as a terminating error. Its logs, original protocol and stopped-process receipt are preserved. The `804893f` harness amendment waits for an observed adb connection before reading boot properties, retaining the original240-second boot deadline. A fresh API35 directory then passes the entire inventory. This repairs test startup only; no app deadline or evidence gate was relaxed. Both owned emulators stopped after export.

**305 scoped JVM cases** pass without failures/errors/skips:255 core-motion,14 adapter and36 app. The core count includes the two existing research projection cases; their presence earns no additional score. **175 Python tests**, all2,688 frozen generated controls and all124 controlled corruptions pass. The original31 accepted corruption targets remain. Manual AndroidJUnitRunner transcripts are checked against exact frozen identities, with a matching start/success for each case and successful complete runner termination; missing, duplicated, failed and skipped cases are refused.

Every production source outside the two activity preferences is unchanged from `872c9f7`; their diffs contain only the mode and explanatory comment. Both packaged Full/Heavy model assets are byte-identical to the previous verified APK. Existing complete reference coverage12/32, capped11/32, all31 complete selected qualified intervals, camera breadth and independent skeletal results are inherited through the unchanged extraction/comparison/selection implementation. Their2,531 immutable evidence receipts revalidate; no reference or independent-label replay was rerun or counted as a new result. Final subjects08-10 remain sealed, with no new annotation or source labels entering inference.

Result collection stopped first on the departure-control report's existing `scenarios` key, then on the ZIP tool's existing `Verification succesful` spelling. Both partial archives, original collectors and error receipts remain preserved. The verified collector uses each report's explicit existing key, checks the exact original row counts and recognizes the tool's actual successful output. The ZIP command itself returned zero; its verbose retry adds reviewable output. No native/JVM test was rerun, result changed or denominator relaxed for these export repairs.

The new debug APK passes signature, ZIP alignment and static16KB ELF/ZIP checks and includes both pinned models. It is archived with its exact source/hash and available locally under `outputs/live-pose-preview-preference-20261029/Senpai-preview-preference-debug.apk` in the task workspace. A physical handset, actual16KB runtime, sustained camera latency/FPS and human form precision remain unverified. The frozen rubric and completion gates are unchanged, so **70 has not been reached**.

Use `models/preview-preference.provenance.json`, `models/preview-preference-readiness.evidence.json` and `models/preview-preference-readiness.score.json` for this checkpoint. Retain the earlier failed/research experiments and their limitations. Next work must address independent-label reference generalization/form evidence or defensible measured camera throughput; this rendering preference alone cannot satisfy those gates.
