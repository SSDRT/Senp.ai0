# Wave 1: Headless Engine Foundation

Wave 1 is split into five parallel lanes:

1. Kernel and infrastructure: contracts, orchestration, cache, provenance, typed failures, Gradle structure.
2. Android video and MediaPipe: sequential decoding, timestamp sampling, 33-landmark VIDEO-mode inference.
3. Motion core: time-aware smoothing, gap repair, quality, normalization, angles, features.
4. Phase and alignment: phase/rep detection, masked DTW, confidence, genuine/uncertain windows.
5. QA and benchmark: real corpus manifests, visual frame inspection, emulator harness, regression and timing reports.

The acceptance surface is the Fedora Android 15/API 35 emulator. A phone is not part of development or verification.
