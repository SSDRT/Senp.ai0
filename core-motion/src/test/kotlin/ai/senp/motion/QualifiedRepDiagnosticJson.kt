package ai.senp.motion

import kotlinx.serialization.json.*

internal fun QualifiedRepFrameEvidence.toDiagnosticJson(): JsonObject = buildJsonObject {
    put("timestamp_ms",timestampMs);put("attempt_id",attemptId);put("progress",driverProgress)
    put("uses_return_branch",comparisonUsesReturnBranch);put("reversal_confirmed",reversalConfirmed)
    put("far_endpoint_previously_observed",farEndpointPreviouslyObserved)
    put("features",JsonArray(features.map { f -> buildJsonObject {
        put("reference_feature",f.referenceFeature);put("observed_feature",f.observedFeature)
        put("raw_degrees",f.rawDegrees);put("confidence",f.confidence);put("observed_degrees",f.observedDegrees)
        put("lower_degrees",f.lowerDegrees);put("upper_degrees",f.upperDegrees)
        put("nominal_degrees",f.nominalAllowanceDegrees);put("acceptable_degrees",f.acceptableAllowanceDegrees)
        put("reject_degrees",f.rejectAllowanceDegrees);put("deviation_degrees",f.deviationDegrees);put("rule_id",f.ruleId)
    } }))
}
