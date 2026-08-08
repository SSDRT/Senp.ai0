# Live Push-up Arena

The live push-up arena is an additive Android path for immediate on-device feedback. It does not replace or modify the existing source-vs-reference video analysis pipeline.

## Architecture

```text
CameraX Preview + ImageAnalysis (KEEP_ONLY_LATEST)
                |
                v
LiveMediaPipePoseEstimator
                |
                | canonical PoseFrame: 33 landmarks,
                | image/world coordinates, visibility, presence
                v
PushUpLiveEvaluator (pure Kotlin/JVM)
                |
                v
LivePushUpActivity + pose overlay + cue/repetition UI
```

The camera analyzer has a single worker and `STRATEGY_KEEP_ONLY_LATEST`, so stale frames are discarded instead of queued behind pose inference. The MediaPipe adapter deliberately uses synchronous `VIDEO` mode on that worker: every accepted bitmap has a deterministic ownership lifetime while MediaPipe temporal tracking still receives strictly increasing timestamps. Timestamps remain monotonic and are the state-machine source of truth.

Camera bitmaps are processed in memory and recycled after inference. The live path does not record video, write camera frames to disk, or upload them.

## Form contract

Live form scoring is intentionally enabled only for `pushup`. Other exercise profiles and the Wave 5 comparison engine remain unchanged.

A correct repetition requires:

1. A usable full-body pose with adequate visibility/presence.
2. A sufficiently side-on camera view. Side view is preferred because elbow depth and shoulder-hip-ankle body alignment are observable with less projection ambiguity.
3. A stable straight-arm start position.
4. Descent below the configured elbow-angle depth threshold, held briefly to reject threshold noise.
5. Shoulder-hip-ankle alignment without a persistent sag/pike violation.
6. Return to full arm extension within the allowed repetition duration.

The evaluator uses hysteresis (`topEnter/topExit`, `bottomEnter/bottomExit`) and timestamp dwell windows rather than frame counts. Shallow attempts and attempts with persistent body-line violations increment `rejectedAttempts` but never `correctReps`.

## UI

`LivePushUpActivity` is a separate activity inside `validation-app`. The existing `ValidationActivity` remains the launcher and continues to drive the Wave 5 emulator E2E checks.

When installed, start the arena explicitly:

```bash
adb shell am start -n ai.senp.validation/.LivePushUpActivity
```

The surface shows:

- live camera preview;
- canonical MediaPipe skeleton overlay;
- large valid-rep counter;
- rejected-attempt counter;
- live phase;
- one actionable form cue;
- elbow angle, body-line angle, and tracking confidence;
- a 52dp reset control.

## Model

The app continues to use the repository-pinned MediaPipe Pose Landmarker Full model. Fetch and verify it before building the runtime APK:

```bash
./scripts/fetch_pose_model.sh
```

The script validates both SHA-256 and byte count. The `.task` file is ignored by Git and is never committed.

## Reference implementations

The implementation is native to Senp.ai0. The external push-up counter projects were used only as behavioral/UX references:

- Johannes0Horn/Push-up-counter-app demonstrates landmark-based repetition counting, but its Android/toolchain architecture is old and is not transplanted.
- durareApp/android demonstrates a simple camera/state counter, but its face-size threshold approach cannot validate elbow depth or body-line form and is not used for scoring.

The Senp.ai implementation instead builds on the existing canonical 33-landmark contracts and motion-engine guardrails.
