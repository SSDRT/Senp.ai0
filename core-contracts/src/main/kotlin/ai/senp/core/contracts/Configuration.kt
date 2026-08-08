package ai.senp.core.contracts

import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Serializable
enum class VideoRole {
    SOURCE,
    REFERENCE,
}

@Serializable
data class VideoSource(
    val uri: String,
    val sha256: Sha256,
) {
    init {
        require(uri.isNotBlank()) { "video URI must not be blank" }
    }
}

@Serializable
data class SamplingConfiguration(
    val targetFramesPerSecond: Int = 15,
    val longEdgeCapPx: Int = 640,
) {
    init {
        require(targetFramesPerSecond in 1..120) { "target FPS must be in 1..120" }
        require(longEdgeCapPx in 64..8192) { "long-edge cap must be in 64..8192 pixels" }
    }
}

@Serializable
data class PoseThresholds(
    val minimumDetectionConfidence: Double = 0.5,
    val minimumPresenceConfidence: Double = 0.5,
    val minimumTrackingConfidence: Double = 0.5,
) {
    init {
        requireProbability(minimumDetectionConfidence, "minimum detection confidence")
        requireProbability(minimumPresenceConfidence, "minimum presence confidence")
        requireProbability(minimumTrackingConfidence, "minimum tracking confidence")
    }
}

@Serializable
data class PoseModelConfiguration(
    val modelSha256: Sha256,
    val modelVariant: String = "pose-landmarker-full",
    val thresholds: PoseThresholds = PoseThresholds(),
) {
    init {
        requireVersion(modelVariant, "model variant")
    }
}

@Serializable
data class AnalysisConfiguration(
    val model: PoseModelConfiguration,
    val pipelineVersion: String,
    val sampling: SamplingConfiguration = SamplingConfiguration(),
    val normalizationVersion: String,
    val exerciseProfileVersion: String,
) {
    init {
        requireVersion(pipelineVersion, "pipeline version")
        requireVersion(normalizationVersion, "normalization version")
        requireVersion(exerciseProfileVersion, "exercise-profile version")
    }
}

@Serializable
data class AnalysisRequest(
    val requestId: String,
    val requestedAtEpochMs: TimestampMs,
    val source: VideoSource,
    val reference: VideoSource,
    val configuration: AnalysisConfiguration,
)

@Serializable
data class CacheKey(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val sourceSha256: Sha256,
    val referenceSha256: Sha256,
    val modelSha256: Sha256,
    val modelVariant: String,
    val poseThresholds: PoseThresholds,
    val pipelineVersion: String,
    val sampling: SamplingConfiguration,
    val normalizationVersion: String,
    val exerciseProfileVersion: String,
) {
    init {
        require(schemaVersion > 0) { "cache-key schema version must be positive" }
        requireVersion(modelVariant, "model variant")
        requireVersion(pipelineVersion, "pipeline version")
        requireVersion(normalizationVersion, "normalization version")
        requireVersion(exerciseProfileVersion, "exercise-profile version")
    }

    fun canonicalForm(): String = buildString {
        appendLine("schemaVersion=$schemaVersion")
        appendLine("sourceSha256=${sourceSha256.value}")
        appendLine("referenceSha256=${referenceSha256.value}")
        appendLine("modelSha256=${modelSha256.value}")
        appendCanonicalString("modelVariant", modelVariant)
        appendLine("minimumDetectionConfidence=${poseThresholds.minimumDetectionConfidence}")
        appendLine("minimumPresenceConfidence=${poseThresholds.minimumPresenceConfidence}")
        appendLine("minimumTrackingConfidence=${poseThresholds.minimumTrackingConfidence}")
        appendCanonicalString("pipelineVersion", pipelineVersion)
        appendLine("targetFramesPerSecond=${sampling.targetFramesPerSecond}")
        appendLine("longEdgeCapPx=${sampling.longEdgeCapPx}")
        appendCanonicalString("normalizationVersion", normalizationVersion)
        appendCanonicalString("exerciseProfileVersion", exerciseProfileVersion)
    }

    fun stableId(): Sha256 {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalForm().toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        return Sha256(digest)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1

        fun from(request: AnalysisRequest): CacheKey = CacheKey(
            sourceSha256 = request.source.sha256,
            referenceSha256 = request.reference.sha256,
            modelSha256 = request.configuration.model.modelSha256,
            modelVariant = request.configuration.model.modelVariant,
            poseThresholds = request.configuration.model.thresholds,
            pipelineVersion = request.configuration.pipelineVersion,
            sampling = request.configuration.sampling,
            normalizationVersion = request.configuration.normalizationVersion,
            exerciseProfileVersion = request.configuration.exerciseProfileVersion,
        )
    }
}

private fun StringBuilder.appendCanonicalString(name: String, value: String) {
    append(name)
    append("Utf8Length=")
    append(value.toByteArray(StandardCharsets.UTF_8).size)
    append('\n')
    append(name)
    append('=')
    append(value)
    append('\n')
}
