package ai.senp.core.pipeline

import ai.senp.alignment.AlignmentConfig
import ai.senp.alignment.AlignmentEngine as InternalAlignmentEngine
import ai.senp.alignment.ExerciseProfile
import ai.senp.alignment.FeatureRule
import ai.senp.alignment.FeatureSample as InternalFeatureSample
import ai.senp.alignment.MotionFrame
import ai.senp.alignment.MotionTrack
import ai.senp.alignment.PhaseDetector as InternalPhaseDetector
import ai.senp.alignment.PhaseState
import ai.senp.alignment.WindowKind
import ai.senp.alignment.indexAtOrAfter
import ai.senp.alignment.indexAtOrBefore
import ai.senp.alignment.medianTimestampDelta
import ai.senp.core.contracts.AlignmentPoint
import ai.senp.core.contracts.AlignmentResult
import ai.senp.core.contracts.AnalysisConfiguration
import ai.senp.core.contracts.AnalysisFailure
import ai.senp.core.contracts.FeatureSample
import ai.senp.core.contracts.FrameValidityStatus
import ai.senp.core.contracts.MotionSeries
import ai.senp.core.contracts.PhaseSegment
import ai.senp.core.contracts.PhaseSeries
import ai.senp.core.contracts.ProblemCertainty
import ai.senp.core.contracts.ProblemWindow
import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.TimestampMs
import ai.senp.core.contracts.VideoRole
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Timestamp-first canonical phase adapter backed by the proven Wave 1 detector. */
class TimestampFirstPhaseDetector : PhaseDetector {
    private val config = AlignmentConfig()

    override suspend fun detect(
        motion: MotionSeries,
        exerciseProfileVersion: String,
    ): StageResult<PhaseSeries> {
        return try {
            if (motion.features.isEmpty()) {
                StageResult.Failure(
                    AnalysisFailure.Phase(motion.role, "motion series contains no feature samples"),
                )
            } else {
                val names = canonicalFeatureNames(motion)
                val profile = InternalProfileCatalog.resolve(
                    version = exerciseProfileVersion,
                    comparisonFeatures = names,
                    phaseFeatures = names,
                ) ?: return StageResult.Failure(
                    AnalysisFailure.Phase(
                        motion.role,
                        InternalProfileCatalog.failureMessage(exerciseProfileVersion, names),
                    ),
                )
                val track = motion.toInternalTrack()
                val state = InternalPhaseDetector.detect(track, profile, config)
                StageResult.Success(state.toCanonicalPhases(track, motion.role))
            }
        } catch (exception: IllegalArgumentException) {
            StageResult.Failure(
                AnalysisFailure.Phase(motion.role, exception.message ?: "invalid phase-detector input"),
            )
        } catch (exception: IllegalStateException) {
            StageResult.Failure(
                AnalysisFailure.Phase(motion.role, exception.message ?: "phase detection failed"),
            )
        }
    }
}

/** Canonical alignment adapter preserving the proven masked phase/rep DTW implementation. */
class TimestampFirstAlignmentEngine : AlignmentEngine {
    private val config = AlignmentConfig()
    private val engine = InternalAlignmentEngine(config)

    override suspend fun align(
        sourceMotion: MotionSeries,
        sourcePhases: PhaseSeries,
        referenceMotion: MotionSeries,
        referencePhases: PhaseSeries,
        configuration: AnalysisConfiguration,
    ): StageResult<AlignmentAnalysis> {
        return try {
            validateRoles(sourceMotion, sourcePhases, referenceMotion, referencePhases)?.let {
                return StageResult.Failure(it)
            }
            if (sourceMotion.features.isEmpty() || referenceMotion.features.isEmpty()) {
                return StageResult.Failure(
                    AnalysisFailure.Alignment("source and reference motion series must contain feature samples"),
                )
            }

            val sourceNames = canonicalFeatureNames(sourceMotion)
            val referenceNames = canonicalFeatureNames(referenceMotion)
            val profile = InternalProfileCatalog.resolve(
                version = configuration.exerciseProfileVersion,
                comparisonFeatures = sourceNames + referenceNames,
                phaseFeatures = sourceNames intersect referenceNames,
            ) ?: return StageResult.Failure(
                AnalysisFailure.Alignment(
                    InternalProfileCatalog.failureMessage(
                        configuration.exerciseProfileVersion,
                        sourceNames intersect referenceNames,
                    ),
                ),
            )

            val sourceTrack = sourceMotion.toInternalTrack()
            val referenceTrack = referenceMotion.toInternalTrack()
            val sourceState = sourcePhases.toInternalState(sourceTrack, profile, config)
            val referenceState = referencePhases.toInternalState(referenceTrack, profile, config)
            val result = engine.align(
                user = sourceTrack,
                reference = referenceTrack,
                profile = profile,
                userPhaseOverride = sourceState,
                referencePhaseOverride = referenceState,
            )

            val alignment = AlignmentResult(
                mode = result.mode.name,
                points = result.mapping.map { point ->
                    AlignmentPoint(
                        sourceTimestamp = TimestampMs(point.userTimestampMs),
                        referenceTimestamp = TimestampMs(point.referenceTimestampMs),
                        localCost = point.rawDifference,
                        confidence = point.alignmentConfidence,
                    )
                },
                aggregateConfidence = result.meanAlignmentConfidence.coerceIn(0.0, 1.0),
            )
            val sourceStep = medianTimestampDelta(sourceTrack).coerceAtLeast(1L)
            val referenceStep = medianTimestampDelta(referenceTrack).coerceAtLeast(1L)
            val problems = result.windows.map { window ->
                val sourceEndExclusive = max(window.userStartMs + 1L, window.userEndMs + sourceStep)
                val referenceEndExclusive = max(
                    window.referenceStartMs + 1L,
                    window.referenceEndMs + referenceStep,
                )
                ProblemWindow(
                    sourceStart = TimestampMs(window.userStartMs),
                    sourceEndExclusive = TimestampMs(sourceEndExclusive),
                    referenceStart = TimestampMs(window.referenceStartMs),
                    referenceEndExclusive = TimestampMs(referenceEndExclusive),
                    label = when (window.kind) {
                        WindowKind.GENUINE_FORM_ERROR -> "genuine_form_error"
                        WindowKind.UNCERTAIN_ALIGNMENT -> "uncertain_alignment"
                    },
                    metric = "masked_feature_deviation",
                    meanDeviation = window.meanDifference,
                    peakDeviation = window.peakDifference,
                    severity = (window.peakDifference / (config.errorThreshold * 2.0)).coerceIn(0.0, 1.0),
                    alignmentConfidence = window.meanConfidence.coerceIn(0.0, 1.0),
                    certainty = when (window.kind) {
                        WindowKind.GENUINE_FORM_ERROR -> ProblemCertainty.GENUINE
                        WindowKind.UNCERTAIN_ALIGNMENT -> ProblemCertainty.UNCERTAIN
                    },
                )
            }
            StageResult.Success(AlignmentAnalysis(alignment, problems))
        } catch (exception: IllegalArgumentException) {
            StageResult.Failure(
                AnalysisFailure.Alignment(exception.message ?: "invalid alignment input"),
            )
        } catch (exception: IllegalStateException) {
            StageResult.Failure(
                AnalysisFailure.Alignment(exception.message ?: "alignment failed"),
            )
        }
    }

    private fun validateRoles(
        sourceMotion: MotionSeries,
        sourcePhases: PhaseSeries,
        referenceMotion: MotionSeries,
        referencePhases: PhaseSeries,
    ): AnalysisFailure.Alignment? = when {
        sourceMotion.role != VideoRole.SOURCE -> AnalysisFailure.Alignment("source motion must have SOURCE role")
        sourcePhases.role != sourceMotion.role -> AnalysisFailure.Alignment(
            "source phase role must match source motion role",
        )
        referenceMotion.role != VideoRole.REFERENCE -> AnalysisFailure.Alignment(
            "reference motion must have REFERENCE role",
        )
        referencePhases.role != referenceMotion.role -> AnalysisFailure.Alignment(
            "reference phase role must match reference motion role",
        )
        else -> null
    }
}

private fun MotionSeries.toInternalTrack(): MotionTrack {
    val anglesByTimestamp = angles.groupBy { it.timestamp.value }
    return MotionTrack(features.map { sample ->
        val blind = sample.validity.status == FrameValidityStatus.BLIND ||
            sample.validity.status == FrameValidityStatus.CONTINUITY_BREAK
        val frameConfidence = if (blind) 0.0 else sample.validity.confidence
        val mapped = linkedMapOf<String, InternalFeatureSample>()
        for ((name, value) in sample.values.toSortedMap()) {
            mapped[name] = InternalFeatureSample(
                value = if (blind) null else value,
                confidence = if (value == null || blind) 0.0 else frameConfidence,
            )
        }
        for ((joint, candidates) in anglesByTimestamp[sample.timestamp.value].orEmpty().groupBy { it.joint }) {
            if (joint in mapped) continue
            val angle = candidates.maxByOrNull { it.confidence } ?: continue
            mapped[joint] = InternalFeatureSample(
                value = if (blind) null else angle.degrees,
                confidence = if (blind) 0.0 else min(frameConfidence, angle.confidence),
            )
        }
        MotionFrame(sample.timestamp.value, mapped)
    })
}

private fun canonicalFeatureNames(motion: MotionSeries): Set<String> = buildSet {
    motion.features.forEach { addAll(it.values.keys) }
    motion.angles.forEach { add(it.joint) }
}

private fun PhaseState.toCanonicalPhases(track: MotionTrack, role: VideoRole): PhaseSeries {
    if (track.frames.size < 2 || activeEnd <= activeStart) return PhaseSeries(role, emptyList())
    val phaseAnchors = (listOf(activeStart) + anchors + listOf(activeEnd))
        .filter { it in track.frames.indices }
        .distinct()
        .sorted()
    val usableAnchors = if (phaseAnchors.size >= 2) phaseAnchors else listOf(activeStart, activeEnd)
    val phases = usableAnchors.zipWithNext().mapIndexedNotNull { segmentIndex, (startIndex, endIndex) ->
        val startMs = track.frames[startIndex].timestampMs
        val endMs = track.frames[endIndex].timestampMs
        if (endMs <= startMs) return@mapIndexedNotNull null
        val endExclusiveMs = if (segmentIndex == usableAnchors.size - 2) {
            val stepMs = medianTimestampDelta(track).coerceAtLeast(1L)
            if (endMs > Long.MAX_VALUE - stepMs) endMs else endMs + stepMs
        } else {
            endMs
        }
        val repetition = repetitionIndex(startIndex)
        val validSignalCount = phaseSignal.subList(startIndex, endIndex + 1).count { it != null }
        val coverage = validSignalCount.toDouble() / (endIndex - startIndex + 1).toDouble()
        val confidence = if (insufficientMotion) {
            min(0.35, coverage)
        } else {
            sqrt(coverage * motionStrength.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
        }
        val startValue = phaseSignal.getOrNull(startIndex)
        val endValue = phaseSignal.getOrNull(endIndex)
        val name = when {
            insufficientMotion -> "insufficient_motion"
            startValue == null || endValue == null -> "masked_motion"
            endValue >= startValue -> "ascending"
            else -> "descending"
        }
        PhaseSegment(
            name = name,
            start = TimestampMs(startMs),
            endExclusive = TimestampMs(endExclusiveMs),
            repetitionIndex = repetition,
            confidence = confidence,
        )
    }
    return PhaseSeries(role, phases)
}

private fun PhaseState.repetitionIndex(frameIndex: Int): Int {
    if (repBoundaries.size < 2) return 0
    return repBoundaries.zipWithNext().indexOfFirst { (start, end) -> frameIndex >= start && frameIndex < end }
        .let { if (it < 0) max(0, repBoundaries.size - 2) else it }
}

private fun PhaseSeries.toInternalState(
    track: MotionTrack,
    profile: ExerciseProfile,
    config: AlignmentConfig,
): PhaseState {
    val detected = InternalPhaseDetector.detect(track, profile, config)
    if (phases.isEmpty() || track.frames.size < 2) return detected

    val start = indexAtOrAfter(track, phases.first().start.value)
    val requestedEnd = (phases.last().endExclusive.value - 1L).coerceAtLeast(phases.last().start.value)
    val end = indexAtOrBefore(track, requestedEnd)
    if (start !in track.frames.indices || end !in track.frames.indices || end <= start) return detected

    val canonicalAnchors = (phases.map { indexAtOrAfter(track, it.start.value) } + end)
        .filter { it in start..end }
        .distinct()
        .sorted()
    val repetitionStarts = phases
        .filterIndexed { index, phase -> index == 0 || phase.repetitionIndex != phases[index - 1].repetitionIndex }
        .map { indexAtOrAfter(track, it.start.value) }
    val boundaries = (listOf(start) + repetitionStarts + end)
        .filter { it in start..end }
        .distinct()
        .sorted()
    val explicitlyInsufficient = phases.all { it.name == "insufficient_motion" }
    return detected.copy(
        activeStart = start,
        activeEnd = end,
        repBoundaries = if (boundaries.size >= 2) boundaries else detected.repBoundaries,
        anchors = if (canonicalAnchors.size >= 2) canonicalAnchors else detected.anchors,
        insufficientMotion = explicitlyInsufficient || detected.insufficientMotion,
    )
}

private object InternalProfileCatalog {
    private val excludedDynamics = listOf("velocity", "speed", "tempo", "timestamp", "time_seconds")
    private val angleFeatures = listOf(
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
        "torso_lean",
        "shoulder_tilt",
        "hip_tilt",
        "profile_signal",
    )

    fun resolve(
        version: String,
        comparisonFeatures: Set<String>,
        phaseFeatures: Set<String>,
    ): ExerciseProfile? {
        val normalized = version.lowercase().replace('-', '_')
        val spec = when {
            "synthetic" in normalized -> ProfileSpec(
                id = version,
                phaseCandidates = listOf("primary", "secondary"),
                weights = linkedMapOf(
                    "primary" to 1.0,
                    "secondary" to 0.70,
                    "stable" to 0.30,
                    "form" to 1.0,
                ),
            )
            "biceps" in normalized || "curl" in normalized -> exerciseSpec(
                version,
                listOf("left_elbow", "right_elbow"),
                mapOf(
                    "left_elbow" to 1.9,
                    "right_elbow" to 1.9,
                    "left_shoulder" to 1.2,
                    "right_shoulder" to 1.2,
                    "left_hip" to 1.0,
                    "right_hip" to 1.0,
                ),
            )
            "pushup" in normalized || "push_up" in normalized -> exerciseSpec(
                version,
                listOf("left_elbow", "right_elbow"),
                mapOf(
                    "left_shoulder" to 1.8,
                    "right_shoulder" to 1.8,
                    "left_elbow" to 1.3,
                    "right_elbow" to 1.3,
                    "left_hip" to 0.7,
                    "right_hip" to 0.7,
                ),
            )
            "squat" in normalized -> exerciseSpec(
                version,
                listOf("left_knee", "right_knee"),
                mapOf(
                    "left_knee" to 2.0,
                    "right_knee" to 2.0,
                    "left_hip" to 1.4,
                    "right_hip" to 1.4,
                    "left_shoulder" to 0.6,
                    "right_shoulder" to 0.6,
                ),
            )
            "leg_raise" in normalized || "legraise" in normalized -> exerciseSpec(
                version,
                listOf("left_hip", "right_hip", "left_knee", "right_knee"),
                mapOf(
                    "left_hip" to 1.6,
                    "right_hip" to 1.6,
                    "left_knee" to 1.0,
                    "right_knee" to 1.0,
                    "left_shoulder" to 0.5,
                    "right_shoulder" to 0.5,
                ),
            )
            "lat_pullover" in normalized || "pullover" in normalized -> exerciseSpec(
                version,
                listOf("left_shoulder", "right_shoulder"),
                mapOf(
                    "left_shoulder" to 2.0,
                    "right_shoulder" to 2.0,
                    "left_elbow" to 1.0,
                    "right_elbow" to 1.0,
                    "left_hip" to 0.6,
                    "right_hip" to 0.6,
                ),
            )
            "pullup" in normalized || "pull_up" in normalized -> exerciseSpec(
                version,
                listOf("left_elbow", "right_elbow", "left_shoulder", "right_shoulder"),
                mapOf(
                    "left_elbow" to 1.7,
                    "right_elbow" to 1.7,
                    "left_shoulder" to 1.4,
                    "right_shoulder" to 1.4,
                    "left_hip" to 0.7,
                    "right_hip" to 0.7,
                ),
            )
            "plank" in normalized -> exerciseSpec(
                version,
                listOf("left_shoulder", "right_shoulder", "left_hip", "right_hip"),
                mapOf(
                    "left_shoulder" to 1.2,
                    "right_shoulder" to 1.2,
                    "left_hip" to 1.2,
                    "right_hip" to 1.2,
                    "left_knee" to 0.5,
                    "right_knee" to 0.5,
                ),
            )
            normalized == "generic" ||
                normalized.startsWith("generic/") ||
                normalized == "exercise_profiles/1" ||
                normalized.startsWith("exercise_profiles/") -> {
                val supported = comparisonFeatures.filter { feature ->
                    feature in angleFeatures && !isExcludedDynamic(feature)
                }
                val fallback = comparisonFeatures.filterNot(::isExcludedDynamic).sorted()
                val names = (supported + fallback).distinct()
                ProfileSpec(
                    id = version,
                    phaseCandidates = listOf(
                        "profile_signal",
                        "left_knee",
                        "right_knee",
                        "left_elbow",
                        "right_elbow",
                        "left_shoulder",
                        "right_shoulder",
                        "left_hip",
                        "right_hip",
                    ) + names,
                    weights = names.associateWithTo(linkedMapOf()) { 1.0 },
                )
            }
            else -> null
        } ?: return null

        val comparableRules = spec.weights.filterKeys { it in comparisonFeatures && !isExcludedDynamic(it) }
        if (comparableRules.isEmpty()) return null
        val primary = spec.phaseCandidates.firstOrNull { it in phaseFeatures && it in comparableRules }
            ?: comparableRules.keys.firstOrNull { it in phaseFeatures }
            ?: return null
        val secondary = spec.phaseCandidates.firstOrNull { it != primary && it in phaseFeatures && it in comparableRules }
        return ExerciseProfile(
            id = spec.id,
            featureRules = comparableRules.mapValues { (name, weight) ->
                FeatureRule(
                    weight = weight,
                    distanceScale = 30.0,
                    phaseWeight = when (name) {
                        primary -> 1.0
                        secondary -> 0.30
                        else -> 0.0
                    },
                    minimumMotionRange = 8.0,
                )
            },
            minimumFeatureConfidence = 0.45,
        )
    }

    fun failureMessage(version: String, features: Set<String>): String =
        "exercise profile '$version' has no common supported non-dynamic features; available=${features.sorted()}"

    private fun exerciseSpec(version: String, phaseCandidates: List<String>, weights: Map<String, Double>) =
        ProfileSpec(version, phaseCandidates, weights)

    private fun isExcludedDynamic(name: String): Boolean {
        val normalized = name.lowercase()
        return excludedDynamics.any { it in normalized }
    }

    private data class ProfileSpec(
        val id: String,
        val phaseCandidates: List<String>,
        val weights: Map<String, Double>,
    )
}
