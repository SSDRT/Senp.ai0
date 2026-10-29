# Licensed joint-measurement source review

29 October 2026. This is a publisher-metadata review, not a new tuning result. Production remains `b03e340` (compiler `/9`), readiness **50/100**, and human live-rep precision remains unknown.

## Verified version and grant

The paper's concept DOI, [Zenodo 17935736](https://doi.org/10.5281/zenodo.17935736), returns HTTP410 from its record API. That response does **not** establish that every dataset version is unavailable. The original [publisher repository](https://github.com/AVAuco/ucophyrehabpp/tree/ed132ffaeb2e667720ce65df224785f0ec394f45) also links the specific [version 17935737](https://zenodo.org/records/17935737). Its API returns200, identifies the same concept, and explicitly declares an open **CC BY4.0 data grant**. Both responses, their timestamps, hashes and headers are preserved. No article or code licence was substituted for the data grant.

The version inventories eight files: five derived visual archives, a sample archive, `dataset_3d_with_angles.json` (230,334,280 bytes), and `ucophyrehab2_data.jsonl` (149,416 bytes). Publisher MD5 values are recorded as declarations; payload integrity is **not** claimed without downloading and checking those payloads. The description spells the metadata filename differently from the actual inventory; acquisition must use the exact inventoried name. The publisher describes the sample archive as one subject, which cannot supply the three-subject test required by our fixed rubric.

The repository README is bound to its Git blob and commit. Its optional RGB loader setting does not establish that original RGB is publicly distributed. No upstream implementation code is copied, executed or trained here.

## Measurement and label limits

The [primary descriptor](https://www.nature.com/articles/s41597-026-07362-5) describes original RGB as available by research request, with derived visual representations public. It describes two-rater ordinal repetition ratings and ground truth for one articulation per exercise. These are distinct from binary whole-rep correctness, full-body coordination truth and raw MP33 confidence. The descriptor also excludes the first five ground-truth frames. Missing evidence cannot be filled by making up points or ratings.

No archive, annotation payload, rating, subject trajectory or original image was opened in this review. The actual payload schema, exercise compatibility, coordinate conventions and synchronisation therefore remain unaudited. No ordinal threshold, rater averaging, RGB reconstruction or unsupported exercise relabelling is introduced. An angle-error benchmark cannot be reported as counted-rep precision.

## Implemented intake and verification

`scripts/audit_ucophyrehab_source.py` binds the actual version identity, DOI/concept relationship, explicit data grant and file inventory to the captured HTTP response. It independently verifies the failed concept observation rather than propagating that failure to the available version. It preserves the distinction between metadata checksums and verified payload checksums, and awards no evaluation eligibility or points. The initial report, before adding concept-response verification, remains alongside the final `publisher-intake-verified.json`.

All **199 Python tests pass**, including seven new source/intake regressions. Existing evaluation code is also checked against integer, fractional and multi-rater ordinal inputs: it rejects them rather than coercing them to true/false. All **2,889 production readiness receipts** revalidate at50/100 with the unchanged rubric. No production main source, network weight, reference selection, calibration gate, independent metric or final UI-PRMD subject changes. JVM/native tests are not rerun for this offline-only change, and no APK is rebuilt.

The first local README copy had an extra trailing newline and failed its Git-blob check. That copy is retained; a separately named exact copy verifies against the publisher blob. This is a metadata-copy correction, not a change to upstream material or a model result.

## Next admissible experiment

This provides a newly verified open data option for **joint-measurement validation**. Before opening any trajectories or ratings, freeze the intended exercise/joint scope, subject split, permitted file reads and evaluation method. Audit the actual schema and time alignment first; keep single-joint errors separate from whole-form labels and MediaPipe camera accuracy. Original RGB access is not requested in this autonomous open-data workflow. Do not use the sample's one subject, projected modalities, ordinal-score rounding or the article's model accuracy to satisfy the independent-label milestone. The current failed skeletal projections remain rejected and final subjects remain sealed.
