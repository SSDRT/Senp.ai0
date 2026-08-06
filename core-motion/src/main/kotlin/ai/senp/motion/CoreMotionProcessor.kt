package ai.senp.motion

import ai.senp.core.contracts.AnalysisFailure
import ai.senp.core.contracts.FeatureSample
import ai.senp.core.contracts.FrameValidityReason
import ai.senp.core.contracts.FrameValidityStatus
import ai.senp.core.contracts.JointAngle
import ai.senp.core.contracts.MotionSeries
import ai.senp.core.contracts.PoseLandmark
import ai.senp.core.contracts.PoseSequence
import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.TimestampMs
import ai.senp.core.pipeline.MotionProcessor

/** Stable canonical feature names emitted by [CoreMotionProcessor]. */
object MotionFeatureSchema {
    const val VERSION: String = "motion-series-features/1"

    val jointNames: List<String> = listOf(
        "left_shoulder",
        "right_shoulder",
        "left_elbow",
        "right_elbow",
        "left_wrist",
        "right_wrist",
        "left_hip",
        "right_hip",
        "left_knee",
        "right_knee",
        "left_ankle",
        "right_ankle",
    )

    fun angleDegrees(space: String, joint: String): String = "angle.$space.$joint.degrees"
    fun angleConfidence(space: String, joint: String): String = "angle.$space.$joint.confidence"
    fun angularVelocity(space: String, joint: String): String =
        "angle.$space.$joint.velocity_degrees_per_second"

    val comparisonFeaturesByExercise: Map<String, List<String>> = linkedMapOf(
        "generic" to jointNames,
        "biceps_curl" to listOf(
            "left_elbow", "right_elbow", "left_shoulder", "right_shoulder", "left_wrist", "right_wrist",
        ),
        "pushup" to listOf(
            "left_elbow", "right_elbow", "left_shoulder", "right_shoulder", "left_hip", "right_hip",
        ),
        "squat" to listOf(
            "left_hip", "right_hip", "left_knee", "right_knee", "left_ankle", "right_ankle",
        ),
        "leg_raise" to listOf("left_hip", "right_hip", "left_knee", "right_knee"),
        "plank" to listOf(
            "left_shoulder", "right_shoulder", "left_hip", "right_hip", "left_knee", "right_knee",
        ),
        "pullup" to listOf(
            "left_elbow", "right_elbow", "left_shoulder", "right_shoulder", "left_hip", "right_hip",
        ),
    )
}

/**
 * Canonical Wave 3 adapter. Its only public DTO boundary is core-contracts.
 * The proven timestamp-aware engine remains module-internal.
 */
class CoreMotionProcessor : MotionProcessor {
    private val config = MotionConfig()
    private val engine = MotionEngine(config)
    private val normalizer = PoseNormalizer(config.minScale)

    override suspend fun process(
        poses: PoseSequence,
        normalizationVersion: String,
        exerciseProfileVersion: String,
    ): StageResult<MotionSeries> {
        if (poses.frames.isEmpty()) {
            return StageResult.Failure(AnalysisFailure.Motion(poses.role, "pose sequence must not be empty"))
        }
        if (normalizationVersion != MotionCoreVersions.NORMALIZATION) {
            return StageResult.Failure(
                AnalysisFailure.Motion(
                    poses.role,
                    "unsupported normalization version '$normalizationVersion'; expected '" +
                        MotionCoreVersions.NORMALIZATION + "'",
                ),
            )
        }
        val profileBinding = resolveProfile(exerciseProfileVersion)
            ?: return StageResult.Failure(
                AnalysisFailure.Motion(
                    poses.role,
                    "unsupported exercise profile version '$exerciseProfileVersion'",
                ),
            )

        return try {
            val internalFrames = poses.frames.map(::toInternalFrame)
            val processed = engine.analyze(internalFrames, profileBinding.profile)
            StageResult.Success(toCanonicalSeries(poses, processed))
        } catch (error: IllegalArgumentException) {
            StageResult.Failure(
                AnalysisFailure.Motion(poses.role, error.message ?: "invalid motion input"),
            )
        } catch (error: IllegalStateException) {
            StageResult.Failure(
                AnalysisFailure.Motion(poses.role, error.message ?: "motion processing failed"),
            )
        }
    }

    private fun toCanonicalSeries(
        poses: PoseSequence,
        processed: List<ProcessedFrame>,
    ): MotionSeries {
        val featureSamples = ArrayList<FeatureSample>(processed.size)
        val jointAngles = ArrayList<JointAngle>(processed.size * MotionFeatureSchema.jointNames.size * 2)
        var previousTimestampMs: Long? = null
        var previousImageAngles: Map<String, Double?> = emptyMap()
        var previousWorldAngles: Map<String, Double?> = emptyMap()

        processed.forEachIndexed { index, item ->
            val inputFrame = poses.frames[index]
            val canonicalValidity = mergeValidity(inputFrame.validity, item)
            val internalValidity = canonicalValidity.status.toInternalValidity()
            val imageNormalization = normalizer.normalizeImage(item.frame)
            val worldNormalization = normalizer.normalizeWorld(item.frame)
            val imageFrame = imageNormalization.frame
            val worldFrame = worldNormalization.frame
            val imageAngles = MotionFeatures.angles(
                imageFrame,
                internalValidity,
                config.minAngleConfidence,
                config.minAngleConfidence,
                CoordinateSpace.IMAGE,
            )
            val worldAngles = MotionFeatures.angles(
                worldFrame,
                internalValidity,
                config.minAngleConfidence,
                config.minAngleConfidence,
                CoordinateSpace.WORLD,
            )
            val imageVelocities = previousTimestampMs?.let { previousTimestamp ->
                MotionFeatures.angularVelocities(
                    previousImageAngles,
                    imageAngles,
                    previousTimestamp,
                    item.frame.timestampMs,
                )
            } ?: MotionFeatureSchema.jointNames.associateWith { null }
            val worldVelocities = previousTimestampMs?.let { previousTimestamp ->
                MotionFeatures.angularVelocities(
                    previousWorldAngles,
                    worldAngles,
                    previousTimestamp,
                    item.frame.timestampMs,
                )
            } ?: MotionFeatureSchema.jointNames.associateWith { null }

            val values = linkedMapOf<String, Double?>()
            values["quality.score"] = item.quality.score
            values["quality.motion_confidence"] = canonicalValidity.confidence
            values["quality.input_confidence"] = inputFrame.validity.confidence
            values["quality.required_coverage"] = item.quality.requiredCoverage
            values["quality.required_visibility"] = item.quality.requiredVisibility
            values["quality.required_presence"] = item.quality.requiredPresence
            values["quality.preferred_quality"] = item.quality.preferredQuality
            values["quality.repaired_fraction"] = item.quality.repairedFraction
            values["quality.clipping"] = item.quality.clipping
            values["quality.instability"] = item.quality.instability
            values["quality.selected_side_code"] = when (item.quality.selectedSide) {
                BodySide.LEFT -> -1.0
                BodySide.RIGHT -> 1.0
                null -> 0.0
            }
            values["guardrail.left_right_swap_applied"] = item.guardrails.leftRightSwapApplied.toDouble()
            values["guardrail.impossible_proportions"] = item.guardrails.impossibleProportions.toDouble()
            values["normalization.image.available"] =
                (imageNormalization.status == NormalizationStatus.NORMALIZED).toDouble()
            values["normalization.world.available"] =
                (worldNormalization.status == NormalizationStatus.NORMALIZED).toDouble()

            appendTorsoFeatures(values, "image", imageFrame, internalValidity, CoordinateSpace.IMAGE)
            appendTorsoFeatures(values, "world", worldFrame, internalValidity, CoordinateSpace.WORLD)
            appendAngleFeatures(
                values = values,
                angles = imageAngles,
                velocities = imageVelocities,
                frame = imageFrame,
                validityConfidence = canonicalValidity.confidence,
                spaceName = "image",
                coordinateSpace = CoordinateSpace.IMAGE,
                timestamp = inputFrame.timestamp,
                jointAngles = jointAngles,
                emitCanonicalComparisonAngles = true,
            )
            appendAngleFeatures(
                values = values,
                angles = worldAngles,
                velocities = worldVelocities,
                frame = worldFrame,
                validityConfidence = canonicalValidity.confidence,
                spaceName = "world",
                coordinateSpace = CoordinateSpace.WORLD,
                timestamp = inputFrame.timestamp,
                jointAngles = jointAngles,
                emitCanonicalComparisonAngles = false,
            )

            featureSamples += FeatureSample(inputFrame.timestamp, values, canonicalValidity)
            previousTimestampMs = item.frame.timestampMs
            previousImageAngles = imageAngles
            previousWorldAngles = worldAngles
        }

        return MotionSeries(poses.role, featureSamples, jointAngles)
    }

    private fun appendAngleFeatures(
        values: MutableMap<String, Double?>,
        angles: Map<String, Double?>,
        velocities: Map<String, Double?>,
        frame: PoseFrame,
        validityConfidence: Double,
        spaceName: String,
        coordinateSpace: CoordinateSpace,
        timestamp: TimestampMs,
        jointAngles: MutableList<JointAngle>,
        emitCanonicalComparisonAngles: Boolean,
    ) {
        MotionFeatureSchema.jointNames.forEach { joint ->
            val angle = angles[joint]
            val confidence = angle?.let {
                angleConfidence(frame, MotionFeatures.angleDefinitions.getValue(joint), coordinateSpace) *
                    validityConfidence
            } ?: 0.0
            values[MotionFeatureSchema.angleDegrees(spaceName, joint)] = angle
            values[MotionFeatureSchema.angleConfidence(spaceName, joint)] = confidence
            values[MotionFeatureSchema.angularVelocity(spaceName, joint)] = velocities[joint]
            if (angle != null && emitCanonicalComparisonAngles) {
                jointAngles += JointAngle(timestamp, joint, angle, confidence.coerceIn(0.0, 1.0))
            }
        }
    }

    private fun appendTorsoFeatures(
        values: MutableMap<String, Double?>,
        spaceName: String,
        frame: PoseFrame,
        validity: FrameValidity,
        coordinateSpace: CoordinateSpace,
    ) {
        val torso = if (validity == FrameValidity.BLIND || validity == FrameValidity.CONTINUITY_BREAK) {
            null
        } else {
            MotionFeatures.torso(
                frame,
                coordinateSpace,
                config.minAngleConfidence,
                config.minAngleConfidence,
            )
        }
        values["torso.$spaceName.length"] = torso?.torsoLength
        values["torso.$spaceName.lean_from_vertical_degrees"] = torso?.leanFromVerticalDeg
        values["torso.$spaceName.shoulder_tilt_degrees"] = torso?.shoulderTiltDeg
        values["torso.$spaceName.hip_tilt_degrees"] = torso?.hipTiltDeg
    }

    private fun angleConfidence(
        frame: PoseFrame,
        definition: AngleDefinition,
        coordinateSpace: CoordinateSpace,
    ): Double {
        val landmarks = listOf(frame[definition.first], frame[definition.vertex], frame[definition.third])
        val coordinatesPresent = landmarks.all { landmark ->
            when (coordinateSpace) {
                CoordinateSpace.IMAGE -> landmark.hasFiniteImage()
                CoordinateSpace.WORLD -> landmark.hasFiniteWorld()
            }
        }
        if (!coordinatesPresent) return 0.0
        return landmarks.minOf { minOf(it.visibility, it.presence) }.coerceIn(0.0, 1.0)
    }

    private fun toInternalFrame(frame: ai.senp.core.contracts.PoseFrame): PoseFrame {
        val inputValidity = frame.validity.status.toInternalValidity()
        val blind = frame.validity.status == FrameValidityStatus.BLIND
        return PoseFrame(
            timestampMs = frame.timestamp.value,
            landmarks = frame.landmarks.map { landmark -> toInternalLandmark(landmark, blind) },
            inputValidity = inputValidity,
        )
    }

    private fun toInternalLandmark(landmark: PoseLandmark, blind: Boolean): Landmark {
        if (blind) {
            return Landmark(image = null, world = null, visibility = 0.0, presence = 0.0)
        }
        val visibility = landmark.visibility ?: landmark.presence ?: 0.0
        val presence = landmark.presence ?: landmark.visibility ?: 0.0
        return Landmark(
            image = Vec3(landmark.image.x, landmark.image.y, landmark.image.z),
            world = landmark.world?.let { Vec3(it.xMeters, it.yMeters, it.zMeters) },
            visibility = visibility,
            presence = presence,
        )
    }

    private fun mergeValidity(
        input: ai.senp.core.contracts.FrameValidity,
        processed: ProcessedFrame,
    ): ai.senp.core.contracts.FrameValidity {
        val motionStatus = processed.quality.validity.toCanonicalStatus()
        val status = if (input.status.rank() >= motionStatus.rank()) input.status else motionStatus
        val confidence = when (status) {
            FrameValidityStatus.BLIND -> 0.0
            else -> minOf(input.confidence, processed.quality.score).coerceIn(0.0, 1.0)
        }
        if (status == FrameValidityStatus.VALID) {
            return ai.senp.core.contracts.FrameValidity(FrameValidityStatus.VALID, confidence)
        }

        val reasons = linkedSetOf<FrameValidityReason>()
        reasons += input.reasons
        if (motionStatus.rank() >= input.status.rank()) reasons += motionStatus.defaultReason()
        if (processed.guardrails.leftRightSwapApplied) reasons += FrameValidityReason.LEFT_RIGHT_SWAP
        if (processed.guardrails.impossibleProportions) reasons += FrameValidityReason.IMPOSSIBLE_PROPORTION
        if (reasons.isEmpty()) reasons += status.defaultReason()
        return ai.senp.core.contracts.FrameValidity(status, confidence, reasons)
    }

    private fun resolveProfile(version: String): ProfileBinding? {
        val normalized = version.lowercase().replace('-', '_')
        val baseVersion = MotionCoreVersions.EXERCISE_PROFILES.replace('-', '_')
        val id = when {
            normalized == baseVersion -> "generic"
            normalized.startsWith("$baseVersion/") -> normalized.removePrefix("$baseVersion/")
            normalized.endsWith("/1") && normalized.count { it == '/' } == 1 -> normalized.substringBeforeLast('/')
            '/' !in normalized -> normalized
            else -> return null
        }
        return when (id) {
            "generic" -> ProfileBinding(ExerciseProfiles.generic)
            "biceps_curl", "biceps_curls" -> ProfileBinding(ExerciseProfiles.bicepsCurl)
            "pushup", "pushups", "push_up", "push_ups" -> ProfileBinding(ExerciseProfiles.pushup)
            "squat", "squats" -> ProfileBinding(ExerciseProfiles.squat)
            "leg_raise", "leg_raises" -> ProfileBinding(ExerciseProfiles.legRaise)
            "plank", "planks" -> ProfileBinding(ExerciseProfiles.plank)
            "pullup", "pullups", "pull_up", "pull_ups" -> ProfileBinding(ExerciseProfiles.pullup)
            else -> null
        }
    }

    private data class ProfileBinding(val profile: ExerciseProfile)
}

private fun Boolean.toDouble(): Double = if (this) 1.0 else 0.0

private fun FrameValidityStatus.rank(): Int = when (this) {
    FrameValidityStatus.VALID -> 0
    FrameValidityStatus.REPAIRED -> 1
    FrameValidityStatus.DEGRADED -> 2
    FrameValidityStatus.BLIND -> 3
    FrameValidityStatus.CONTINUITY_BREAK -> 4
}

private fun FrameValidityStatus.defaultReason(): FrameValidityReason = when (this) {
    FrameValidityStatus.VALID -> error("valid frames do not have invalidity reasons")
    FrameValidityStatus.REPAIRED -> FrameValidityReason.SHORT_GAP_INTERPOLATION
    FrameValidityStatus.DEGRADED -> FrameValidityReason.UNUSABLE_TRACKING
    FrameValidityStatus.BLIND -> FrameValidityReason.LONG_GAP
    FrameValidityStatus.CONTINUITY_BREAK -> FrameValidityReason.TRACKING_RESET
}

private fun FrameValidityStatus.toInternalValidity(): FrameValidity = when (this) {
    FrameValidityStatus.VALID -> FrameValidity.VALID
    FrameValidityStatus.REPAIRED -> FrameValidity.REPAIRED
    FrameValidityStatus.DEGRADED -> FrameValidity.DEGRADED
    FrameValidityStatus.BLIND -> FrameValidity.BLIND
    FrameValidityStatus.CONTINUITY_BREAK -> FrameValidity.CONTINUITY_BREAK
}

private fun FrameValidity.toCanonicalStatus(): FrameValidityStatus = when (this) {
    FrameValidity.VALID -> FrameValidityStatus.VALID
    FrameValidity.REPAIRED -> FrameValidityStatus.REPAIRED
    FrameValidity.DEGRADED -> FrameValidityStatus.DEGRADED
    FrameValidity.BLIND -> FrameValidityStatus.BLIND
    FrameValidity.CONTINUITY_BREAK -> FrameValidityStatus.CONTINUITY_BREAK
}
