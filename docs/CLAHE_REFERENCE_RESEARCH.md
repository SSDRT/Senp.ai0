# Frozen CLAHE input hypothesis

28 October 2026. Test one photometric input alternative for the original sixteen complete reference uploads. Several reviewed gym frames contain large lighting differences between body and background. The hypothesis is that fixed local contrast processing may recover direct pose evidence. This is not established pose accuracy, a suggested form threshold or a reason to relax confidence.

Use pinned MediaPipe Full IMAGE CPU with a fixed OpenCV RGB-to-LAB conversion, CLAHE on L only (clip limit 2.0, tile grid 8x8), then LAB-to-RGB. OpenCV's [official example](https://docs.opencv.org/4.13.0/d5/daf/tutorial_py_histogram_equalization.html) documents this contrast-limited operator; it does not establish a benefit to Senpai. No parameters are searched after results. The image dimensions, 960-pixel edge cap, original 66 ms PTS and complete recording duration stay fixed. No spatial warp, resampling, crop, smoothing, repaired landmark, confidence boost or new network weight is introduced. Original and inferred RGB hashes remain separate.

Compare with the already-preserved, same-frame Full IMAGE raw control under the same current compiler `/8`. Both pipelines retain the same current coarse Full VIDEO torso gate: confidence >=0.65, in-image anchors, and centre distance <=max(0.05, half the coarse torso length). Keep all 33 raw image/world outputs and image/world visibility/presence even when withheld. No earlier or later observation may supply the current person. An independent Kotlin audit checks timebase, source/model/pipeline identities, raw preservation and admission before 128 calibration trials (two inputs x sixteen references x two sides x two fixed fits).

Freeze all code, input, model, original-control and runtime receipts before inference. OpenCV 4.13.0.92 is installed only in the existing ignored research environment from the hash-verified official PyPI Windows wheel, without changing NumPy/MediaPipe or Android dependencies. Preserve bundled original licence texts and dependency versions. Existing Full/Heavy choices keep priority; only unavailable sides can consider CLAHE ordinary then CLAHE bounded fitting. The 32-side denominator and every production cycle, confidence, range, noise, dwell, coverage, fault and >=80% reconstruction gate remain unchanged. Source outcomes, clip labels, skeletal validation and final subjects cannot select a candidate.

This is an initial reference-only screen. A gain would require a coherent live/source input pipeline, all source/independent/causal/native checks, original timestamped QA and a newly verified APK before promotion. A failed screen stops before source/holdout replay and leaves the app untouched. Review six fixed original RGB frames and raw model overlays at 6000/10000 ms for front squat, side squat and side push-up; these are diagnostic views, not new rep labels. Readiness starts at 50/100; this screen itself earns no points. Final subjects 08–10 remain sealed.

## Outcome: no additional reference sides

The code, fixed recipe and 115 source/input/runtime receipts were committed in `8d04259` before inference. All sixteen original references complete at all **7,191** expected PTS slots. The candidate preserves 5,763 raw model outputs and admits 5,180 current-person observations; the matched original IMAGE caches retain 5,886 raw outputs and admit 5,311. Counts of detections are not pose-accuracy measurements. Extraction takes 110.57 seconds with three desktop workers; it is not Android inference or sustained-camera performance.

All 128 trials complete under compiler `/8` and unchanged gates:

| Input | Fit | Ready / 32 | Insufficient cycles | Missing feature | Weak reference | Low self-reconstruction |
|---|---|---:|---:|---:|---:|---:|
| Original Full IMAGE | Ordinary | 4 | 17 | 11 | 0 | 0 |
| Original Full IMAGE | Bounded | 3 | 17 | 11 | 0 | 1 |
| Fixed CLAHE Full IMAGE | Ordinary | 4 | 13 | 12 | 3 | 0 |
| Fixed CLAHE Full IMAGE | Bounded | 4 | 13 | 12 | 3 | 0 |

Every ready CLAHE side is already served by an established Full/Heavy backend. **Zero additional sides recover**; the candidate is rejected for promotion. The production selector, current 12/32 references and source/live pipeline are unchanged. The different matched-control and candidate failure counts do not establish a gain in human-form precision.

Six original timestamped RGB frames, six corresponding transformed-input panels and twelve raw model overlays were inspected at the fixed 6,000/10,000 ms timestamps. At the front-squat 6,000 ms sample, the original IMAGE result is admitted while the CLAHE result is withheld by current-person/confidence admission. Side-squat and push-up overlays also differ without creating additional usable references. These are directly observed diagnostics, not new fault/correctness labels or validation of inferred depth. Original-input and transformed-input hashes reproduce the inferred frames exactly.

Verification passes **246 core-motion JVM and 134 Python tests**, including two new independent Kotlin preservation/identity cases and three Python photometry/scope cases. The first standalone Python preflight lacked the scripts import path; it was corrected before the source freeze and both logs are retained. All 1,796 current scored receipts and the unchanged rubric revalidate at **50/100**. Production main sources remain byte-for-byte equivalent to verified `6d1386b`; prior native/causal/corruption evidence is preserved and not described as freshly rerun. No APK, source outcome selection, new manual annotation, independent-label evaluation, validation/final-subject access or threshold change follows this failed reference-only screen.

`models/clahe-reference.provenance.json` binds 190 immutable source/input/outcome/runtime/QA receipts. Existing model binaries and Android dependencies are unchanged. Do not repeat this fixed recipe at easier gates or sweep its parameters on the sealed holdouts. Continue a separately justified representation/generalization or properly licensed reference acquisition experiment.

Reproduce the frozen source with preserved inputs in fresh output paths (completed outputs intentionally refuse overwrite):

```powershell
python scripts/extract_clahe_reference_candidate.py --workers 3
./gradlew.bat --init-script scripts/clahe-reference-research.init.gradle :core-motion:claheReferenceScreen :core-motion:verifyMotionCore --no-parallel
python scripts/plot_clahe_reference_candidate.py
python -m unittest discover -s scripts/tests -v
```
