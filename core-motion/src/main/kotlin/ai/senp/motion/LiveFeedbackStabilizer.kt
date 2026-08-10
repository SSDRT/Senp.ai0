package ai.senp.motion

/** A generic deviation/coaching candidate produced by any reference-action recognizer. */
data class CoachingObservation(
    val stableKey: String,
    val label: String,
    val confidence: Double,
    val severity: Double,
    val timestampMs: Long,
    val priority: Int = 0,
) {
    init {
        require(stableKey.isNotBlank()) { "stableKey must not be blank" }
        require(label.isNotBlank()) { "label must not be blank" }
        require(confidence.isFinite() && confidence in 0.0..1.0) { "confidence must be finite and in [0,1]" }
        require(severity.isFinite() && severity in 0.0..1.0) { "severity must be finite and in [0,1]" }
        require(timestampMs >= 0L) { "timestampMs must be non-negative" }
        require(priority >= 0) { "priority must be non-negative" }
    }
}

data class StableCoachingCue(
    val stableKey: String,
    val label: String,
    val confidence: Double,
    val severity: Double,
    val priority: Int,
    val displayedSinceMs: Long,
    val lastObservedAtMs: Long,
)

enum class LiveTrackingState {
    TRACKING,
    DEGRADED,
    LOST,
}

enum class LiveFeedbackUncertainty {
    LOW,
    ELEVATED,
    HIGH,
}

data class StableLiveFeedback(
    val timestampMs: Long,
    val primary: StableCoachingCue?,
    val secondary: StableCoachingCue?,
    val trackingState: LiveTrackingState,
    val uncertainty: LiveFeedbackUncertainty,
)

/**
 * Time-based hysteresis between noisy reference-action deviations and a live coaching UI.
 *
 * New cues must persist for [LiveFeedbackConfig.confirmationMs]. Replacing an already visible cue
 * additionally requires [LiveFeedbackConfig.replacementDwellMs], while brief dropouts are hidden by
 * a bounded release grace. All state transitions use timestamps rather than frame counts.
 */
class LiveFeedbackStabilizer(
    private val config: LiveFeedbackConfig = LiveFeedbackConfig(),
) {
    private data class CandidateTrack(
        var firstPersistentAtMs: Long,
        var lastObservedAtMs: Long,
        var latest: CoachingObservation,
        var confirmedAtMs: Long? = null,
    )

    private data class DisplaySlot(
        val stableKey: String,
        val label: String,
        val displayedSinceMs: Long,
    )

    private val tracks = mutableMapOf<String, CandidateTrack>()
    private var previousTimestampMs: Long? = null
    private var primarySlot: DisplaySlot? = null
    private var secondarySlot: DisplaySlot? = null

    @Synchronized
    fun reset() {
        tracks.clear()
        previousTimestampMs = null
        primarySlot = null
        secondarySlot = null
    }

    @Synchronized
    fun update(
        timestampMs: Long,
        observations: List<CoachingObservation>,
        trackingConfidence: Double = 1.0,
    ): StableLiveFeedback {
        require(timestampMs >= 0L) { "timestampMs must be non-negative" }
        require(trackingConfidence.isFinite() && trackingConfidence in 0.0..1.0) {
            "trackingConfidence must be finite and in [0,1]"
        }
        val previous = previousTimestampMs
        require(previous == null || timestampMs > previous) { "live feedback timestamps must strictly increase" }
        observations.forEach { observation ->
            require(observation.timestampMs <= timestampMs) {
                "observation timestamp ${observation.timestampMs} cannot be newer than update timestamp $timestampMs"
            }
        }
        previousTimestampMs = timestampMs

        val trackingState = trackingState(trackingConfidence)
        if (trackingConfidence >= config.minimumTrackingConfidence) {
            ingest(timestampMs, observations)
        }
        prune(timestampMs)

        primarySlot = retainIfHeld(primarySlot, timestampMs, trackingState)
        secondarySlot = retainIfHeld(secondarySlot, timestampMs, trackingState)
            ?.takeUnless { it.stableKey == primarySlot?.stableKey }

        if (trackingState != LiveTrackingState.LOST) {
            primarySlot = chooseSlot(
                current = primarySlot,
                excludedKey = null,
                timestampMs = timestampMs,
            )
            secondarySlot = chooseSlot(
                current = secondarySlot?.takeUnless { it.stableKey == primarySlot?.stableKey },
                excludedKey = primarySlot?.stableKey,
                timestampMs = timestampMs,
            )
        }

        val primary = primarySlot?.let(::toCue)
        val secondary = secondarySlot?.let(::toCue)
        return StableLiveFeedback(
            timestampMs = timestampMs,
            primary = primary,
            secondary = secondary,
            trackingState = trackingState,
            uncertainty = uncertainty(trackingState, primary, timestampMs),
        )
    }

    private fun ingest(timestampMs: Long, observations: List<CoachingObservation>) {
        val strongestByKey = observations
            .asSequence()
            .filter { timestampMs - it.timestampMs <= config.maximumObservationLagMs }
            .filter { it.confidence >= config.minimumCandidateConfidence }
            .groupBy(CoachingObservation::stableKey)
            .mapValues { (_, candidates) -> candidates.minWithOrNull(sameKeyObservationComparator())!! }

        strongestByKey.values.forEach { observation ->
            val existing = tracks[observation.stableKey]
            if (existing == null) {
                tracks[observation.stableKey] = CandidateTrack(
                    firstPersistentAtMs = observation.timestampMs,
                    lastObservedAtMs = observation.timestampMs,
                    latest = observation,
                    confirmedAtMs = observation.timestampMs.takeIf { config.confirmationMs == 0L },
                )
                return@forEach
            }
            if (observation.timestampMs <= existing.lastObservedAtMs) return@forEach

            if (observation.timestampMs - existing.lastObservedAtMs > config.maximumEvidenceGapMs) {
                existing.firstPersistentAtMs = observation.timestampMs
                existing.confirmedAtMs = null
            }
            existing.lastObservedAtMs = observation.timestampMs
            existing.latest = observation
            if (
                existing.confirmedAtMs == null &&
                observation.timestampMs - existing.firstPersistentAtMs >= config.confirmationMs
            ) {
                existing.confirmedAtMs = observation.timestampMs
            }
        }
    }

    private fun prune(timestampMs: Long) {
        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val isDisplayed = entry.key == primarySlot?.stableKey || entry.key == secondarySlot?.stableKey
            if (!isDisplayed && timestampMs - entry.value.lastObservedAtMs > config.candidateStaleMs) {
                iterator.remove()
            }
        }
    }

    private fun retainIfHeld(
        slot: DisplaySlot?,
        timestampMs: Long,
        trackingState: LiveTrackingState,
    ): DisplaySlot? {
        slot ?: return null
        val track = tracks[slot.stableKey] ?: return null
        val holdMs = if (trackingState == LiveTrackingState.LOST) {
            config.trackingLossHoldMs
        } else {
            config.releaseGraceMs
        }
        if (timestampMs - track.lastObservedAtMs <= holdMs) return slot

        tracks.remove(slot.stableKey)
        return null
    }

    private fun chooseSlot(
        current: DisplaySlot?,
        excludedKey: String?,
        timestampMs: Long,
    ): DisplaySlot? {
        val freshConfirmed = tracks
            .asSequence()
            .filter { (key, track) ->
                key != excludedKey &&
                    track.confirmedAtMs != null &&
                    timestampMs - track.lastObservedAtMs <= config.maximumEvidenceGapMs
            }
            .sortedWith(trackComparator())
            .toList()

        if (current == null) {
            val best = freshConfirmed.firstOrNull() ?: return null
            return DisplaySlot(
                stableKey = best.key,
                label = best.value.latest.label,
                displayedSinceMs = timestampMs,
            )
        }

        val currentTrack = tracks[current.stableKey] ?: return null
        val challenger = freshConfirmed.firstOrNull { (key, track) ->
            key != current.stableKey &&
                timestampMs - track.firstPersistentAtMs >= config.confirmationMs + config.replacementDwellMs
        } ?: return current

        val currentFresh = timestampMs - currentTrack.lastObservedAtMs <= config.maximumEvidenceGapMs
        if (!currentFresh || outranks(challenger.value.latest, currentTrack.latest)) {
            return DisplaySlot(
                stableKey = challenger.key,
                label = challenger.value.latest.label,
                displayedSinceMs = timestampMs,
            )
        }
        return current
    }

    private fun toCue(slot: DisplaySlot): StableCoachingCue? {
        val track = tracks[slot.stableKey] ?: return null
        return StableCoachingCue(
            stableKey = slot.stableKey,
            label = slot.label,
            confidence = track.latest.confidence,
            severity = track.latest.severity,
            priority = track.latest.priority,
            displayedSinceMs = slot.displayedSinceMs,
            lastObservedAtMs = track.lastObservedAtMs,
        )
    }

    private fun uncertainty(
        trackingState: LiveTrackingState,
        primary: StableCoachingCue?,
        timestampMs: Long,
    ): LiveFeedbackUncertainty {
        if (trackingState == LiveTrackingState.LOST) return LiveFeedbackUncertainty.HIGH
        if (trackingState == LiveTrackingState.DEGRADED) return LiveFeedbackUncertainty.ELEVATED
        if (primary != null && primary.confidence < config.highConfidenceThreshold) {
            return LiveFeedbackUncertainty.ELEVATED
        }

        val topTwo = tracks
            .asSequence()
            .filter { (_, track) ->
                track.confirmedAtMs != null && timestampMs - track.lastObservedAtMs <= config.maximumEvidenceGapMs
            }
            .sortedWith(trackComparator())
            .take(2)
            .map { it.value.latest }
            .toList()
        if (topTwo.size == 2 && topTwo[0].priority == topTwo[1].priority) {
            val gap = strength(topTwo[0]) - strength(topTwo[1])
            if (gap < config.switchStrengthMargin) return LiveFeedbackUncertainty.ELEVATED
        }
        return LiveFeedbackUncertainty.LOW
    }

    private fun trackingState(trackingConfidence: Double): LiveTrackingState = when {
        trackingConfidence < config.minimumTrackingConfidence -> LiveTrackingState.LOST
        trackingConfidence < config.degradedTrackingConfidence -> LiveTrackingState.DEGRADED
        else -> LiveTrackingState.TRACKING
    }

    private fun outranks(challenger: CoachingObservation, current: CoachingObservation): Boolean {
        if (challenger.priority != current.priority) return challenger.priority > current.priority
        return strength(challenger) >= strength(current) + config.switchStrengthMargin
    }

    private fun sameKeyObservationComparator(): Comparator<CoachingObservation> =
        compareByDescending<CoachingObservation> { it.timestampMs }
            .thenByDescending { it.priority }
            .thenByDescending(::strength)
            .thenBy(CoachingObservation::label)

    private fun trackComparator(): Comparator<Map.Entry<String, CandidateTrack>> =
        compareByDescending<Map.Entry<String, CandidateTrack>> { it.value.latest.priority }
            .thenByDescending { strength(it.value.latest) }
            .thenBy { it.key }

    private fun strength(observation: CoachingObservation): Double = observation.confidence * observation.severity
}

data class LiveFeedbackConfig(
    val minimumCandidateConfidence: Double = 0.50,
    val highConfidenceThreshold: Double = 0.78,
    val minimumTrackingConfidence: Double = 0.45,
    val degradedTrackingConfidence: Double = 0.70,
    val confirmationMs: Long = 180L,
    val replacementDwellMs: Long = 120L,
    val releaseGraceMs: Long = 270L,
    val trackingLossHoldMs: Long = 450L,
    val maximumEvidenceGapMs: Long = 140L,
    val maximumObservationLagMs: Long = 200L,
    val candidateStaleMs: Long = 700L,
    val switchStrengthMargin: Double = 0.12,
) {
    init {
        require(minimumCandidateConfidence.isFinite() && minimumCandidateConfidence in 0.0..1.0)
        require(highConfidenceThreshold.isFinite() && highConfidenceThreshold in minimumCandidateConfidence..1.0)
        require(minimumTrackingConfidence.isFinite() && minimumTrackingConfidence in 0.0..1.0)
        require(degradedTrackingConfidence.isFinite() && degradedTrackingConfidence in minimumTrackingConfidence..1.0)
        require(confirmationMs >= 0L)
        require(replacementDwellMs >= 0L)
        require(releaseGraceMs >= 0L)
        require(trackingLossHoldMs >= releaseGraceMs)
        require(maximumEvidenceGapMs >= 0L)
        require(maximumObservationLagMs >= 0L)
        require(candidateStaleMs >= maxOf(releaseGraceMs, trackingLossHoldMs, maximumEvidenceGapMs))
        require(switchStrengthMargin.isFinite() && switchStrengthMargin in 0.0..1.0)
    }
}
