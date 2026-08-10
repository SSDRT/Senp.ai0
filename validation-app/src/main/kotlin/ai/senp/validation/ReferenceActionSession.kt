package ai.senp.validation

import ai.senp.core.contracts.CanonicalObservationSequence
import ai.senp.core.contracts.DurationMs
import ai.senp.core.contracts.ObservationSampling
import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.PoseModelConfiguration
import ai.senp.core.contracts.SamplingConfiguration
import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.VideoPoseExtraction
import ai.senp.core.contracts.VideoRole
import ai.senp.core.contracts.VideoSource
import ai.senp.core.pipeline.VideoPoseExtractor
import ai.senp.motion.ActionProfile
import ai.senp.motion.ActionRecognitionResult
import ai.senp.motion.ActionStateEstimate
import ai.senp.motion.ActionStateRecognizer
import ai.senp.motion.ActionTrackingStatus
import ai.senp.motion.CoachingObservation
import ai.senp.motion.LiveFeedbackStabilizer
import ai.senp.motion.ReferenceActionCompilation
import ai.senp.motion.ReferenceActionCompiler
import ai.senp.motion.ReferenceDeviationEvaluator
import ai.senp.motion.ReferenceDeviationMeasurement
import ai.senp.motion.SpatialSynchronizationEngine
import ai.senp.motion.StableLiveFeedback
import ai.senp.sync.v2.PoseObservationAdapter
import ai.senp.sync.v2.SynchronizationPoseCache
import ai.senp.sync.v2.SynchronizationPoseCacheKey
import kotlin.math.roundToInt

internal sealed interface ReferencePreparationOutcome {
    data class Ready(
        val profile: ActionProfile,
        val extraction: VideoPoseExtraction,
    ) : ReferencePreparationOutcome

    data class Rejected(
        val message: String,
        val extraction: VideoPoseExtraction? = null,
    ) : ReferencePreparationOutcome
}

internal data class RecordedReferenceActionAnalysis(
    val profile: ActionProfile,
    val recognition: ActionRecognitionResult,
    val deviations: List<ReferenceDeviationMeasurement>,
)

internal data class LiveReferenceActionOutput(
    val estimate: ActionStateEstimate,
    val feedback: StableLiveFeedback,
    val poseConfidence: Double,
)

/** Android-side composition seam for reference-action compilation and recognition. */
internal class ReferenceActionSessionEngine(
    private val videoPoseExtractor: VideoPoseExtractor,
    private val poseCache: SynchronizationPoseCache,
    private val observationAdapter: PoseObservationAdapter = PoseObservationAdapter(),
    private val spatialEngine: SpatialSynchronizationEngine = SpatialSynchronizationEngine(),
    private val compiler: ReferenceActionCompiler = ReferenceActionCompiler(),
) {
    suspend fun prepareReference(
        source: VideoSource,
        sampling: SamplingConfiguration,
        model: PoseModelConfiguration,
    ): ReferencePreparationOutcome {
        val extraction = when (val result = extractPose(VideoRole.REFERENCE, source, sampling, model)) {
            is StageResult.Success -> result.value
            is StageResult.Failure -> return ReferencePreparationOutcome.Rejected(result.failure.message)
        }
        return compileReference(extraction, sampling.targetFramesPerSecond.toDouble())
    }

    suspend fun extractPose(
        role: VideoRole,
        source: VideoSource,
        sampling: SamplingConfiguration,
        model: PoseModelConfiguration,
    ): StageResult<VideoPoseExtraction> {
        val cacheKey = poseCacheKey(role, source, sampling, model)
        poseCache.lookup(cacheKey)?.let { cached ->
            if (cached.role == role && cached.poses.frames.isNotEmpty()) return StageResult.Success(cached)
        }
        return when (val result = videoPoseExtractor.extract(role, source, sampling, model)) {
            is StageResult.Failure -> result
            is StageResult.Success -> {
                val extraction = result.value
                if (extraction.role != role || extraction.poses.frames.isEmpty()) {
                    StageResult.Failure(
                        ai.senp.core.contracts.AnalysisFailure.VideoPose(
                            role = role,
                            kind = ai.senp.core.contracts.VideoPoseFailureKind.INFERENCE,
                            message = if (extraction.role != role) {
                                "extractor returned ${extraction.role} for $role"
                            } else {
                                "extractor returned no sampled pose frames"
                            },
                        ),
                    )
                } else {
                    poseCache.store(cacheKey, extraction)
                    StageResult.Success(extraction)
                }
            }
        }
    }

    fun compileReference(
        extraction: VideoPoseExtraction,
        analysisFramesPerSecond: Double,
    ): ReferencePreparationOutcome {
        require(extraction.role == VideoRole.REFERENCE)
        val observations = observationAdapter.adapt(extraction, analysisFramesPerSecond)
        return when (val result = compiler.compile(observations)) {
            is ReferenceActionCompilation.Success -> ReferencePreparationOutcome.Ready(result.profile, extraction)
            is ReferenceActionCompilation.Failure -> ReferencePreparationOutcome.Rejected(result.message, extraction)
        }
    }

    fun analyzeRecorded(
        profile: ActionProfile,
        extraction: VideoPoseExtraction,
        analysisFramesPerSecond: Double,
    ): RecordedReferenceActionAnalysis {
        require(extraction.role == VideoRole.SOURCE)
        val observations = observationAdapter.adapt(extraction, analysisFramesPerSecond)
        val spatial = spatialEngine.analyzeSequence(observations)
        val recognizer = ActionStateRecognizer(profile)
        val deviations = ReferenceDeviationEvaluator(profile)
        val measurements = buildList {
            spatial.frames.forEach { frame ->
                val estimate = recognizer.accept(frame)
                addAll(deviations.evaluate(frame, estimate))
            }
        }
        return RecordedReferenceActionAnalysis(
            profile = profile,
            recognition = recognizer.finish(),
            deviations = measurements,
        )
    }

    fun newLiveProcessor(
        profile: ActionProfile,
        analysisFramesPerSecond: Double,
    ): LiveReferenceActionProcessor = LiveReferenceActionProcessor(
        profile = profile,
        analysisFramesPerSecond = analysisFramesPerSecond,
        observationAdapter = observationAdapter,
        spatialEngine = spatialEngine,
    )

    private fun poseCacheKey(
        role: VideoRole,
        source: VideoSource,
        sampling: SamplingConfiguration,
        model: PoseModelConfiguration,
    ): SynchronizationPoseCacheKey = SynchronizationPoseCacheKey(
        role = role,
        videoSha256 = source.sha256.value,
        modelSha256 = model.modelSha256.value,
        modelVariant = model.modelVariant,
        targetFramesPerSecond = sampling.targetFramesPerSecond,
        longEdgeCapPx = sampling.longEdgeCapPx,
        minimumDetectionConfidence = model.thresholds.minimumDetectionConfidence,
        minimumPresenceConfidence = model.thresholds.minimumPresenceConfidence,
        minimumTrackingConfidence = model.thresholds.minimumTrackingConfidence,
    )
}

internal fun referenceProfileQualityNotice(profile: ActionProfile): String? {
    val validation = profile.validation
    return when {
        validation.reconstructionAccuracy < MIN_REFERENCE_RECONSTRUCTION ->
            "Only ${(validation.reconstructionAccuracy * 100).roundToInt()}% of the demonstrated states were reconstructed confidently. Live comparison is still available, but hints may be less specific."
        validation.transitionCoverage < MIN_REFERENCE_TRANSITION_COVERAGE ->
            "Only ${(validation.transitionCoverage * 100).roundToInt()}% of the demonstrated transitions were recovered confidently. Live comparison is still available and will rely on the states it can recognize."
        validation.meanRecognitionConfidence < MIN_REFERENCE_RECOGNITION_CONFIDENCE ->
            "Reference recognition confidence is ${(validation.meanRecognitionConfidence * 100).roundToInt()}%. Live comparison is still available; low-confidence frames will produce fewer hints."
        else -> null
    }
}

private const val MIN_REFERENCE_RECONSTRUCTION = 0.72
private const val MIN_REFERENCE_TRANSITION_COVERAGE = 0.80
private const val MIN_REFERENCE_RECOGNITION_CONFIDENCE = 0.55

internal class LiveReferenceActionProcessor(
    private val profile: ActionProfile,
    private val analysisFramesPerSecond: Double,
    private val observationAdapter: PoseObservationAdapter = PoseObservationAdapter(),
    private val spatialEngine: SpatialSynchronizationEngine = SpatialSynchronizationEngine(),
    private val recognizer: ActionStateRecognizer = ActionStateRecognizer(profile),
    private val deviationEvaluator: ReferenceDeviationEvaluator = ReferenceDeviationEvaluator(profile),
    private val stabilizer: LiveFeedbackStabilizer = LiveFeedbackStabilizer(),
) {
    init {
        require(analysisFramesPerSecond.isFinite() && analysisFramesPerSecond > 0.0)
    }

    fun reset() {
        recognizer.reset()
        deviationEvaluator.reset()
        stabilizer.reset()
    }

    fun update(pose: PoseFrame): LiveReferenceActionOutput {
        val observation = observationAdapter.adaptFrame(pose)
        val sequence = CanonicalObservationSequence(
            role = VideoRole.SOURCE,
            duration = DurationMs(pose.timestamp.value + 1L),
            sampling = ObservationSampling(analysisFramesPerSecond = analysisFramesPerSecond),
            observations = listOf(observation),
        )
        val spatialFrame = spatialEngine.analyzeSequence(sequence).frames.single()
        val estimate = recognizer.accept(spatialFrame)
        val deviations = deviationEvaluator.evaluate(spatialFrame, estimate)
        val actionTracking = estimate.status == ActionTrackingStatus.TRACKING ||
            estimate.status == ActionTrackingStatus.COMPLETED
        val coachingCandidates = if (actionTracking) {
            deviations
                .asSequence()
                .filter { measurement -> measurement.feature.startsWith("angle.") }
                .map { measurement -> measurement.toCoachingObservation(profile) }
                .toList()
        } else {
            emptyList()
        }
        val trackingConfidence = if (actionTracking) {
            minOf(pose.validity.confidence, estimate.confidence)
        } else {
            0.0
        }
        return LiveReferenceActionOutput(
            estimate = estimate,
            feedback = stabilizer.update(
                timestampMs = pose.timestamp.value,
                observations = coachingCandidates,
                trackingConfidence = trackingConfidence,
            ),
            poseConfidence = pose.validity.confidence,
        )
    }
}

internal data class PreparedReferenceAction(
    val profile: ActionProfile,
    val referenceSha256: String,
    val analysisFramesPerSecond: Double,
)

internal object ReferenceActionProfileStore {
    @Volatile
    private var current: PreparedReferenceAction? = null

    fun set(prepared: PreparedReferenceAction) {
        current = prepared
    }

    fun get(): PreparedReferenceAction? = current

    fun get(referenceSha256: String): PreparedReferenceAction? = current?.takeIf {
        it.referenceSha256 == referenceSha256
    }

    fun clear() {
        current = null
    }
}

internal fun ReferenceDeviationMeasurement.toReferenceCueLabel(): String {
    val joint = feature.removePrefix("angle.").takeIf { feature.startsWith("angle.") }
    if (joint != null) {
        val readable = joint.replace('_', ' ')
        val tooOpen = signedDeltaOutsideRange > 0.0
        return when {
            joint.endsWith("elbow") || joint.endsWith("knee") -> if (tooOpen) {
                "Bend your $readable a little more to match the reference"
            } else {
                "Straighten your $readable a little more to match the reference"
            }
            joint.endsWith("hip") -> if (tooOpen) {
                "Bend more at your $readable to match the reference"
            } else {
                "Open your $readable a little more to match the reference"
            }
            joint.endsWith("shoulder") -> if (tooOpen) {
                "Bring your $readable position closer to the reference"
            } else {
                "Raise your $readable position a little more toward the reference"
            }
            else -> "Adjust your $readable angle toward the reference"
        }
    }
    val subject = feature.substringAfter('.', feature).replace('_', ' ')
    return "Adjust $subject toward the reference"
}

private fun ReferenceDeviationMeasurement.toCoachingObservation(profile: ActionProfile): CoachingObservation {
    val state = profile.states.firstOrNull { it.id == stateId }
    val importance = state?.features?.firstOrNull { it.name == feature }?.importance ?: 0.0
    val direction = if (signedDeltaOutsideRange < 0.0) "below" else "above"
    return CoachingObservation(
        stableKey = feature + "|" + direction,
        label = toReferenceCueLabel(),
        confidence = confidence,
        severity = (normalizedDeviation / 2.0).coerceIn(0.0, 1.0),
        timestampMs = timestamp.value,
        priority = (importance * 100.0).roundToInt().coerceAtLeast(0),
    )
}
