package ai.senp.motion

import ai.senp.core.contracts.DurationMs
import ai.senp.core.contracts.ObservationSampling
import ai.senp.core.contracts.TimestampMs
import ai.senp.core.contracts.VideoRole

internal object WorkoutPrecisionTestData {
    val angles = listOf("left_shoulder", "right_shoulder", "left_elbow", "right_elbow", "left_hip", "right_hip", "left_knee", "right_knee", "left_ankle", "right_ankle", "left_body_line", "right_body_line").map { "angle.$it" }
    val distances = listOf("hand_separation", "foot_separation", "knee_separation", "elbow_separation", "left_hand_to_shoulder", "right_hand_to_shoulder", "left_hand_to_hip", "right_hand_to_hip").map { "distance.$it" }
    val proportions = listOf("left_upper_arm_over_torso", "right_upper_arm_over_torso", "left_forearm_over_torso", "right_forearm_over_torso", "left_thigh_over_torso", "right_thigh_over_torso").map { "ratio.$it" }
    fun feature(name: String): ActionFeatureProfile {
        val angle = name.startsWith("angle.")
        val median = if (angle) 90.0 else 0.8
        val allowance = if (angle) 2.0 else 0.02
        return ActionFeatureProfile(name, ActionFeatureKind.GEOMETRY,
            RobustDistribution(median, median - allowance, median + allowance, if (angle) 0.5 else 0.005, 30, 30, 0.0),
            if (angle) 5.0 else 0.03, 0.95, 0.8, 0.95, 0.8, 0.8, 0.95)
    }
    fun profile(static: Boolean = false): ActionProfile {
        val features = (angles + distances + proportions).map(::feature)
        val duration = RobustDistribution(1000.0, 800.0, 1200.0, 20.0, 30, 30, 0.0)
        val count = if (static) 1 else 3
        return ActionProfile(ReferenceActionVersions.PROFILE, !static, if (static) 0.0 else 0.95, 3,
            (0 until count).map { i -> ActionStateProfile("state_$i", i, i.toDouble() / count, (i + 1.0) / count, duration, features, 0.95) },
            if (static) emptyList() else (0 until count).map { ActionTransitionProfile(it, (it + 1) % count, it == count - 1, 0.95) },
            if (static) null else duration, features.associate { it.name to it.scale }, 0.95,
            ActionProfileValidation(1.0, 1.0, 0.95, 1.0, 0.0), static, setOf(SpatialEvidenceKind.THREE_D))
    }
    fun frame(ms: Long, profile: ActionProfile = profile(), changes: Map<String, Double> = emptyMap(), confidence: Double = 0.95): SpatialObservationFrame {
        val values = profile.states[0].features.filter { it.kind == ActionFeatureKind.GEOMETRY }.associate { it.name to it.reference.median } + changes
        return SpatialObservationFrame(TimestampMs(ms), SpatialEvidenceKind.THREE_D, null, null, null,
            SpatialIntrinsicDescriptor(values, confidence, values.keys.associateWith { confidence }), confidence, "subject", 0)
    }
    fun estimate(ms: Long, state: Int = 0, mirror: ActionMirrorMode = ActionMirrorMode.DIRECT) = ActionStateEstimate(
        TimestampMs(ms), ActionTrackingStatus.TRACKING, "state_$state", state, 0.95, 1.0, mirror, 0)
    fun sequence(profile: ActionProfile = profile(), static: Boolean = true): SpatialSequenceAnalysis {
        val frames = (0..60).map { i ->
            val changes = if (static) emptyMap() else angles.associateWith { 90.0 + 30.0 * kotlin.math.sin(i / 5.0) }
            frame(i * 50L, profile, changes)
        }
        return SpatialSequenceAnalysis(VideoRole.REFERENCE, DurationMs(3050), ObservationSampling(analysisFramesPerSecond = 20.0), frames, 1.0)
    }
}
