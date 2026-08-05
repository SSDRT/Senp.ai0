# Senp.ai0 Engineering Instructions

## Scope
- Build the native Android motion-analysis engine. No frontend, web UI, Compose screens, navigation, cloud coaching, or rendered-video product flow in Wave 1.
- `SSDRT/senp.ai` and `/home/coder/mcp_workspace/senp-ai-analysis` are read-only behavioral references. Do not commit changes there for this project.
- The real implementation repository is `SSDRT/Senp.ai0`.

## Architecture
- Timestamps are the source of truth; frame indices are derived only for diagnostics.
- All temporal thresholds use explicit time units, normally milliseconds.
- Core modules must remain pure Kotlin/JVM with no Android or MediaPipe types.
- Android video and pose implementations sit behind narrow interfaces.
- Use immutable data contracts, typed failures, one composition root, deterministic tests, and versioned cache/provenance formats.
- Keep all 33 MediaPipe landmarks, world/image coordinates, visibility, and presence. COCO-17 is only an adapter.
- Do not port the Python heuristic 3D lifter or unconditional bone-length enforcement.
- Preserve short-gap repair, confidence gating, quality hysteresis, phase-aware masked DTW, alignment confidence, and genuine-versus-uncertain windows.
- The main analysis path must not render MP4 output.

## Verification
- No phone is available. Use the Fedora `senp_api35` emulator for Android/runtime validation.
- Build/test success alone is insufficient for video work: inspect representative timestamped frames/contact sheets and diagnostic pose overlays.
- Real test corpus lives outside Git under `/home/coder/.local/share/senp-test-videos/drive-14DVra51GZZozAF-4uBwOuk75U0z-QwMO`.
- Do not commit large videos, model downloads without provenance, generated build files, or local SDK paths.

## Git controls
- Verify the repository with `gh repo view SSDRT/Senp.ai0` before work.
- Work only on the assigned branch/worktree.
- Keep changes cohesive, tested, committed, and reviewable.
- Never force-push or edit `main` directly from a child lane.
- Child agents must not push or open PRs; the lead reviews and publishes branches centrally.
- Do not invoke Codex, Antigravity/agy, or nested subagents.
