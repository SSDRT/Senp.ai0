package ai.senp.motion

import ai.senp.core.contracts.TimestampMs
import kotlin.math.max

class ActionStateRecognizer(
    private val profile: ActionProfile,
    private val config: ActionStateRecognizerConfig = ActionStateRecognizerConfig(),
) {
    private val estimates = mutableListOf<ActionStateEstimate>()
    private var status = ActionTrackingStatus.NO_ACTION
    private var lastTimestampMs: Long? = null
    private var lastSpatialSegmentId: Int? = null
    private var previousUsableGeometry: Map<String, Double>? = null
    private var previousUsableTimestampMs: Long? = null
    private var candidateState: Int? = null
    private var candidateStartMs: Long? = null
    private var candidateStateEnteredMs: Long? = null
    private var candidateProgressSteps = 0
    private val candidateVisited = linkedSetOf<Int>()
    private var mirrorMode = ActionMirrorMode.UNKNOWN
    private var trackingState: Int? = null
    private var stateEnteredMs: Long? = null
    private var lastGoodMs: Long? = null
    private var completedRepetitions = 0
    private val visitedSinceWrap = linkedSetOf<Int>()
    private var cycleEvidenceEligible = false

    fun reset() {
        estimates.clear()
        status = ActionTrackingStatus.NO_ACTION
        lastTimestampMs = null
        lastSpatialSegmentId = null
        previousUsableGeometry = null
        previousUsableTimestampMs = null
        candidateState = null
        candidateStartMs = null
        candidateStateEnteredMs = null
        candidateProgressSteps = 0
        candidateVisited.clear()
        mirrorMode = ActionMirrorMode.UNKNOWN
        trackingState = null
        stateEnteredMs = null
        lastGoodMs = null
        completedRepetitions = 0
        visitedSinceWrap.clear()
        cycleEvidenceEligible = false
    }

    fun recognize(sequence: SpatialSequenceAnalysis): ActionRecognitionResult {
        reset()
        sequence.frames.forEach(::accept)
        return finish()
    }

    fun accept(frame: SpatialObservationFrame): ActionStateEstimate {
        val timestampMs = frame.timestamp.value
        val previousTimestamp = lastTimestampMs
        require(previousTimestamp == null || timestampMs > previousTimestamp) {
            "action recognizer timestamps must be strictly increasing"
        }
        lastTimestampMs = timestampMs
        val spatialSegmentId = frame.spatialSegmentId
        val continuityBreak = spatialSegmentId != null &&
            lastSpatialSegmentId != null &&
            spatialSegmentId != lastSpatialSegmentId
        if (spatialSegmentId != null) lastSpatialSegmentId = spatialSegmentId
        if (status == ActionTrackingStatus.COMPLETED) {
            return emit(frame.timestamp, status, null, 1.0, 1.0, mirrorMode, null)
        }
        if (continuityBreak) {
            previousUsableGeometry = null
            previousUsableTimestampMs = null
            if (status == ActionTrackingStatus.POSSIBLE_ENTRY || status == ActionTrackingStatus.TRACKING) {
                resetTrackingForContinuityBreak()
                return emit(frame.timestamp, status, null, 0.0, 0.0, mirrorMode, null)
            }
        }

        val geometry = frame.intrinsicDescriptor.values
        val frameConfidence = frame.intrinsicDescriptor.confidence
        val priorTimestamp = previousUsableTimestampMs
        val priorGeometry = if (
            priorTimestamp != null &&
            timestampMs - priorTimestamp <= config.maximumMotionHistoryGapMs
        ) {
            previousUsableGeometry
        } else {
            null
        }
        val trajectory = actionTrend(priorGeometry, geometry, profile.featureScales)
        val usable = geometry.isNotEmpty() && frameConfidence >= config.minimumFrameConfidence
        if (usable) {
            previousUsableGeometry = geometry
            previousUsableTimestampMs = timestampMs
        }

        return when (status) {
            ActionTrackingStatus.NO_ACTION, ActionTrackingStatus.LOST -> handleEntry(frame, geometry, trajectory, usable)
            ActionTrackingStatus.POSSIBLE_ENTRY -> handlePossibleEntry(frame, geometry, trajectory, usable)
            ActionTrackingStatus.TRACKING -> handleTracking(frame, geometry, trajectory, usable)
            ActionTrackingStatus.COMPLETED -> error("completed state handled above")
        }
    }

    fun finish(): ActionRecognitionResult {
        val effectiveRepetitions: Int
        val finalStatus: ActionTrackingStatus
        if (profile.cyclic) {
            val trailingCompleteCycle = when {
                cycleEvidenceEligible && hasSufficientObservedCycleEvidence() -> 1
                !cycleEvidenceEligible && visitedSinceWrap.size == profile.states.size -> 1
                else -> 0
            }
            effectiveRepetitions = completedRepetitions + trailingCompleteCycle
            finalStatus = if (effectiveRepetitions > 0) ActionTrackingStatus.COMPLETED else when (status) {
                ActionTrackingStatus.NO_ACTION -> ActionTrackingStatus.NO_ACTION
                else -> ActionTrackingStatus.LOST
            }
        } else {
            val reachedFinal = trackingState == profile.states.lastIndex || status == ActionTrackingStatus.COMPLETED
            effectiveRepetitions = if (reachedFinal) 1 else 0
            finalStatus = if (reachedFinal) ActionTrackingStatus.COMPLETED else when (status) {
                ActionTrackingStatus.NO_ACTION -> ActionTrackingStatus.NO_ACTION
                else -> ActionTrackingStatus.LOST
            }
        }
        val trackedCount = estimates.count {
            it.status == ActionTrackingStatus.TRACKING ||
                (it.status == ActionTrackingStatus.COMPLETED && it.stateIndex != null)
        }
        return ActionRecognitionResult(
            estimates = estimates.toList(),
            finalStatus = finalStatus,
            completedRepetitions = effectiveRepetitions,
            repetitionDeltaFromReference = effectiveRepetitions - profile.referenceRepetitions,
            trackedFraction = if (estimates.isEmpty()) 0.0 else trackedCount.toDouble() / estimates.size.toDouble(),
        )
    }

    private fun handleEntry(
        frame: SpatialObservationFrame,
        geometry: Map<String, Double>,
        trajectory: Map<String, Double>,
        usable: Boolean,
    ): ActionStateEstimate {
        if (!usable) {
            status = ActionTrackingStatus.NO_ACTION
            clearCandidate()
            return emit(frame.timestamp, status, null, 0.0, 0.0, ActionMirrorMode.UNKNOWN, null)
        }
        val candidateStates = if (profile.cyclic) {
            profile.states.indices.toList()
        } else {
            profile.states.indices.take(config.finiteEntryStateCount)
        }
        val matches = matchActionStates(profile, geometry, trajectory, candidateStates)
        val evidenceBest = matches.firstOrNull()
        val second = matches.drop(1).firstOrNull()
        if (!isStrongEntry(evidenceBest, second)) {
            status = ActionTrackingStatus.NO_ACTION
            clearCandidate()
            return emit(frame.timestamp, status, null, evidenceBest?.score ?: 0.0, evidenceBest?.coverage ?: 0.0, ActionMirrorMode.UNKNOWN, null)
        }
        evidenceBest!!
        val selected = if (profile.cyclic) {
            evidenceBest
        } else {
            matches.filter { match ->
                evidenceBest.score - match.score <= config.continuityPreferenceScoreMargin &&
                    match.score >= config.minimumEntryScore &&
                    match.coverage >= config.minimumFeatureCoverage
            }.minWithOrNull(
                compareBy<ActionStateMatch>(ActionStateMatch::stateIndex).thenByDescending(ActionStateMatch::score),
            ) ?: evidenceBest
        }
        status = ActionTrackingStatus.POSSIBLE_ENTRY
        candidateState = selected.stateIndex
        candidateStartMs = frame.timestamp.value
        candidateStateEnteredMs = frame.timestamp.value
        candidateProgressSteps = 0
        candidateVisited.clear()
        candidateVisited += selected.stateIndex
        mirrorMode = selected.mirrorMode
        return emit(frame.timestamp, status, selected.stateIndex, selected.score, selected.coverage, mirrorMode, null)
    }

    private fun handlePossibleEntry(
        frame: SpatialObservationFrame,
        geometry: Map<String, Double>,
        trajectory: Map<String, Double>,
        usable: Boolean,
    ): ActionStateEstimate {
        val candidate = candidateState ?: run {
            status = ActionTrackingStatus.NO_ACTION
            return handleEntry(frame, geometry, trajectory, usable)
        }
        if (!usable) {
            val start = candidateStartMs ?: frame.timestamp.value
            if (frame.timestamp.value - start > config.entryGraceMs) {
                status = ActionTrackingStatus.NO_ACTION
                clearCandidate()
                return emit(frame.timestamp, status, null, 0.0, 0.0, ActionMirrorMode.UNKNOWN, null)
            }
            return emit(frame.timestamp, status, candidate, 0.0, 0.0, mirrorMode, null)
        }
        val entryElapsedBeforeMatch = frame.timestamp.value - (candidateStartMs ?: frame.timestamp.value)
        if (profile.states.size > 1 && candidateProgressSteps == 0 && entryElapsedBeforeMatch > config.entryGraceMs) {
            status = ActionTrackingStatus.NO_ACTION
            clearCandidate()
            return handleEntry(frame, geometry, trajectory, usable = true)
        }
        val entrySkipLimit = if (profile.cyclic) config.maximumEntryStateSkip else 1
        val possibleEntryStates = buildList {
            add(candidate)
            for (step in 1..entrySkipLimit) {
                nextState(candidate, step)?.let(::add)
            }
        }.distinct()
        val matches = matchActionStates(
            profile,
            geometry,
            trajectory,
            possibleEntryStates,
            mirrorModes = listOf(mirrorMode),
        )
        val best = selectContinuityMatch(matches, candidate)
        if (best == null || best.score < config.minimumTrackingScore || best.coverage < config.minimumFeatureCoverage) {
            val start = candidateStartMs ?: frame.timestamp.value
            if (frame.timestamp.value - start > config.entryGraceMs) {
                status = ActionTrackingStatus.NO_ACTION
                clearCandidate()
                return emit(frame.timestamp, status, null, best?.score ?: 0.0, best?.coverage ?: 0.0, ActionMirrorMode.UNKNOWN, null)
            }
            return emit(frame.timestamp, status, candidate, best?.score ?: 0.0, best?.coverage ?: 0.0, mirrorMode, null)
        }
        val forward = forwardDistance(candidate, best.stateIndex)
        if (forward in 0..entrySkipLimit) {
            if (forward > 0) {
                candidateVisited += best.stateIndex
                candidateProgressSteps += forward
                candidateStateEnteredMs = frame.timestamp.value
            }
            candidateState = best.stateIndex
            val entryElapsed = frame.timestamp.value - (candidateStartMs ?: frame.timestamp.value)
            val entryConfirmed = if (profile.states.size == 1) {
                entryElapsed >= config.minimumSingleStateConfirmationMs
            } else {
                candidateProgressSteps >= 1 && entryElapsed >= config.minimumEntryConfirmationMs
            }
            if (entryConfirmed) {
                status = ActionTrackingStatus.TRACKING
                trackingState = best.stateIndex
                stateEnteredMs = candidateStateEnteredMs ?: frame.timestamp.value
                lastGoodMs = frame.timestamp.value
                visitedSinceWrap.clear()
                visitedSinceWrap += candidateVisited
                cycleEvidenceEligible = profile.cyclic
                return emit(frame.timestamp, status, best.stateIndex, best.score, best.coverage, mirrorMode, null)
            }
            return emit(frame.timestamp, status, best.stateIndex, best.score, best.coverage, mirrorMode, null)
        }

        status = ActionTrackingStatus.POSSIBLE_ENTRY
        candidateState = best.stateIndex
        candidateStartMs = frame.timestamp.value
        candidateStateEnteredMs = frame.timestamp.value
        candidateProgressSteps = 0
        candidateVisited.clear()
        candidateVisited += best.stateIndex
        mirrorMode = best.mirrorMode
        return emit(frame.timestamp, status, best.stateIndex, best.score, best.coverage, mirrorMode, null)
    }

    private fun handleTracking(
        frame: SpatialObservationFrame,
        geometry: Map<String, Double>,
        trajectory: Map<String, Double>,
        usable: Boolean,
    ): ActionStateEstimate {
        val current = trackingState ?: error("tracking status requires a current state")
        if (!usable) return handleTrackingGap(frame, current)
        val trackingSkipLimit = if (profile.cyclic) config.maximumTrackingStateSkip else 1
        val candidates = buildList {
            add(current)
            for (step in 1..trackingSkipLimit) {
                nextState(current, step)?.let(::add)
            }
        }.distinct()
        val matches = matchActionStates(
            profile,
            geometry,
            trajectory,
            candidates,
            mirrorModes = listOf(mirrorMode),
        )
        val best = selectContinuityMatch(matches, current)
        if (best == null || best.score < config.minimumTrackingScore || best.coverage < config.minimumFeatureCoverage) {
            return handleTrackingGap(frame, current, best)
        }
        lastGoodMs = frame.timestamp.value
        var timing: PhaseTimingComparison? = null
        if (best.stateIndex != current) {
            val distance = forwardDistance(current, best.stateIndex)
            if (distance <= 0 || distance > trackingSkipLimit) {
                return handleTrackingGap(frame, current, best)
            }
            val entered = stateEnteredMs ?: frame.timestamp.value
            val observedDurationMs = frame.timestamp.value - entered
            val minimumDwellMs = (
                profile.states[current].durationMs.median * config.minimumTrackingStateDwellFraction
                ).toLong()
            if (observedDurationMs < minimumDwellMs) {
                val currentMatch = matches.firstOrNull { it.stateIndex == current }
                return emit(
                    frame.timestamp,
                    ActionTrackingStatus.TRACKING,
                    current,
                    currentMatch?.score ?: best.score,
                    0.0,
                    mirrorMode,
                    null,
                )
            }
            timing = timingForState(current, observedDurationMs, best.score)
            if (profile.cyclic && best.stateIndex < current) {
                handleCycleWrap()
            }
            trackingState = best.stateIndex
            stateEnteredMs = frame.timestamp.value
        }
        val selectedState = trackingState ?: current
        visitedSinceWrap += selectedState
        if (!profile.cyclic && profile.states.size > 1 && selectedState == profile.states.lastIndex) {
            val entered = stateEnteredMs ?: frame.timestamp.value
            if (frame.timestamp.value - entered >= config.minimumFiniteCompletionHoldMs) {
                status = ActionTrackingStatus.COMPLETED
                completedRepetitions = 1
            }
        }
        return emit(frame.timestamp, status, selectedState, best.score, best.coverage, mirrorMode, timing)
    }

    private fun handleTrackingGap(
        frame: SpatialObservationFrame,
        current: Int,
        weakMatch: ActionStateMatch? = null,
    ): ActionStateEstimate {
        val lastGood = lastGoodMs ?: frame.timestamp.value
        if (frame.timestamp.value - lastGood <= config.trackingLossGraceMs) {
            return emit(
                frame.timestamp,
                ActionTrackingStatus.TRACKING,
                current,
                weakMatch?.score ?: 0.0,
                weakMatch?.coverage ?: 0.0,
                mirrorMode,
                null,
            )
        }
        status = ActionTrackingStatus.LOST
        trackingState = null
        stateEnteredMs = null
        visitedSinceWrap.clear()
        cycleEvidenceEligible = false
        clearCandidate()
        return emit(frame.timestamp, status, null, weakMatch?.score ?: 0.0, weakMatch?.coverage ?: 0.0, mirrorMode, null)
    }

    private fun isStrongEntry(best: ActionStateMatch?, second: ActionStateMatch?): Boolean {
        if (best == null) return false
        if (best.score < config.minimumEntryScore || best.coverage < config.minimumFeatureCoverage) return false
        val margin = best.score - (second?.score ?: 0.0)
        return margin >= config.minimumEntryMargin || best.score >= config.highConfidenceEntryScore
    }

    private fun selectContinuityMatch(matches: List<ActionStateMatch>, currentState: Int): ActionStateMatch? {
        val best = matches.firstOrNull() ?: return null
        val closeMatches = matches.filter { match ->
            best.score - match.score <= config.continuityPreferenceScoreMargin
        }
        return closeMatches.minWithOrNull(
            compareBy<ActionStateMatch> { match -> forwardDistance(currentState, match.stateIndex) }
                .thenByDescending(ActionStateMatch::score),
        ) ?: best
    }

    private fun forwardDistance(from: Int, to: Int): Int {
        if (to == from) return 0
        return if (profile.cyclic) {
            (to - from + profile.states.size) % profile.states.size
        } else {
            to - from
        }
    }

    private fun handleCycleWrap() {
        if (cycleEvidenceEligible && hasSufficientObservedCycleEvidence()) {
            completedRepetitions += 1
        }
        visitedSinceWrap.clear()
        cycleEvidenceEligible = true
    }

    private fun hasSufficientObservedCycleEvidence(): Boolean =
        visitedSinceWrap.size.toDouble() / profile.states.size.toDouble() >= config.minimumObservedStateFractionPerRepetition

    private fun nextState(from: Int, step: Int): Int? {
        val raw = from + step
        return if (profile.cyclic) raw % profile.states.size else raw.takeIf { it in profile.states.indices }
    }

    private fun timingForState(stateIndex: Int, observedMs: Long, matchConfidence: Double): PhaseTimingComparison {
        val state = profile.states[stateIndex]
        val reference = state.durationMs
        val classification = when {
            observedMs.toDouble() < reference.lower -> PhaseTimingClass.FASTER
            observedMs.toDouble() > reference.upper -> PhaseTimingClass.SLOWER
            else -> PhaseTimingClass.WITHIN_REFERENCE_RANGE
        }
        return PhaseTimingComparison(
            stateId = state.id,
            observedDurationMs = observedMs,
            referenceDurationMs = reference,
            relativeToReferenceMedian = observedMs.toDouble() / max(reference.median, 1.0),
            classification = classification,
            confidence = (matchConfidence * state.confidence).coerceIn(0.0, 1.0),
        )
    }

    private fun resetTrackingForContinuityBreak() {
        status = ActionTrackingStatus.LOST
        candidateState = null
        candidateStartMs = null
        candidateStateEnteredMs = null
        candidateProgressSteps = 0
        candidateVisited.clear()
        trackingState = null
        stateEnteredMs = null
        lastGoodMs = null
        visitedSinceWrap.clear()
        cycleEvidenceEligible = false
        mirrorMode = ActionMirrorMode.UNKNOWN
    }

    private fun clearCandidate() {
        candidateState = null
        candidateStartMs = null
        candidateStateEnteredMs = null
        candidateProgressSteps = 0
        candidateVisited.clear()
        if (status == ActionTrackingStatus.NO_ACTION) mirrorMode = ActionMirrorMode.UNKNOWN
    }

    private fun emit(
        timestamp: TimestampMs,
        estimateStatus: ActionTrackingStatus,
        stateIndex: Int?,
        score: Double,
        coverage: Double,
        estimateMirrorMode: ActionMirrorMode,
        timing: PhaseTimingComparison?,
    ): ActionStateEstimate {
        val estimate = ActionStateEstimate(
            timestamp = timestamp,
            status = estimateStatus,
            stateId = stateIndex?.let { profile.states[it].id },
            stateIndex = stateIndex,
            confidence = score.coerceIn(0.0, 1.0),
            featureCoverage = coverage.coerceIn(0.0, 1.0),
            mirrorMode = estimateMirrorMode,
            completedRepetitions = completedRepetitions,
            timing = timing,
        )
        estimates += estimate
        return estimate
    }
}

data class ActionStateRecognizerConfig(
    val minimumFrameConfidence: Double = 0.35,
    val minimumFeatureCoverage: Double = 0.52,
    val minimumEntryScore: Double = 0.58,
    val highConfidenceEntryScore: Double = 0.78,
    val minimumEntryMargin: Double = 0.025,
    val minimumTrackingScore: Double = 0.48,
    val continuityPreferenceScoreMargin: Double = 0.04,
    val finiteEntryStateCount: Int = 1,
    val maximumEntryStateSkip: Int = 2,
    val maximumTrackingStateSkip: Int = 2,
    val minimumTrackingStateDwellFraction: Double = 0.42,
    val minimumObservedStateFractionPerRepetition: Double = 0.80,
    val minimumEntryConfirmationMs: Long = 45L,
    val minimumSingleStateConfirmationMs: Long = 500L,
    val entryGraceMs: Long = 500L,
    val trackingLossGraceMs: Long = 450L,
    val maximumMotionHistoryGapMs: Long = 650L,
    val minimumFiniteCompletionHoldMs: Long = 80L,
) {
    init {
        listOf(
            minimumFrameConfidence,
            minimumFeatureCoverage,
            minimumEntryScore,
            highConfidenceEntryScore,
            minimumEntryMargin,
            minimumTrackingScore,
            continuityPreferenceScoreMargin,
            minimumTrackingStateDwellFraction,
            minimumObservedStateFractionPerRepetition,
        ).forEach(::requireActionProbability)
        require(highConfidenceEntryScore >= minimumEntryScore)
        require(finiteEntryStateCount >= 1)
        require(maximumEntryStateSkip >= 1 && maximumTrackingStateSkip >= 1)
        require(minimumEntryConfirmationMs >= 0L)
        require(minimumSingleStateConfirmationMs >= minimumEntryConfirmationMs)
        require(entryGraceMs >= minimumEntryConfirmationMs)
        require(trackingLossGraceMs >= 0L)
        require(maximumMotionHistoryGapMs > 0L)
        require(minimumFiniteCompletionHoldMs >= 0L)
    }
}
