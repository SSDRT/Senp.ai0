# Codex frame review

Sends selected analysis frames plus a system prompt to a reasoning model and returns coaching text.
The provider is the Codex Responses endpoint reached with a ChatGPT sign-in, so there is no Codex CLI
binary, no local proxy process, and no API key in the app.

## Layout

- `core-review` — pure Kotlin/JVM. Contracts, model and reasoning selection, request body, SSE
  folding. No networking types, so it stays inside `checkCoreBoundaries` and is testable headlessly.
- `android-codex` — OAuth sign-in, token storage and refresh, `HttpURLConnection` transport, and
  `Bitmap` → JPEG → base64 frame encoding.

`core-review` deliberately depends on nothing in the frozen pipeline contracts. Review is a
post-analysis concern; the caller formats whatever context it wants into `userContext`.

## Usage

```kotlin
val auth = CodexAuth(context)
if (!auth.isSignedIn) auth.signIn()

val reviewer = CodexFrameReviewer(AndroidCodexTransport(auth))
val outcome = reviewer.review(
    FrameReviewRequest(
        systemPrompt = "You are a strength coach. Name one fault and one correction.",
        userContext = "Back squat. Source on the left, reference on the right.",
        frames = keyframes.map { ReviewFrames.encode(it.bitmap, it.label, it.timestampMs) },
        model = ReviewModel(effort = ReasoningEffort.HIGH, imageDetail = ImageDetail.LOW),
    ),
)
```

## Tuning

`ReasoningEffort` and `ImageDetail` are the two knobs that matter and neither has a correct default.
Effort trades answer quality against latency and against the signed-in account's plan limits;
`ImageDetail.LOW` caps per-frame token cost and is usually enough for joint positions. Frame count
multiplies both. Measure on real clips at the device's actual capture resolution — the defaults
(`MEDIUM`, `AUTO`, 768 px, JPEG 70) are a starting point.

Effort ranges are model-dependent: `gpt-5.4-codex` accepts `none` through `xhigh`, `gpt-5.3-codex`
only `low` through `xhigh`. `ReviewModel` rejects an unsupported combination at construction.

## Implementation notes

- The endpoint answers with SSE and **no `Content-Type` header**, so off-the-shelf event-source
  clients reject the response. The transport reads raw lines; `Codex.parse` folds them.
- `store` is pinned false and `stream` pinned true — the endpoint only streams, and retaining a
  copy of a user's workout frames is not the app's call to make.
- Sign-in uses a loopback redirect on port 1455 (RFC 8252), which works on Android because the
  browser and the app share the device.
- Access tokens expire in hours and refresh happens behind a mutex, so concurrent reviews cannot
  race each other into an invalidated refresh token.

## Verification

```bash
./gradlew :core-review:test checkCoreBoundaries
```

`android-codex` needs the Android SDK and is covered by the emulator run, like the other `android-*`
modules.

## Scope and account limits

Wave 1 excludes cloud coaching; this module is additive and nothing in the analysis path calls it.
Each user signs in with their own ChatGPT account, so no token is shared, but the tokens are scoped
to Codex clients and the calls consume that account's plan quota. For a shipped consumer build,
replace `AndroidCodexTransport` with one pointing at a first-party backend holding a platform API
key — `core-review` does not change.
