package ai.senp.motion

/** Exercise-profile-aware quality scoring with timestamp-duration hysteresis. */
internal class QualityGate(private val config: MotionConfig = MotionConfig()) {
    fun evaluate(
        frames: List<PoseFrame>,
        profile: ExerciseProfile,
        signals: List<FrameSignals> = frames.map { FrameSignals() },
    ): List<QualityResult> = evaluateTracked(
        frames = frames.map { TrackedFrame(it) },
        profile = profile,
        signals = signals,
        guardrails = frames.map { GuardrailFlags() },
    )

    fun evaluateTracked(
        frames: List<TrackedFrame>,
        profile: ExerciseProfile,
        signals: List<FrameSignals> = frames.map { FrameSignals() },
        guardrails: List<GuardrailFlags> = frames.map { GuardrailFlags() },
    ): List<QualityResult> {
        require(frames.size == signals.size) { "one FrameSignals value is required per frame" }
        require(frames.size == guardrails.size) { "one GuardrailFlags value is required per frame" }
        if (frames.isEmpty()) return emptyList()
        require(frames.zipWithNext().all { (previous, current) -> current.frame.timestampMs > previous.frame.timestampMs }) {
            "timestamps must be strictly increasing"
        }

        var previousSelectedSide: BodySide? = null
        val evaluations = frames.mapIndexed { index, tracked ->
            val selectedSide = selectSide(tracked.frame, profile, previousSelectedSide)
            if (profile.sidePolicy == SidePolicy.BEST_VISIBLE) previousSelectedSide = selectedSide
            evaluateFrame(tracked, profile, selectedSide, signals[index], guardrails[index])
        }
        val blindMask = hysteresisMask(frames.map { it.frame.timestampMs }, evaluations.map { it.score })

        return evaluations.mapIndexed { index, evaluation ->
            val validity = when {
                evaluation.continuityBreak -> FrameValidity.CONTINUITY_BREAK
                blindMask[index] -> FrameValidity.BLIND
                evaluation.required.coverage + 1e-12 < profile.minimumRequiredCoverage -> FrameValidity.DEGRADED
                evaluation.score + 1e-12 < config.usableThreshold -> FrameValidity.DEGRADED
                evaluation.required.repairedFraction > 0.0 -> FrameValidity.REPAIRED
                else -> FrameValidity.VALID
            }
            QualityResult(
                timestampMs = frames[index].frame.timestampMs,
                score = evaluation.score,
                validity = validity,
                selectedSide = evaluation.selectedSide,
                requiredCoverage = evaluation.required.coverage,
                requiredVisibility = evaluation.required.visibility,
                requiredPresence = evaluation.required.presence,
                preferredQuality = evaluation.preferred.quality,
                repairedFraction = evaluation.required.repairedFraction,
                clipping = signals[index].clipping,
                instability = signals[index].instability,
            )
        }
    }

    private data class Metrics(
        val visibility: Double,
        val presence: Double,
        val coverage: Double,
        val repairedFraction: Double,
    ) {
        val quality: Double get() = (visibility + presence + coverage) / 3.0
    }

    private data class Evaluation(
        val score: Double,
        val selectedSide: BodySide?,
        val required: Metrics,
        val preferred: Metrics,
        val continuityBreak: Boolean,
    )

    private fun evaluateFrame(
        tracked: TrackedFrame,
        profile: ExerciseProfile,
        selectedSide: BodySide?,
        signals: FrameSignals,
        guardrails: GuardrailFlags,
    ): Evaluation {
        val requiredIds = applySidePolicy(profile.required, profile.sidePolicy, selectedSide)
        val preferredIds = applySidePolicy(profile.preferred, profile.sidePolicy, selectedSide)
        val required = metrics(tracked.frame, requiredIds)
        val preferred = metrics(tracked.frame, preferredIds)
        val weights = profile.weights
        val preferredWeight = if (preferredIds.isEmpty()) 0.0 else weights.preferredQuality
        val positiveWeight = weights.visibility + weights.presence + weights.requiredCoverage + preferredWeight
        var score = (
            weights.visibility * required.visibility +
                weights.presence * required.presence +
                weights.requiredCoverage * required.coverage +
                preferredWeight * preferred.quality
            ) / positiveWeight
        score *= 1.0 - weights.repairedPenalty * required.repairedFraction
        score *= 1.0 - weights.clippingPenalty * signals.clipping
        score *= 1.0 - weights.instabilityPenalty * signals.instability
        if (guardrails.impossibleProportions) score *= 1.0 - weights.impossibleProportionPenalty

        return Evaluation(
            score = score.coerceIn(0.0, 1.0),
            selectedSide = selectedSide,
            required = required,
            preferred = preferred,
            continuityBreak = tracked.continuityBreakLandmarks.any { it in requiredIds },
        )
    }

    private fun metrics(frame: PoseFrame, ids: Set<LandmarkId>): Metrics {
        if (ids.isEmpty()) return Metrics(1.0, 1.0, 1.0, 0.0)
        var visibility = 0.0
        var presence = 0.0
        var coverage = 0.0
        var repaired = 0
        for (id in ids) {
            val landmark = frame[id]
            val finite = landmark.hasFiniteImage()
            visibility += if (finite) landmark.visibility else 0.0
            presence += if (finite) landmark.presence else 0.0
            coverage += when {
                finite && landmark.visibility >= config.minVisibility && landmark.presence >= config.minPresence -> 1.0
                finite && landmark.repaired -> config.repairedCoverageCredit
                else -> 0.0
            }
            if (landmark.repaired) repaired += 1
        }
        val count = ids.size.toDouble()
        return Metrics(
            visibility = visibility / count,
            presence = presence / count,
            coverage = coverage / count,
            repairedFraction = repaired / count,
        )
    }

    private fun selectSide(frame: PoseFrame, profile: ExerciseProfile, previous: BodySide?): BodySide? = when (profile.sidePolicy) {
        SidePolicy.BOTH -> null
        SidePolicy.LEFT_ONLY -> BodySide.LEFT
        SidePolicy.RIGHT_ONLY -> BodySide.RIGHT
        SidePolicy.BEST_VISIBLE -> {
            val left = sideEvidence(frame, profile.required, BodySide.LEFT)
            val right = sideEvidence(frame, profile.required, BodySide.RIGHT)
            when (previous) {
                BodySide.LEFT -> if (right > left + config.sideSwitchMargin) BodySide.RIGHT else BodySide.LEFT
                BodySide.RIGHT -> if (left > right + config.sideSwitchMargin) BodySide.LEFT else BodySide.RIGHT
                null -> if (left >= right) BodySide.LEFT else BodySide.RIGHT
            }
        }
    }

    private fun sideEvidence(frame: PoseFrame, ids: Set<LandmarkId>, side: BodySide): Double {
        val metrics = metrics(frame, applySidePolicy(ids, SidePolicy.BEST_VISIBLE, side))
        return 0.4 * metrics.visibility + 0.3 * metrics.presence + 0.3 * metrics.coverage
    }

    private fun applySidePolicy(
        ids: Set<LandmarkId>,
        policy: SidePolicy,
        selectedSide: BodySide?,
    ): Set<LandmarkId> {
        if (policy == SidePolicy.BOTH || selectedSide == null) return ids
        return ids.filterTo(linkedSetOf()) { id -> id.side == null || id.side == selectedSide }
    }

    private fun hysteresisMask(timestampsMs: List<Long>, scores: List<Double>): BooleanArray {
        val blind = BooleanArray(scores.size)
        var inBlind = false
        var lowStart: Int? = null
        var recoveryStart: Int? = null

        for (index in scores.indices) {
            val score = scores[index]
            if (!inBlind) {
                if (score < config.blindEnterThreshold) {
                    if (lowStart == null) lowStart = index
                    val start = lowStart!!
                    if (timestampsMs[index] - timestampsMs[start] >= config.blindEnterDurationMs) {
                        inBlind = true
                        for (blindIndex in start..index) blind[blindIndex] = true
                        recoveryStart = null
                    }
                } else {
                    lowStart = null
                }
            } else {
                blind[index] = true
                if (score >= config.usableThreshold) {
                    if (recoveryStart == null) recoveryStart = index
                    val start = recoveryStart!!
                    if (timestampsMs[index] - timestampsMs[start] >= config.recoverDurationMs) {
                        for (recoveredIndex in start..index) blind[recoveredIndex] = false
                        inBlind = false
                        lowStart = null
                        recoveryStart = null
                    }
                } else {
                    recoveryStart = null
                }
            }
        }
        return blind
    }
}
