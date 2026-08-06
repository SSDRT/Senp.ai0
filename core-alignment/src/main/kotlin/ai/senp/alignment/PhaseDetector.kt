package ai.senp.alignment

import kotlin.math.abs
import kotlin.math.max

internal data class PhaseState(
    val activeStart: Int,
    val activeEnd: Int,
    val phaseSignal: List<Double?>,
    val repBoundaries: List<Int>,
    val anchors: List<Int>,
    val insufficientMotion: Boolean,
    val motionStrength: Double,
)

private data class TurningPoint(
    val index: Int,
    val isMinimum: Boolean,
    val prominence: Double,
)

internal object PhaseDetector {
    fun detect(track: MotionTrack, profile: ExerciseProfile, config: AlignmentConfig): PhaseState {
        if (track.frames.isEmpty()) {
            return PhaseState(0, -1, emptyList(), emptyList(), emptyList(), true, 0.0)
        }

        val signal = buildPhaseSignal(track, profile)
        val motionStrength = motionStrength(track, profile)
        val velocity = phaseVelocity(track, signal)
        val smoothedVelocity = smoothByTime(track, velocity, config.activeSmoothingWindowMs)
        val positiveVelocity = smoothedVelocity.filter { it > 0.0 }

        var activeStart = 0
        var activeEnd = track.frames.lastIndex
        if (positiveVelocity.isNotEmpty()) {
            val threshold = quantile(positiveVelocity, config.activeVelocityQuantile) * config.activeVelocityMultiplier
            val activeIndices = smoothedVelocity.indices.filter { smoothedVelocity[it] >= threshold }
            if (activeIndices.isNotEmpty()) {
                val startTimestamp = track.frames[activeIndices.first()].timestampMs - config.activePaddingMs
                val endTimestamp = track.frames[activeIndices.last()].timestampMs + config.activePaddingMs
                activeStart = indexAtOrAfter(track, startTimestamp.coerceAtLeast(track.frames.first().timestampMs))
                activeEnd = indexAtOrBefore(track, endTimestamp.coerceAtMost(track.frames.last().timestampMs))
            }
        }

        if (
            activeEnd < activeStart ||
            track.frames[activeEnd].timestampMs - track.frames[activeStart].timestampMs < config.minimumActiveDurationMs
        ) {
            activeStart = 0
            activeEnd = track.frames.lastIndex
        }

        val activeDurationMs = track.frames[activeEnd].timestampMs - track.frames[activeStart].timestampMs
        val insufficient = motionStrength < 1.0 || activeDurationMs < config.minimumActiveDurationMs
        if (insufficient) {
            val endpoints = if (track.frames.size == 1) listOf(0) else listOf(0, track.frames.lastIndex)
            return PhaseState(
                activeStart = 0,
                activeEnd = track.frames.lastIndex,
                phaseSignal = signal,
                repBoundaries = endpoints,
                anchors = endpoints,
                insufficientMotion = true,
                motionStrength = motionStrength,
            )
        }

        val smoothedSignal = smoothNullableByTime(track, signal, config.turningNeighborhoodMs / 2)
        val turns = turningPoints(track, smoothedSignal, activeStart, activeEnd, config)
        val repBoundaries = repBoundaries(track, activeStart, activeEnd, turns, config)
        val anchors = (listOf(activeStart) + turns.map { it.index } + listOf(activeEnd))
            .distinct()
            .sorted()

        return PhaseState(
            activeStart = activeStart,
            activeEnd = activeEnd,
            phaseSignal = signal,
            repBoundaries = repBoundaries,
            anchors = anchors,
            insufficientMotion = false,
            motionStrength = motionStrength,
        )
    }

    private fun buildPhaseSignal(track: MotionTrack, profile: ExerciseProfile): List<Double?> {
        val normalizedByFeature = mutableMapOf<String, List<Double?>>()
        for ((name, rule) in profile.orderedRules) {
            if (rule.phaseWeight <= 0.0) continue
            val raw = track.frames.map { frame ->
                frame.features[name]?.takeIf {
                    it.value != null && it.confidence >= profile.minimumFeatureConfidence
                }?.value
            }
            val valid = raw.filterNotNull()
            if (valid.isEmpty()) {
                normalizedByFeature[name] = List(raw.size) { null }
                continue
            }
            val lower = quantile(valid, 0.05)
            val upper = quantile(valid, 0.95)
            val span = (upper - lower).coerceAtLeast(1e-9)
            normalizedByFeature[name] = raw.map { value ->
                value?.let { ((it - lower) / span).coerceIn(-0.5, 1.5) }
            }
        }

        return track.frames.indices.map { index ->
            var weighted = 0.0
            var weight = 0.0
            for ((name, rule) in profile.orderedRules) {
                if (rule.phaseWeight <= 0.0) continue
                val value = normalizedByFeature[name]?.get(index) ?: continue
                weighted += value * rule.phaseWeight
                weight += rule.phaseWeight
            }
            if (weight <= 0.0) null else weighted / weight
        }
    }

    private fun motionStrength(track: MotionTrack, profile: ExerciseProfile): Double {
        var weightedStrength = 0.0
        var totalPhaseWeight = 0.0
        for ((name, rule) in profile.orderedRules) {
            if (rule.phaseWeight <= 0.0) continue
            val valid = track.frames.mapNotNull { frame ->
                frame.features[name]?.takeIf {
                    it.value != null && it.confidence >= profile.minimumFeatureConfidence
                }?.value
            }
            val range = if (valid.size < 2) 0.0 else quantile(valid, 0.95) - quantile(valid, 0.05)
            weightedStrength += (range / rule.minimumMotionRange) * rule.phaseWeight
            totalPhaseWeight += rule.phaseWeight
        }
        return if (totalPhaseWeight <= 0.0) 0.0 else weightedStrength / totalPhaseWeight
    }

    private fun phaseVelocity(track: MotionTrack, signal: List<Double?>): List<Double> {
        val velocity = MutableList(track.frames.size) { 0.0 }
        for (index in 1 until track.frames.size) {
            val previous = signal[index - 1]
            val current = signal[index]
            val deltaMs = track.frames[index].timestampMs - track.frames[index - 1].timestampMs
            if (previous != null && current != null && deltaMs > 0L) {
                velocity[index] = abs(current - previous) * 1000.0 / deltaMs
            }
        }
        return velocity
    }

    private fun smoothByTime(track: MotionTrack, values: List<Double>, radiusMs: Long): List<Double> {
        if (radiusMs <= 0L) return values
        return values.indices.map { index ->
            val timestamp = track.frames[index].timestampMs
            val nearby = values.indices.filter {
                abs(track.frames[it].timestampMs - timestamp) <= radiusMs
            }
            nearby.map { values[it] }.averageOrZero()
        }
    }

    private fun smoothNullableByTime(
        track: MotionTrack,
        values: List<Double?>,
        radiusMs: Long,
    ): List<Double?> {
        if (radiusMs <= 0L) return values
        return values.indices.map { index ->
            val timestamp = track.frames[index].timestampMs
            val nearby = values.indices.mapNotNull {
                if (abs(track.frames[it].timestampMs - timestamp) <= radiusMs) values[it] else null
            }
            if (nearby.isEmpty()) null else nearby.average()
        }
    }

    private fun turningPoints(
        track: MotionTrack,
        values: List<Double?>,
        start: Int,
        end: Int,
        config: AlignmentConfig,
    ): List<TurningPoint> {
        if (end - start < 2) return emptyList()
        val derivatives = DoubleArray(values.size)
        val finiteDerivatives = mutableListOf<Double>()
        for (index in start + 1..end) {
            val previous = values[index - 1]
            val current = values[index]
            val deltaMs = track.frames[index].timestampMs - track.frames[index - 1].timestampMs
            if (previous != null && current != null && deltaMs > 0L) {
                derivatives[index] = (current - previous) * 1000.0 / deltaMs
                if (abs(derivatives[index]) > 1e-9) finiteDerivatives += abs(derivatives[index])
            }
        }
        val epsilon = if (finiteDerivatives.isEmpty()) 1e-9 else quantile(finiteDerivatives, 0.10) * 0.10
        val candidates = mutableListOf<TurningPoint>()
        var previousSign = 0
        var previousSignedIndex = start
        for (index in start + 1..end) {
            val sign = when {
                derivatives[index] > epsilon -> 1
                derivatives[index] < -epsilon -> -1
                else -> 0
            }
            if (sign == 0) continue
            if (previousSign != 0 && sign != previousSign) {
                val searchStart = previousSignedIndex.coerceAtLeast(start)
                val searchEnd = index.coerceAtMost(end)
                val range = searchStart..searchEnd
                val candidateIndex = if (previousSign > 0 && sign < 0) {
                    range.maxByOrNull { values[it] ?: Double.NEGATIVE_INFINITY }
                } else {
                    range.minByOrNull { values[it] ?: Double.POSITIVE_INFINITY }
                } ?: index - 1
                val current = values[candidateIndex]
                if (current != null) {
                    val timestamp = track.frames[candidateIndex].timestampMs
                    val left = values.indices.mapNotNull { candidate ->
                        val candidateTimestamp = track.frames[candidate].timestampMs
                        if (candidate < candidateIndex && timestamp - candidateTimestamp <= config.turningNeighborhoodMs) {
                            values[candidate]
                        } else {
                            null
                        }
                    }
                    val right = values.indices.mapNotNull { candidate ->
                        val candidateTimestamp = track.frames[candidate].timestampMs
                        if (candidate > candidateIndex && candidateTimestamp - timestamp <= config.turningNeighborhoodMs) {
                            values[candidate]
                        } else {
                            null
                        }
                    }
                    if (left.isNotEmpty() && right.isNotEmpty()) {
                        val isMinimum = previousSign < 0 && sign > 0
                        val prominence = if (isMinimum) {
                            minOf(left.maxOrNull()!! - current, right.maxOrNull()!! - current)
                        } else {
                            minOf(current - left.minOrNull()!!, current - right.minOrNull()!!)
                        }
                        if (prominence >= config.turningProminenceNormalized) {
                            candidates += TurningPoint(candidateIndex, isMinimum, prominence)
                        }
                    }
                }
            }
            previousSign = sign
            previousSignedIndex = index - 1
        }

        val accepted = mutableListOf<TurningPoint>()
        for (candidate in candidates) {
            val previous = accepted.lastOrNull()
            if (
                previous != null &&
                track.frames[candidate.index].timestampMs - track.frames[previous.index].timestampMs < config.minimumTurningGapMs
            ) {
                if (candidate.prominence > previous.prominence) accepted[accepted.lastIndex] = candidate
            } else {
                accepted += candidate
            }
        }
        return accepted.distinctBy { it.index }
    }

    private fun repBoundaries(
        track: MotionTrack,
        start: Int,
        end: Int,
        turns: List<TurningPoint>,
        config: AlignmentConfig,
    ): List<Int> {
        val endTimestamp = track.frames[end].timestampMs
        val boundaries = mutableListOf(start)
        for (turn in turns.filter { it.isMinimum }) {
            val timestamp = track.frames[turn.index].timestampMs
            val previousTimestamp = track.frames[boundaries.last()].timestampMs
            if (
                timestamp - previousTimestamp >= config.minimumRepDurationMs &&
                endTimestamp - timestamp >= config.minimumRepDurationMs / 2
            ) {
                boundaries += turn.index
            }
        }
        if (
            boundaries.size > 1 &&
            endTimestamp - track.frames[boundaries.last()].timestampMs < config.minimumRepDurationMs / 2
        ) {
            boundaries.removeAt(boundaries.lastIndex)
        }
        if (boundaries.last() != end) boundaries += end
        return boundaries.distinct()
    }
}
