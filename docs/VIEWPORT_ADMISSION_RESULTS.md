# Verified qualified-rep viewport guard

28 October2026. Promoted implementation **`872c9f7`**, test-source checkpoint **`bd95301`**, compiler **`qualified-rep-compiler/9`**. The qualified-rep reference/live path now withholds world-coordinate landmarks whose paired same-frame, same-subject normalized image projection is unavailable or outside inclusive[0,1]. Raw MP33 coordinates, visibility/presence/confidence, model weights and source timestamps remain unchanged. The comparison copy carries missing values/masks; no clipped, repaired or invented joint is substituted. Image fallback cannot qualify a typed3D repetition. Compiled reference keys change deliberately; raw extraction pipeline `/1` and reference schema `/5` stay unchanged.

The change prevents a constructed high-confidence offscreen wrist from certifying a curl and withdraws an actual capped-reference press count that relied on a clipped wrist. It does not establish that the person's form was incorrect. The visible generic-pose input and learned-quality observation paths remain unchanged; reference profiles produced by qualified compilation rebuild from admitted evidence. Offline `legacy_raw` replay diagnostics are not a generic-product behavior guarantee or an accuracy metric.

| Fixed comparison | Before | Verified guard |
|---|---:|---:|
| Complete usable reference sides |12/32|12/32|
| Complete selected-backend qualified outputs |31|31, same intervals/verdicts|
| Capped usable reference sides |10/32|11/32|
| Capped selected-backend qualified outputs |18|13|
| Capped rejected / unassessable attempts |46 /167|52 /175|
| Fixed engineering readiness |50/100|50/100|

Capped coverage costs and backend changes are explained in [the correctness review](VIEWPORT_ADMISSION_REVIEW.md), including the Full LEFT press interval5333–6733ms with visibility0.61857 below the unchanged0.90 requirement. No existing selected reference side is lost. Full→Heavy selection for one capped reference follows reference evidence alone. The extra capped side does not change the fixed complete-reference score. Whole-clip good/bad metadata cannot turn these output changes into measured human precision or recall.

All eight Full/Heavy × ordinary/bounded × capped/complete camera configurations were replayed. There are188 common traces with original timestamps preserved, eight newly available traces and four withheld Full traces. The guard withdraws282 comparison-angle values across these overlapping traces; it rewrites no eligible numeric angle. All31 complete selected qualified intervals stay unchanged. Ten additional original RGB timestamps and20 raw model overlays were inspected, alongside the earlier reference/source reviews. All46 changed event/fault/status records remain in the comparison report.

Verification passed:

- **303 scoped JVM tests**:253 core,14 pose-adapter,36 app; zero failures/errors/skips.
- **31 native cases on each API35 and API36**:12 adapter and19 app, including the new clipped-wrist guard; both task-owned emulators stopped cleanly.
- **2,688 frozen mechanism controls**, **124 controlled feature corruptions**, and **134 Python tests**; zero failures. These are engineering controls, not human-form accuracy labels.
- Existing MM-Fit lateral-raise reference ready in all four trials; independent development160 and previously-opened validation120 episode verdicts unchanged. Final subjects08–10 remain sealed.
- Signed/aligned APK, pinned Full/Heavy model hashes, compiler9 marker and static16KB packaging checks. Both actual runtimes are x86_64/4KB emulators; physical-phone and16KB runtime checks remain unperformed.

Initial preflight failures (a test field name and an invalid duplicate-channel fixture) were corrected before implementation freeze and retained. Native parity targets are versioned from frozen JVM results; raw native fixtures and earlier `/5` and `/8` expectations remain intact, with all event/path/timing assertions retained.

`models/viewport-admission.provenance.json` binds896 new receipts; `models/viewport-admission-readiness.evidence.json` revalidates2,531 total receipts. The fixed rubric reports **50/100**, not70. Human live-rep precision remains unknown; unchanged skeletal selection precision is53.45% on development and65% on already-opened validation, with recall/coverage below the independent gate. Those results do not measure MediaPipe camera accuracy.

Fresh experimental APK: `C:/Users/sk/Documents/Codex/2026-10-22/d/outputs/live-pose-viewport-guard-20261028/Senpai-viewport-guard-debug.apk` (103,835,481bytes), SHA256 `57a4d201d61a7466869d8ddf9e4ff8d6ef2a83650176c780af467cc33b874a58`. No push, deployment or release qualification occurred. The remaining milestone work is independent form discrimination/recall and validated sustained operation; another generator sweep or the extra capped reference cannot substitute for those gates.
