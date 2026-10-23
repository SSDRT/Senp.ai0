package ai.senp.validation

import ai.senp.core.contracts.*
import ai.senp.motion.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Retain the verified upload and extraction settings, never an inferred exercise or source outcome. */
internal data class ReferenceExtractionRequest(
    val source: VideoSource,
    val sampling: SamplingConfiguration,
    val model: PoseModelConfiguration,
)

internal data class ReferenceBackendAttempt(
    val modelSha256: String,
    val failure: RepReferenceFailure?,
    val detail: String,
    val curveFit: RepReferenceCurveFit? = null,
)

internal const val QUALIFIED_REFERENCE_SELECTION_POLICY = "qualified-reference-selection/2"

internal sealed interface QualifiedReferencePreparation {
    val attempts: List<ReferenceBackendAttempt>
    val selectionPolicy: String get() = QUALIFIED_REFERENCE_SELECTION_POLICY
    data class Ready(
        val reference: QualifiedRepReference,
        val extraction: VideoPoseExtraction,
        val request: ReferenceExtractionRequest?,
        override val attempts: List<ReferenceBackendAttempt>,
    ) : QualifiedReferencePreparation
    data class Unavailable(override val attempts: List<ReferenceBackendAttempt>) : QualifiedReferencePreparation {
        val detail: String get() = attempts.joinToString("; ") { it.detail }
    }
}

/** Select once from reference evidence alone. No live/source observations or labels enter this API. */
internal suspend fun ReferenceActionSessionEngine.prepareQualifiedReference(
    primary: VideoPoseExtraction,
    identity: RepEvidenceFingerprint,
    request: ReferenceExtractionRequest?,
    exerciseId: String,
    config: QualifiedRepConfig,
    fallbackModel: PoseModelConfiguration?,
    allowCurveFitRecovery: Boolean = true,
): QualifiedReferencePreparation {
    val attempts = mutableListOf<ReferenceBackendAttempt>()
    fun unavailable(reason: RepReferenceFailure, detail: String): QualifiedReferencePreparation.Unavailable {
        attempts += ReferenceBackendAttempt(identity.poseModelSha256, reason, detail)
        return QualifiedReferencePreparation.Unavailable(attempts.toList())
    }
    if (request != null && (request.source.sha256.value != identity.referenceVideoSha256 ||
            request.model.modelSha256.value != identity.poseModelSha256)) {
        return unavailable(RepReferenceFailure.MISSING_PROVENANCE, "Reference extraction settings do not match its verified identity")
    }
    if (identity.preprocessingId != RepEvidenceFingerprint.RAW_WORLD_PIPELINE) {
        return unavailable(RepReferenceFailure.MISSING_PROVENANCE, "Reference backend selection requires the raw world-landmark pipeline")
    }
    val fatal = setOf(RepReferenceFailure.UNKNOWN_EXERCISE,RepReferenceFailure.UNSUPPORTED_EXERCISE,
        RepReferenceFailure.UNRESOLVED_GUIDANCE,RepReferenceFailure.MISSING_PROVENANCE)
    data class Input(val extraction: VideoPoseExtraction,val identity: RepEvidenceFingerprint,
        val request: ReferenceExtractionRequest?,val failure: RepReferenceFailure)
    val failedInputs = mutableListOf<Input>()
    suspend fun compile(extraction: VideoPoseExtraction, fingerprint: RepEvidenceFingerprint,
        settings: QualifiedRepConfig, label: String): QualifiedRepCompilation {
        currentCoroutineContext().ensureActive()
        val result = compileQualifiedRepFromExtraction(extraction,exerciseId,fingerprint,settings)
        currentCoroutineContext().ensureActive()
        attempts += ReferenceBackendAttempt(fingerprint.poseModelSha256,(result as? QualifiedRepCompilation.Unavailable)?.reason,
            "$label: ${(result as? QualifiedRepCompilation.Unavailable)?.detail ?: "Reference calibrated"}",settings.referenceCurveFit)
        return result
    }
    val primaryResult = compile(primary,identity,config,"Primary configured fit")
    if (primaryResult is QualifiedRepCompilation.Ready)
        return QualifiedReferencePreparation.Ready(primaryResult.reference,primary,request,attempts.toList())
    primaryResult as QualifiedRepCompilation.Unavailable
    if (primaryResult.reason in fatal) return QualifiedReferencePreparation.Unavailable(attempts.toList())
    failedInputs += Input(primary,identity,request,primaryResult.reason)
    if (request != null && fallbackModel != null && fallbackModel.modelSha256 != request.model.modelSha256) {
        currentCoroutineContext().ensureActive()
        when (val extracted = extractPose(VideoRole.REFERENCE,request.source,request.sampling,fallbackModel)) {
            is StageResult.Failure -> attempts += ReferenceBackendAttempt(fallbackModel.modelSha256.value,RepReferenceFailure.WEAK_REFERENCE,
                "Fallback reference extraction unavailable: ${extracted.failure.message}")
            is StageResult.Success -> {
                val fallbackIdentity = identity.copy(poseModelSha256 = fallbackModel.modelSha256.value)
                val fallbackRequest = request.copy(model = fallbackModel)
                val result = compile(extracted.value,fallbackIdentity,config,"Fallback model configured fit")
                if (result is QualifiedRepCompilation.Ready)
                    return QualifiedReferencePreparation.Ready(result.reference,extracted.value,fallbackRequest,attempts.toList())
                result as QualifiedRepCompilation.Unavailable
                if (result.reason in fatal) return QualifiedReferencePreparation.Unavailable(attempts.toList())
                failedInputs += Input(extracted.value,fallbackIdentity,fallbackRequest,result.reason)
            }
        }
    }
    // Preserve both models' established fits before trying a different estimator. Recovery
    // cannot create missing observations, cycles or range, or bypass unresolved guidance.
    if (allowCurveFitRecovery && config.referenceCurveFit == RepReferenceCurveFit.ORDINARY_LEAST_SQUARES) {
        val recoverable = setOf(RepReferenceFailure.WEAK_REFERENCE,RepReferenceFailure.LOW_SELF_RECONSTRUCTION)
        for (input in failedInputs.filter { it.failure in recoverable }) {
            val result = compile(input.extraction,input.identity,config.copy(referenceCurveFit = RepReferenceCurveFit.BOUNDED_MEDIAN_PAIRWISE),"Robust curve recovery")
            if (result is QualifiedRepCompilation.Ready)
                return QualifiedReferencePreparation.Ready(result.reference,input.extraction,input.request,attempts.toList())
        }
    }
    return QualifiedReferencePreparation.Unavailable(attempts.toList())
}
