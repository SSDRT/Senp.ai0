# Build and toolchain

## Verified Fedora environment

The Wave 1 kernel project is pinned to:

- Gradle wrapper 8.13;
- Kotlin 2.0.21;
- JVM toolchain 21;
- kotlinx.coroutines 1.9.0;
- kotlinx.serialization 1.7.3.

It was verified on Fedora with Eclipse Temurin JDK 21.0.11. The installed Android SDK is `/home/coder/Android/Sdk` with API 35 and API 36 platforms available, but this lane contains only Kotlin/JVM modules and does not read a local SDK path or require `local.properties`.

## Commands

Run the complete clean verification surface:

```bash
./gradlew clean check
```

`check` includes every subproject check and the root `checkCoreBoundaries` task.

Run the deterministic headless engine and emit a human-inspectable JSON result:

```bash
./gradlew :headless-runner:run --quiet
```

Inspect dependency direction when integrating another lane:

```bash
./gradlew projects
./gradlew :core-contracts:dependencies
./gradlew :core-pipeline:dependencies
./gradlew :core-cache:dependencies
```

## Android runtime note

No Android application or instrumentation runner is declared in this lane, so emulator execution would not exercise additional behavior. Android-dependent adapters must be validated by their owning integration lane on the existing `senp_api35` emulator. Adding an Android module later must not move Android or MediaPipe types into the core modules.
