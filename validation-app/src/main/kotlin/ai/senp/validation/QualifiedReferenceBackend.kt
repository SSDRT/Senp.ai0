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
)

internal sealed interface QualifiedReferencePreparation {
    val attempts: List<ReferenceBackendAttempt>
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
    currentCoroutineContext().ensureActive()
    when (val result = compileQualifiedRepFromExtraction(primary, exerciseId, identity, config)) {
        is QualifiedRepCompilation.Ready -> {
            attempts += ReferenceBackendAttempt(identity.poseModelSha256, null, "Primary reference calibrated")
            return QualifiedReferencePreparation.Ready(result.reference, primary, request, attempts.toList())
        }
        is QualifiedRepCompilation.Unavailable -> {
            attempts += ReferenceBackendAttempt(identity.poseModelSha256, result.reason, result.detail)
            if (result.reason in setOf(RepReferenceFailure.UNKNOWN_EXERCISE, RepReferenceFailure.UNSUPPORTED_EXERCISE,
                    RepReferenceFailure.UNRESOLVED_GUIDANCE, RepReferenceFailure.MISSING_PROVENANCE)) {
                return QualifiedReferencePreparation.Unavailable(attempts.toList())
            }
        }
    }
    if (request == null || fallbackModel == null || fallbackModel.modelSha256 == request.model.modelSha256) {
        return QualifiedReferencePreparation.Unavailable(attempts.toList())
    }
    currentCoroutineContext().ensureActive()
    val fallbackRequest = request.copy(model = fallbackModel)
    val fallback = when (val result = extractPose(VideoRole.REFERENCE, request.source, request.sampling, fallbackModel)) {
        is StageResult.Success -> result.value
        is StageResult.Failure -> {
            attempts += ReferenceBackendAttempt(fallbackModel.modelSha256.value, RepReferenceFailure.WEAK_REFERENCE,
                "Fallback reference extraction unavailable: ${result.failure.message}")
            return QualifiedReferencePreparation.Unavailable(attempts.toList())
        }
    }
    currentCoroutineContext().ensureActive()
    val fallbackIdentity = identity.copy(poseModelSha256 = fallbackModel.modelSha256.value)
    return when (val result = compileQualifiedRepFromExtraction(fallback, exerciseId, fallbackIdentity, config)) {
        is QualifiedRepCompilation.Ready -> {
            attempts += ReferenceBackendAttempt(fallbackIdentity.poseModelSha256, null, "Fallback reference calibrated")
            QualifiedReferencePreparation.Ready(result.reference, fallback, fallbackRequest, attempts.toList())
        }
        is QualifiedRepCompilation.Unavailable -> {
            attempts += ReferenceBackendAttempt(fallbackIdentity.poseModelSha256, result.reason, result.detail)
            QualifiedReferencePreparation.Unavailable(attempts.toList())
        }
    }
}
