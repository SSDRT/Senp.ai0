package ai.senp.motion

/** Passive research measurements from the branch actually used by the comparator.
 * These are reference disagreements, never new correctness labels or fault onsets.
 * No data is collected unless a diagnostic engine is constructed explicitly.
 */
internal data class QualifiedRepFeatureEvidence(
    val referenceFeature: String,
    val observedFeature: String,
    val rawDegrees: Double?,
    val confidence: Double,
    val observedDegrees: Double?,
    val lowerDegrees: Double,
    val upperDegrees: Double,
    val nominalAllowanceDegrees: Double,
    val acceptableAllowanceDegrees: Double,
    val rejectAllowanceDegrees: Double,
    val deviationDegrees: Double?,
    val ruleId: String?,
)

internal data class QualifiedRepFrameEvidence(
    val timestampMs: Long,
    val referenceKey: String,
    val attemptId: Long,
    val driverProgress: Double,
    val comparisonUsesReturnBranch: Boolean,
    val reversalConfirmed: Boolean,
    val farEndpointPreviouslyObserved: Boolean,
    val features: List<QualifiedRepFeatureEvidence>,
)
