package ai.senp.motion

import ai.senp.core.contracts.CanonicalObservationSequence
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class ReferenceActionCompiler(
    private val config: ReferenceActionCompilerConfig = ReferenceActionCompilerConfig(),
) {
    fun compile(reference: CanonicalObservationSequence): ReferenceActionCompilation =
        compile(SpatialSynchronizationEngine().analyzeSequence(reference))

    fun compile(output: SpatialSynchronizationOutput): ReferenceActionCompilation = compile(output.reference)

    fun compile(reference: SpatialSequenceAnalysis): ReferenceActionCompilation {
        val allFrames = reference.frames
        val samples = allFrames.mapNotNull { frame ->
            val descriptor = frame.intrinsicDescriptor
            if (descriptor.confidence < config.minimumFrameConfidence || descriptor.values.isEmpty()) {
                null
            } else {
                ActionSample(frame.timestamp.value, descriptor.values, descriptor.confidence)
            }
        }
        if (samples.size < config.minimumAnalyzableFrames) {
            return ReferenceActionCompilation.Failure(
                ReferenceActionCompilationFailureReason.INSUFFICIENT_ANALYZABLE_FRAMES,
                "reference has ${samples.size} analyzable frames; ${config.minimumAnalyzableFrames} required",
            )
        }
        val usableSpanMs = samples.last().timestampMs - samples.first().timestampMs
        if (usableSpanMs < config.minimumReferenceDurationMs) {
            return ReferenceActionCompilation.Failure(
                ReferenceActionCompilationFailureReason.INSUFFICIENT_DURATION,
                "reference usable span $usableSpanMs ms is below ${config.minimumReferenceDurationMs} ms",
            )
        }
        val frameAnalyzableFraction = samples.size.toDouble() / max(1, allFrames.size).toDouble()
        val analyzableFraction = min(reference.analyzableFraction, frameAnalyzableFraction)
        if (analyzableFraction < config.minimumAnalyzableFraction) {
            return ReferenceActionCompilation.Failure(
                ReferenceActionCompilationFailureReason.LOW_REFERENCE_CONFIDENCE,
                "reference analyzable fraction $analyzableFraction is below ${config.minimumAnalyzableFraction}",
            )
        }

        val featureNames = samples.flatMap { it.values.keys }.groupingBy { it }.eachCount()
            .filterValues { count -> count.toDouble() / samples.size.toDouble() >= config.minimumFeatureObservability }
            .keys
            .sorted()
        if (featureNames.size < config.minimumFeatureCount) {
            return ReferenceActionCompilation.Failure(
                ReferenceActionCompilationFailureReason.INSUFFICIENT_FEATURES,
                "reference exposes only ${featureNames.size} stable intrinsic features",
            )
        }
        val filteredSamples = samples.map { sample ->
            sample.copy(values = sample.values.filterKeys { it in featureNames })
        }
        val featureScales = featureNames.associateWith { feature ->
            robustScale(filteredSamples.mapNotNull { it.values[feature] }, feature)
        }
        val trends = buildTrends(filteredSamples, featureScales)
        val hasPhaseMotion = hasSufficientCyclicMotion(filteredSamples, featureNames)
        val cycles = if (hasPhaseMotion) {
            detectCycles(filteredSamples, trends)
        } else {
            null
        }
        val stateCount = if (hasPhaseMotion) {
            min(
                config.targetStateCount,
                max(config.minimumStateCount, filteredSamples.size / config.minimumSamplesPerState),
            )
        } else {
            1
        }
        val assignment = if (cycles != null) {
            assignCyclicStates(filteredSamples, cycles.boundariesMs, stateCount)
        } else {
            assignFiniteStates(filteredSamples, stateCount)
        }
        if (assignment.assignments.size < config.minimumAnalyzableFrames || assignment.groups.any { it.isEmpty() }) {
            return ReferenceActionCompilation.Failure(
                ReferenceActionCompilationFailureReason.INSUFFICIENT_ANALYZABLE_FRAMES,
                "reference does not contain enough usable samples across the discovered action states",
            )
        }

        val stateMedians = assignment.groups.map { group ->
            featureNames.mapNotNull { feature ->
                val values = group.mapNotNull { it.values[feature] }
                if (values.isEmpty()) null else feature to median(values)
            }.toMap()
        }
        val stateTrendMedians = assignment.groups.map { group ->
            val timestamps = group.mapTo(hashSetOf()) { it.timestampMs }
            featureNames.mapNotNull { feature ->
                val values = timestamps.mapNotNull { timestamp -> trends[timestamp]?.get(feature) }
                if (values.isEmpty()) null else feature to median(values)
            }.toMap()
        }
        val states = assignment.groups.mapIndexed { stateIndex, group ->
            buildState(
                stateIndex = stateIndex,
                stateCount = assignment.groups.size,
                group = group,
                featureNames = featureNames,
                featureScales = featureScales,
                trends = trends,
                stateMedians = stateMedians,
                stateTrendMedians = stateTrendMedians,
                stateDurationsMs = assignment.stateDurationsMs[stateIndex],
                repeatedEvidence = cycles != null,
            )
        }
        val transitions = buildTransitions(states, cycles != null, cycles?.confidence ?: 1.0)
        val cycleDistribution = cycles?.let { cycle ->
            robustDistribution(cycle.cycleDurationsMs.map(Long::toDouble), config.minimumTimingScaleMs)
        }
        val referenceOutlierFraction = states.flatMap(ActionStateProfile::features)
            .map { it.reference.outlierFraction }
            .averageOrZeroAction()
            .coerceIn(0.0, 1.0)
        val stateConfidence = states.map(ActionStateProfile::confidence).averageOrZeroAction()
        val cyclicityConfidence = cycles?.confidence ?: 0.0
        val structuralConfidence = if (cycles != null) cyclicityConfidence else config.nonCyclicStructuralConfidence
        val confidence = (
            analyzableFraction *
                filteredSamples.map(ActionSample::confidence).averageOrZeroAction() *
                (0.55 + 0.45 * stateConfidence) *
                structuralConfidence
            ).coerceIn(0.0, 1.0)
        val placeholderValidation = ActionProfileValidation(
            reconstructionAccuracy = 0.0,
            transitionCoverage = 0.0,
            meanRecognitionConfidence = 0.0,
            analyzableFraction = analyzableFraction.coerceIn(0.0, 1.0),
            referenceOutlierFraction = referenceOutlierFraction,
        )
        val baseProfile = ActionProfile(
            version = ReferenceActionVersions.PROFILE,
            cyclic = cycles != null,
            cyclicityConfidence = cyclicityConfidence,
            referenceRepetitions = cycles?.cycleDurationsMs?.size ?: 1,
            states = states,
            transitions = transitions,
            cycleDurationMs = cycleDistribution,
            featureScales = featureScales,
            confidence = confidence,
            validation = placeholderValidation,
        )
        val validation = validateProfile(
            profile = baseProfile,
            reference = reference,
            assignments = assignment.assignments,
            trends = trends,
            analyzableFraction = analyzableFraction,
            referenceOutlierFraction = referenceOutlierFraction,
        )
        return ReferenceActionCompilation.Success(baseProfile.copy(validation = validation))
    }

    private fun buildTrends(
        samples: List<ActionSample>,
        featureScales: Map<String, Double>,
    ): Map<Long, Map<String, Double>> {
        val result = linkedMapOf<Long, Map<String, Double>>()
        var previous: ActionSample? = null
        for (sample in samples) {
            val prior = previous?.takeIf { sample.timestampMs - it.timestampMs <= config.maximumTrendGapMs }
            result[sample.timestampMs] = actionTrend(prior?.values, sample.values, featureScales)
            previous = sample
        }
        return result
    }

    private fun hasSufficientCyclicMotion(
        samples: List<ActionSample>,
        featureNames: List<String>,
    ): Boolean {
        if (samples.isEmpty() || featureNames.isEmpty()) return false
        val movingFeatures = featureNames.count { feature ->
            val values = samples.mapNotNull { it.values[feature] }
            if (values.size < config.minimumAnalyzableFrames) {
                false
            } else {
                val robustRange = quantile(values, 0.90) - quantile(values, 0.10)
                robustRange >= semanticFeatureFloor(feature) * config.minimumCyclicMotionRangeFloorMultiples
            }
        }
        val requiredMovingFeatures = max(
            config.minimumCyclicMovingFeatureCount,
            ceil(featureNames.size * config.minimumCyclicMovingFeatureFraction).toInt(),
        )
        return movingFeatures >= requiredMovingFeatures
    }

    private fun detectCycles(
        samples: List<ActionSample>,
        trends: Map<Long, Map<String, Double>>,
    ): CycleDetection? {
        val start = samples.first().timestampMs
        val end = samples.last().timestampMs
        val span = end - start
        if (span < config.minimumCycleDurationMs * 2L) return null
        val candidates = mutableListOf<Double>()
        val maximumCycles = min(config.maximumReferenceCycles, (span / config.minimumCycleDurationMs).toInt())
        for (count in 2..maximumCycles) {
            candidates += span.toDouble() / count.toDouble()
        }
        val stride = max(1, samples.size / config.maximumRecurrenceSamples)
        val recurrenceSamples = samples.indices.filter { it % stride == 0 }.map(samples::get)
        for (leftIndex in recurrenceSamples.indices) {
            val left = recurrenceSamples[leftIndex]
            for (rightIndex in leftIndex + 1 until recurrenceSamples.size) {
                val right = recurrenceSamples[rightIndex]
                val duration = right.timestampMs - left.timestampMs
                if (duration < config.minimumCycleDurationMs || duration > span * 3L / 4L) continue
                val distance = robustActionDescriptorDistance(left.values, right.values) ?: continue
                if (distance <= config.recurrenceCandidateDistance) candidates += duration.toDouble()
            }
        }
        val clustered = clusterPeriods(candidates)
        if (clustered.isEmpty()) return null
        val scored = clustered.map { period -> period to scorePeriod(period, samples, trends) }
            .filter { (_, score) -> score.coverage >= config.minimumPeriodCoverage }
        val bestScore = scored.maxOfOrNull { it.second.score } ?: return null
        if (bestScore < config.minimumCyclicityScore) return null
        val selected = scored
            .filter { (_, score) -> score.score >= bestScore * config.periodNearBestFraction }
            .minByOrNull { it.first }
            ?: return null
        val period = selected.first
        val anchorResult = findCycleBoundaries(period, samples, trends) ?: return null
        if (anchorResult.boundariesMs.size < 3) return null
        val durations = anchorResult.boundariesMs.zipWithNext { left, right -> right - left }
        val consistency = durationConsistency(durations)
        val confidence = (
            selected.second.score * 0.60 +
                anchorResult.anchorConfidence * 0.25 +
                consistency * 0.15
            ).coerceIn(0.0, 1.0)
        if (confidence < config.minimumCyclicityScore) return null
        return CycleDetection(anchorResult.boundariesMs, durations, confidence)
    }

    private fun clusterPeriods(raw: List<Double>): List<Double> {
        if (raw.isEmpty()) return emptyList()
        val sorted = raw.filter { it >= config.minimumCycleDurationMs.toDouble() }.sorted()
        if (sorted.isEmpty()) return emptyList()
        val clusters = mutableListOf<MutableList<Double>>()
        for (period in sorted) {
            val last = clusters.lastOrNull()
            val center = last?.let(::median)
            if (last == null || center == null || abs(period - center) / max(center, 1.0) > config.periodClusterRelativeWidth) {
                clusters += mutableListOf(period)
            } else {
                last += period
            }
        }
        return clusters.map(::median).distinct()
    }

    private fun scorePeriod(
        periodMs: Double,
        samples: List<ActionSample>,
        trends: Map<Long, Map<String, Double>>,
    ): PeriodScore {
        val end = samples.last().timestampMs
        val stride = max(1, samples.size / config.maximumPeriodScoreSamples)
        val distances = mutableListOf<Double>()
        var eligible = 0
        for (index in samples.indices step stride) {
            val sample = samples[index]
            val target = sample.timestampMs + periodMs
            if (target > end) continue
            eligible += 1
            val nearest = nearestSample(samples, target) ?: continue
            val tolerance = max(config.minimumPeriodTimestampToleranceMs, periodMs * config.periodTimestampToleranceFraction)
            if (abs(nearest.timestampMs.toDouble() - target) > tolerance) continue
            val geometry = robustActionDescriptorDistance(sample.values, nearest.values) ?: continue
            val directionPenalty = trajectoryOpposition(trends[sample.timestampMs], trends[nearest.timestampMs])
            distances += geometry + config.recurrenceDirectionPenalty * directionPenalty
        }
        if (eligible == 0 || distances.isEmpty()) return PeriodScore(0.0, 0.0)
        val coverage = distances.size.toDouble() / eligible.toDouble()
        val typicalDistance = median(distances)
        val score = coverage * exp(-typicalDistance / config.recurrenceScoreScale)
        return PeriodScore(score.coerceIn(0.0, 1.0), coverage.coerceIn(0.0, 1.0))
    }

    private fun findCycleBoundaries(
        periodMs: Double,
        samples: List<ActionSample>,
        trends: Map<Long, Map<String, Double>>,
    ): AnchorResult? {
        val firstWindowEnd = samples.first().timestampMs + min(periodMs, (samples.last().timestampMs - samples.first().timestampMs) * 0.30)
        val candidates = samples.filter { it.timestampMs <= firstWindowEnd }
            .filter { it.confidence >= config.minimumFrameConfidence }
            .let { candidateSamples ->
                val stride = max(1, candidateSamples.size / config.maximumAnchorCandidates)
                candidateSamples.indices.filter { it % stride == 0 }.map(candidateSamples::get)
            }
        var best: AnchorResult? = null
        for (anchor in candidates) {
            val result = followAnchor(anchor, periodMs, samples, trends)
            if (result.boundariesMs.size < 3) continue
            if (best == null || result.quality > best.quality) best = result
        }
        return best
    }

    private fun followAnchor(
        anchor: ActionSample,
        periodMs: Double,
        samples: List<ActionSample>,
        trends: Map<Long, Map<String, Double>>,
    ): AnchorResult {
        val boundaries = mutableListOf(anchor.timestampMs)
        val matchDistances = mutableListOf<Double>()
        var current = anchor
        while (true) {
            val minTimestamp = current.timestampMs + (periodMs * config.anchorMinPeriodFraction).toLong()
            val maxTimestamp = current.timestampMs + (periodMs * config.anchorMaxPeriodFraction).toLong()
            val candidates = samples.asSequence()
                .filter { it.timestampMs in minTimestamp..maxTimestamp }
                .mapNotNull { candidate ->
                    val geometry = robustActionDescriptorDistance(anchor.values, candidate.values)
                        ?: return@mapNotNull null
                    val directionPenalty = trajectoryOpposition(trends[anchor.timestampMs], trends[candidate.timestampMs])
                    val intervalPenalty = abs((candidate.timestampMs - current.timestampMs).toDouble() / periodMs - 1.0)
                    val combined = geometry + config.anchorDirectionPenalty * directionPenalty +
                        config.anchorDurationPenalty * intervalPenalty
                    AnchorMatch(candidate, geometry, combined)
                }
                .filter { it.geometryDistance <= config.anchorMaximumGeometryDistance }
                .minByOrNull(AnchorMatch::combinedDistance)
                ?: break
            if (candidates.combinedDistance > config.anchorMaximumCombinedDistance) break
            current = candidates.sample
            boundaries += current.timestampMs
            matchDistances += candidates.geometryDistance
        }
        val expected = max(2.0, (samples.last().timestampMs - anchor.timestampMs).toDouble() / periodMs)
        val occurrenceCoverage = ((boundaries.size - 1).toDouble() / expected).coerceIn(0.0, 1.0)
        val similarity = if (matchDistances.isEmpty()) 0.0 else exp(-median(matchDistances) / config.recurrenceScoreScale)
        val anchorConfidence = (0.55 * occurrenceCoverage + 0.45 * similarity).coerceIn(0.0, 1.0)
        return AnchorResult(boundaries, anchorConfidence, boundaries.size.toDouble() + anchorConfidence)
    }

    private fun assignCyclicStates(
        samples: List<ActionSample>,
        boundariesMs: List<Long>,
        stateCount: Int,
    ): StateAssignment {
        val assignments = mutableListOf<AssignedSample>()
        val groups = List(stateCount) { mutableListOf<ActionSample>() }
        val stateDurations = List(stateCount) { mutableListOf<Double>() }
        boundariesMs.zipWithNext().forEach { (start, end) ->
            val duration = end - start
            if (duration <= 0L) return@forEach
            repeat(stateCount) { stateDurations[it] += duration.toDouble() / stateCount.toDouble() }
            samples.asSequence().filter { it.timestampMs >= start && it.timestampMs < end }.forEach { sample ->
                val phase = (sample.timestampMs - start).toDouble() / duration.toDouble()
                val state = min(stateCount - 1, floor(phase * stateCount.toDouble()).toInt())
                groups[state] += sample
                assignments += AssignedSample(sample, state)
            }
        }
        return StateAssignment(assignments.sortedBy { it.sample.timestampMs }, groups, stateDurations)
    }

    private fun assignFiniteStates(samples: List<ActionSample>, stateCount: Int): StateAssignment {
        val start = samples.first().timestampMs
        val end = samples.last().timestampMs
        val span = max(1L, end - start)
        val groups = List(stateCount) { mutableListOf<ActionSample>() }
        val assignments = samples.map { sample ->
            val phase = (sample.timestampMs - start).toDouble() / span.toDouble()
            val state = min(stateCount - 1, floor(phase.coerceIn(0.0, 0.999999) * stateCount.toDouble()).toInt())
            groups[state] += sample
            AssignedSample(sample, state)
        }
        val stateDuration = span.toDouble() / stateCount.toDouble()
        val durations = List(stateCount) { mutableListOf(stateDuration) }
        return StateAssignment(assignments, groups, durations)
    }

    private fun buildState(
        stateIndex: Int,
        stateCount: Int,
        group: List<ActionSample>,
        featureNames: List<String>,
        featureScales: Map<String, Double>,
        trends: Map<Long, Map<String, Double>>,
        stateMedians: List<Map<String, Double>>,
        stateTrendMedians: List<Map<String, Double>>,
        stateDurationsMs: List<Double>,
        repeatedEvidence: Boolean,
    ): ActionStateProfile {
        val geometryProfiles = featureNames.mapNotNull { feature ->
            val values = group.mapNotNull { it.values[feature] }
            if (values.isEmpty()) return@mapNotNull null
            val scale = featureScales.getValue(feature)
            val distribution = robustDistribution(values, semanticFeatureFloor(feature))
            val stateScale = max(
                semanticFeatureFloor(feature),
                max(1.4826 * distribution.mad, (distribution.upper - distribution.lower) / 3.0),
            )
            val observability = (values.size.toDouble() / group.size.toDouble()).coerceIn(0.0, 1.0) *
                group.map(ActionSample::confidence).averageOrZeroAction()
            val repeatability = (1.0 / (1.0 + 1.4826 * distribution.mad / scale)).coerceIn(0.0, 1.0)
            val medians = stateMedians.mapNotNull { it[feature] }
            val motionRelevance = if (medians.size >= 2) {
                ((medians.maxOrNull()!! - medians.minOrNull()!!) / (scale * 3.0)).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val currentMedian = stateMedians[stateIndex][feature] ?: distribution.median
            val discrimination = stateMedians.mapIndexedNotNull { otherIndex, map ->
                if (otherIndex == stateIndex) null else map[feature]?.let { abs(it - currentMedian) / (scale * 2.5) }
            }.minOrNull()?.coerceIn(0.0, 1.0) ?: 0.0
            val importance = (observability * (
                0.35 * repeatability + 0.30 * motionRelevance + 0.35 * discrimination
                )).coerceIn(0.0, 1.0)
            ActionFeatureProfile(
                name = feature,
                kind = ActionFeatureKind.GEOMETRY,
                reference = distribution,
                scale = stateScale,
                repeatability = repeatability,
                motionRelevance = motionRelevance,
                observability = observability,
                stateDiscrimination = discrimination,
                importance = importance,
                confidence = (observability * repeatability).coerceIn(0.0, 1.0),
            )
        }
        val timestamps = group.mapTo(hashSetOf()) { it.timestampMs }
        val trajectoryProfiles = featureNames.mapNotNull { feature ->
            val values = timestamps.mapNotNull { timestamp -> trends[timestamp]?.get(feature) }
            if (values.size < max(2, group.size / 3)) return@mapNotNull null
            val distribution = robustDistribution(values, 0.10)
            val nonZeroFraction = values.count { abs(it) > 0.5 }.toDouble() / values.size.toDouble()
            if (nonZeroFraction < config.minimumTrajectoryMotionFraction) return@mapNotNull null
            val repeatability = (1.0 - distribution.outlierFraction).coerceIn(0.0, 1.0)
            val observability = (values.size.toDouble() / group.size.toDouble()).coerceIn(0.0, 1.0) *
                group.map(ActionSample::confidence).averageOrZeroAction()
            val currentMedian = stateTrendMedians[stateIndex][feature] ?: distribution.median
            val discrimination = stateTrendMedians.mapIndexedNotNull { otherIndex, map ->
                if (otherIndex == stateIndex) null else map[feature]?.let { abs(it - currentMedian) / 2.0 }
            }.maxOrNull()?.coerceIn(0.0, 1.0) ?: 0.0
            val importance = (observability * nonZeroFraction * (0.55 * repeatability + 0.45 * discrimination))
                .coerceIn(0.0, 1.0)
            ActionFeatureProfile(
                name = "trajectory.$feature",
                kind = ActionFeatureKind.TRAJECTORY,
                reference = distribution,
                scale = 1.0,
                repeatability = repeatability,
                motionRelevance = nonZeroFraction.coerceIn(0.0, 1.0),
                observability = observability,
                stateDiscrimination = discrimination,
                importance = importance,
                confidence = (observability * repeatability).coerceIn(0.0, 1.0),
            )
        }
        val features = geometryProfiles + trajectoryProfiles
        val featureConfidence = weightedMean(features.map { it.confidence to max(it.importance, 0.05) })
        val stateConfidence = (featureConfidence * group.map(ActionSample::confidence).averageOrZeroAction()).coerceIn(0.0, 1.0)
        val durationDistribution = robustDistribution(
            stateDurationsMs.ifEmpty { listOf(1.0) },
            config.minimumTimingScaleMs,
            widenSingleSample = !repeatedEvidence,
        )
        return ActionStateProfile(
            id = "state_${stateIndex.toString().padStart(2, '0')}",
            index = stateIndex,
            phaseStart = stateIndex.toDouble() / stateCount.toDouble(),
            phaseEndExclusive = (stateIndex + 1).toDouble() / stateCount.toDouble(),
            durationMs = durationDistribution,
            features = features,
            confidence = stateConfidence,
        )
    }

    private fun buildTransitions(
        states: List<ActionStateProfile>,
        cyclic: Boolean,
        structuralConfidence: Double,
    ): List<ActionTransitionProfile> {
        val transitions = states.zipWithNext { left, right ->
            ActionTransitionProfile(
                fromStateIndex = left.index,
                toStateIndex = right.index,
                cyclicWrap = false,
                confidence = (min(left.confidence, right.confidence) * structuralConfidence).coerceIn(0.0, 1.0),
            )
        }.toMutableList()
        if (cyclic) {
            transitions += ActionTransitionProfile(
                fromStateIndex = states.lastIndex,
                toStateIndex = 0,
                cyclicWrap = true,
                confidence = (min(states.last().confidence, states.first().confidence) * structuralConfidence).coerceIn(0.0, 1.0),
            )
        }
        return transitions
    }

    private fun validateProfile(
        profile: ActionProfile,
        reference: SpatialSequenceAnalysis,
        assignments: List<AssignedSample>,
        trends: Map<Long, Map<String, Double>>,
        analyzableFraction: Double,
        referenceOutlierFraction: Double,
    ): ActionProfileValidation {
        val assignedMatches = assignments.map { assignment ->
            val trajectory = trends[assignment.sample.timestampMs].orEmpty()
            matchActionStates(
                profile = profile,
                geometry = assignment.sample.values,
                trajectory = trajectory,
                candidateStates = listOf(assignment.stateIndex),
                mirrorModes = listOf(ActionMirrorMode.DIRECT),
            ).firstOrNull()
        }
        val accuracy = if (assignedMatches.isEmpty()) 0.0 else assignedMatches.count { match ->
            match != null &&
                match.score >= config.minimumSelfReconstructionScore &&
                match.coverage >= config.minimumSelfReconstructionFeatureCoverage
        }.toDouble() / assignedMatches.size.toDouble()
        val meanConfidence = assignedMatches.mapNotNull { it?.score }.averageOrZeroAction().coerceIn(0.0, 1.0)
        val selfRecognition = ActionStateRecognizer(profile).recognize(reference)
        val firstTrackedEstimateIndex = selfRecognition.estimates.indexOfFirst { estimate ->
            estimate.status == ActionTrackingStatus.TRACKING ||
                (estimate.status == ActionTrackingStatus.COMPLETED && estimate.stateIndex != null)
        }
        val confirmedEntryOrigin = if (firstTrackedEstimateIndex > 0) {
            selfRecognition.estimates
                .subList(0, firstTrackedEstimateIndex)
                .asReversed()
                .firstOrNull { estimate ->
                    estimate.status == ActionTrackingStatus.POSSIBLE_ENTRY && estimate.stateIndex != null
                }
                ?.stateIndex
        } else {
            null
        }
        val recognizedStatePath = buildList {
            confirmedEntryOrigin?.let(::add)
            if (firstTrackedEstimateIndex >= 0) {
                selfRecognition.estimates
                    .drop(firstTrackedEstimateIndex)
                    .asSequence()
                    .filter { estimate ->
                        estimate.status == ActionTrackingStatus.TRACKING ||
                            (estimate.status == ActionTrackingStatus.COMPLETED && estimate.stateIndex != null)
                    }
                    .mapNotNull(ActionStateEstimate::stateIndex)
                    .forEach(::add)
            }
        }.fold(mutableListOf<Int>()) { acc, state ->
            if (acc.lastOrNull() != state) acc += state
            acc
        }
        val recognizedTransitions = recognizedStatePath.zipWithNext().flatMap { (from, to) ->
            expandedValidationTransitions(profile, from, to)
        }.toSet()
        val expectedTransitions = profile.transitions.map { it.fromStateIndex to it.toStateIndex }.toSet()
        val transitionCoverage = if (expectedTransitions.isEmpty()) 1.0 else {
            expectedTransitions.count { it in recognizedTransitions }.toDouble() / expectedTransitions.size.toDouble()
        }
        return ActionProfileValidation(
            reconstructionAccuracy = accuracy.coerceIn(0.0, 1.0),
            transitionCoverage = transitionCoverage.coerceIn(0.0, 1.0),
            meanRecognitionConfidence = meanConfidence,
            analyzableFraction = analyzableFraction.coerceIn(0.0, 1.0),
            referenceOutlierFraction = referenceOutlierFraction.coerceIn(0.0, 1.0),
        )
    }

    private fun expandedValidationTransitions(
        profile: ActionProfile,
        from: Int,
        to: Int,
    ): List<Pair<Int, Int>> {
        if (from == to || profile.states.isEmpty()) return emptyList()
        val stateCount = profile.states.size
        val forwardSteps = if (profile.cyclic) {
            (to - from + stateCount) % stateCount
        } else {
            to - from
        }
        if (forwardSteps !in 1..config.maximumValidationStateSkip) {
            return listOf(from to to)
        }
        return (0 until forwardSteps).map { step ->
            val left = if (profile.cyclic) (from + step) % stateCount else from + step
            val right = if (profile.cyclic) (from + step + 1) % stateCount else from + step + 1
            left to right
        }
    }

    private fun nearestSample(samples: List<ActionSample>, targetTimestampMs: Double): ActionSample? {
        if (samples.isEmpty()) return null
        var low = 0
        var high = samples.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (samples[mid].timestampMs.toDouble() < targetTimestampMs) low = mid + 1 else high = mid - 1
        }
        val candidates = listOf(low - 1, low).filter { it in samples.indices }.map(samples::get)
        return candidates.minByOrNull { abs(it.timestampMs.toDouble() - targetTimestampMs) }
    }

    private fun trajectoryOpposition(left: Map<String, Double>?, right: Map<String, Double>?): Double {
        if (left == null || right == null) return 0.0
        var comparable = 0
        var opposite = 0
        for ((feature, leftTrend) in left) {
            val rightTrend = right[feature] ?: continue
            if (abs(leftTrend) < 0.5 || abs(rightTrend) < 0.5) continue
            comparable += 1
            if (leftTrend * rightTrend < 0.0) opposite += 1
        }
        return if (comparable == 0) 0.0 else opposite.toDouble() / comparable.toDouble()
    }

    private fun durationConsistency(durations: List<Long>): Double {
        if (durations.size <= 1) return 0.65
        val values = durations.map(Long::toDouble)
        val center = median(values)
        if (center <= 0.0) return 0.0
        val mad = median(values.map { abs(it - center) })
        return (1.0 / (1.0 + 2.5 * mad / center)).coerceIn(0.0, 1.0)
    }

    private data class PeriodScore(val score: Double, val coverage: Double)

    private data class AnchorMatch(
        val sample: ActionSample,
        val geometryDistance: Double,
        val combinedDistance: Double,
    )

    private data class AnchorResult(
        val boundariesMs: List<Long>,
        val anchorConfidence: Double,
        val quality: Double,
    )

    private data class CycleDetection(
        val boundariesMs: List<Long>,
        val cycleDurationsMs: List<Long>,
        val confidence: Double,
    )

    private data class AssignedSample(val sample: ActionSample, val stateIndex: Int)

    private data class StateAssignment(
        val assignments: List<AssignedSample>,
        val groups: List<List<ActionSample>>,
        val stateDurationsMs: List<List<Double>>,
    )
}

data class ReferenceActionCompilerConfig(
    val minimumReferenceDurationMs: Long = 700L,
    val minimumAnalyzableFrames: Int = 18,
    val minimumAnalyzableFraction: Double = 0.55,
    val minimumFrameConfidence: Double = 0.35,
    val minimumFeatureObservability: Double = 0.60,
    val minimumFeatureCount: Int = 3,
    val minimumSelfReconstructionScore: Double = 0.58,
    val minimumSelfReconstructionFeatureCoverage: Double = 0.60,
    val targetStateCount: Int = 6,
    val minimumStateCount: Int = 3,
    val minimumSamplesPerState: Int = 3,
    val maximumTrendGapMs: Long = 500L,
    val minimumCycleDurationMs: Long = 450L,
    val minimumCyclicMotionRangeFloorMultiples: Double = 3.0,
    val minimumCyclicMovingFeatureFraction: Double = 0.15,
    val minimumCyclicMovingFeatureCount: Int = 2,
    val maximumReferenceCycles: Int = 10,
    val maximumRecurrenceSamples: Int = 120,
    val recurrenceCandidateDistance: Double = 0.12,
    val periodClusterRelativeWidth: Double = 0.06,
    val maximumPeriodScoreSamples: Int = 160,
    val minimumPeriodCoverage: Double = 0.60,
    val minimumPeriodTimestampToleranceMs: Double = 55.0,
    val periodTimestampToleranceFraction: Double = 0.16,
    val recurrenceDirectionPenalty: Double = 0.12,
    val recurrenceScoreScale: Double = 0.09,
    val minimumCyclicityScore: Double = 0.58,
    val periodNearBestFraction: Double = 0.94,
    val maximumAnchorCandidates: Int = 28,
    val anchorMinPeriodFraction: Double = 0.45,
    val anchorMaxPeriodFraction: Double = 2.20,
    val anchorDirectionPenalty: Double = 0.10,
    val anchorDurationPenalty: Double = 0.025,
    val anchorMaximumGeometryDistance: Double = 0.18,
    val anchorMaximumCombinedDistance: Double = 0.25,
    val minimumTrajectoryMotionFraction: Double = 0.20,
    val minimumTimingScaleMs: Double = 20.0,
    val nonCyclicStructuralConfidence: Double = 0.82,
    val maximumValidationStateSkip: Int = 2,
) {
    init {
        require(minimumReferenceDurationMs > 0L)
        require(minimumAnalyzableFrames >= 6)
        requireActionProbability(minimumAnalyzableFraction)
        requireActionProbability(minimumFrameConfidence)
        requireActionProbability(minimumFeatureObservability)
        require(minimumFeatureCount >= 1)
        requireActionProbability(minimumSelfReconstructionScore)
        requireActionProbability(minimumSelfReconstructionFeatureCoverage)
        require(targetStateCount >= minimumStateCount && minimumStateCount >= 2)
        require(minimumSamplesPerState >= 1)
        require(maximumTrendGapMs > 0L)
        require(minimumCycleDurationMs > 0L)
        require(minimumCyclicMotionRangeFloorMultiples > 0.0)
        requireActionProbability(minimumCyclicMovingFeatureFraction)
        require(minimumCyclicMovingFeatureCount >= 1)
        require(maximumReferenceCycles >= 2)
        require(maximumRecurrenceSamples > 0 && maximumPeriodScoreSamples > 0 && maximumAnchorCandidates > 0)
        require(recurrenceCandidateDistance > 0.0)
        require(periodClusterRelativeWidth > 0.0)
        requireActionProbability(minimumPeriodCoverage)
        require(minimumPeriodTimestampToleranceMs > 0.0)
        require(periodTimestampToleranceFraction > 0.0)
        require(recurrenceDirectionPenalty >= 0.0 && recurrenceScoreScale > 0.0)
        requireActionProbability(minimumCyclicityScore)
        require(periodNearBestFraction in 0.0..1.0)
        require(anchorMinPeriodFraction > 0.0 && anchorMaxPeriodFraction > anchorMinPeriodFraction)
        require(anchorDirectionPenalty >= 0.0 && anchorDurationPenalty >= 0.0)
        require(anchorMaximumGeometryDistance > 0.0 && anchorMaximumCombinedDistance > 0.0)
        requireActionProbability(minimumTrajectoryMotionFraction)
        require(minimumTimingScaleMs > 0.0)
        requireActionProbability(nonCyclicStructuralConfidence)
        require(maximumValidationStateSkip >= 1)
    }
}
