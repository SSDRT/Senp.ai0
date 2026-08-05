# Senp.ai0

Native, offline-first Android motion-analysis engine for side-by-side exercise comparison.

Wave 1 is engine-first: no frontend, Compose UI, web UI, cloud coaching, or rendered-MP4 product path. The existing Python implementation in `SSDRT/senp.ai` remains a read-only behavioral reference for smoothing, quality gating, phase-aware alignment, and problem-window classification.

## Current headless foundation

- `core-contracts`: immutable timestamp-first requests, configuration, 33-landmark pose data, motion/alignment/results, typed failures, timings, provenance, and versioned cache identity.
- `core-pipeline`: narrow decoder/pose/motion/phase/alignment/cache interfaces and injected cancellation-safe orchestration.
- `core-cache`: bounded pure-JVM cache implementation.
- `headless-runner`: deterministic fake adapters, end-to-end tests, and a human-inspectable JSON result.

```bash
./gradlew clean check
./gradlew :headless-runner:run --quiet
```

See [the architecture](docs/ARCHITECTURE.md), [integration contract](docs/INTEGRATION_CONTRACT.md), and [build/toolchain notes](docs/BUILD_AND_TOOLCHAIN.md).
