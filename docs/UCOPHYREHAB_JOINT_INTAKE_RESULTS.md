# Single-record joint intake and coordinate conformance

29 October2026. Licensed data acquisition and a guarded joint-schema adapter now work. **No model is tuned or promoted by this result.** Production stays `b03e340`, compiler `/9`, readiness **50/100**, and human live-rep precision remains unknown.

## Original data and frozen access

Attribution: Rafael Aguilar-Ortega, Jorge Zafra-Palma, Rafael Muñoz-Salinas and Manuel J. Marin-Jimenez, *UCOPhyRehab++: Multi-modal and multi-view dataset for human rehabilitation analysis*, [version17935737](https://zenodo.org/records/17935737), [CC BY4.0](https://creativecommons.org/licenses/by/4.0/). The original `dataset_3d_with_angles.json` is preserved without modification under ignored `test-artifacts/`. All230,334,280 bytes match publisher MD5 `73026a0b44ada0603a6de63dd70f9fd4`; the locally measured SHA256 is `2bcb0d216b16c51c499acac885ac6bc2626ba58eff057ad4b984ec1481e12bc7`.

Intake `c07e563` was committed before acquisition/selection. Only subject1/exercise1 trajectory objects were allowed. A streaming parser projected432 folder/exercise headers, representing27 numeric folders0–26 and16 exercise IDs, while discarding other records' trajectory tokens without constructing their frame objects or computing their outcomes. This consumes JSON syntax from those records; it is not a claim that their bytes were never read. Other subjects remain sealed for subsequent numerical work. No ordinal-rating file or UI-PRMD final trajectory was opened.

Before selection, source-based amendment `0beb531` corrected the subject grammar to accept the publisher's valid `00` identifier in the metadata roster. It did not change the selected subject/exercise. The [primary descriptor](https://pmc.ncbi.nlm.nih.gov/articles/PMC13365497/) identifies exercise01 as left seated unsupported knee bending, which cannot be renamed bodyweight squat. It also states that the distributed frame list already omits the unreliable initial five frames. Our adapter retains every supplied frame instead of dropping five more. It does not inherit the upstream loader's zero-angle exclusion or subject-filter override of split membership.

The actual selected schema contains940 frames with IDs5–944, no internal gap, string-valued XYZ coordinates at `l_hip`, `l_knee`, `l_ankle`, and a numeric recorded angle. No explicit timestamp field is present in these frame objects. Acquisition period, coordinate units, complete tracking validity and video synchronisation remain unaudited. No milliseconds, confidence, anatomical centre or missing point is invented. The raw file remains authoritative; Decimal-valued numbers use strings in the separate JSON transport without rewriting the raw data.

## What the numerical check establishes

Mechanical geometry was frozen at `fe0f640`. After observing only the selected schema, `8148abb` fixed the exact coordinate fields, strict finite decimal-string parsing and all three vertex comparisons **before numerical interpretation**. No best vertex, complementary angle, fitted axis, normalization, range change or rater threshold is selected from outcomes.

| Original vertex | Compared frames | Mean absolute difference from recorded angle | Maximum difference |
|---|---:|---:|---:|
| `l_hip` |940|121.0124°|175.7481°|
| `l_knee` |940|2.01×10⁻¹⁴°|2.84×10⁻¹³°|
| `l_ankle` |940|116.3433°|175.4822°|

The recorded angle is mechanically consistent with the knee vertex of those same recorded points. **This is circular arithmetic conformance, not independent angle accuracy, clinical validity, rep correctness or camera precision.** It verifies a faithful parser and angle convention for this one recording. It cannot validate UI-PRMD surface-marker-to-joint-centre mappings, learned landmark weights or the app's whole-form decisions. No form labels or repetitions are created from this curve.

Original supplied frame IDs5,475,944 and the complete all-vertex trace were inspected. The first plot has crowded ticks/point names; it remains preserved. A separately named layout review retains exactly the same original points, frame choices and view direction, with padded bounds, fewer ticks and colour legends. Its receipt proves unchanged snapshot records. These are measured 3D triplet plots, not original RGB frames or MP33 overlays. No video or production pose path changes.

## Verification and continuation

All **220 Python tests pass**, including strict identity/split selection, ordinal rejection, finite parsing, preservation of zero/null data, absence of guessed timing, no gap repair and rigid-transform/scale invariance. `ijson3.6.0` is an offline-only dependency (BSD-3-Clause AND ISC); its Windows CPython3.13 wheel matches PyPI SHA256 `cf855a688dd80570e6daaa67afc84a950acf9c6ba9c3526096957614d21db1bd`. Wheel, licences, package metadata, acquisition headers, logs, protocols and original plots are retained. Install the research requirements using `scripts/requirements-joint-audit.txt`.

All **2,889 production evidence receipts** and the unchanged rubric revalidate at50/100. Main production sources, raw MP33 evidence, network assets, calibration/reference selection and frozen independent metrics remain unchanged. JVM/native tests are not rerun for this offline-only work; no APK is rebuilt. UI-PRMD final subjects remain sealed. No new human annotation is requested.

Keep this completed intake/conformance result; repeating the same angle reconstruction cannot improve the score. Future use needs a prospective paired-image measurement protocol, verified original-media rights and time alignment. The selected rehabilitation task supplies no two-policy binary-form benchmark. Next milestone work should address actual camera capture-clock/latency evidence or independent form generalisation on compatible supported exercises, rather than treating this arithmetic identity as model tuning. See `models/ucophyrehab-joint-intake.provenance.json` for immutable receipts.
