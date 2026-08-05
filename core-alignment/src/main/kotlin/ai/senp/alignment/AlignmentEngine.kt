package ai.senp.alignment

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class AlignmentEngine(private val config: AlignmentConfig = AlignmentConfig()) {
    fun align(
        user: MotionTrack,
        reference: MotionTrack,
        profile: ExerciseProfile,
    ): AlignmentResult {
        if (user.frames.isEmpty() || reference.frames.isEmpty()) {
            return AlignmentResult(
                mode = AlignmentMode.EMPTY,
                mapping = emptyList(),
                windows = emptyList(),
                userPhase = diagnostics(user, null, 0L),
                referencePhase = diagnostics(reference, null, 0L),
            )
        }

        val userPhase = PhaseDetector.detect(user, profile, config)
        val referencePhase = PhaseDetector.detect(reference, profile, config)
        if (userPhase.insufficientMotion || referencePhase.insufficientMotion) {
            return linearFallback(user, reference, userPhase, referencePhase, profile)
        }

        val requestedTrim = estimatePhaseTrim(user, reference, userPhase, referencePhase)
        val userStart = acceptedTrimmedStart(user, userPhase, requestedTrim.userTrimMs)
        val referenceStart = acceptedTrimmedStart(reference, referencePhase, requestedTrim.referenceTrimMs)
        val userTrimMs = user.frames[userStart].timestampMs - user.frames[userPhase.activeStart].timestampMs
        val referenceTrimMs = reference.frames[referenceStart].timestampMs -
            reference.frames[referencePhase.activeStart].timestampMs

        val userEnd = userPhase.activeEnd
        val referenceEnd = referencePhase.activeEnd
        val userBoundaries = clippedBoundaries(userPhase.repBoundaries, userStart, userEnd)
        val referenceBoundaries = clippedBoundaries(referencePhase.repBoundaries, referenceStart, referenceEnd)
        val userAnchors = clippedBoundaries(userPhase.anchors, userStart, userEnd)
        val referenceAnchors = clippedBoundaries(referencePhase.anchors, referenceStart, referenceEnd)

        var mode = AlignmentMode.BANDED_GLOBAL_DTW
        var path = emptyList<DtwNode>()
        if (userBoundaries.size >= 4 && referenceBoundaries.size >= 4) {
            path = repNormalizedPath(
                user,
                reference,
                userBoundaries,
                referenceBoundaries,
                profile,
            )
            if (path.isNotEmpty()) mode = AlignmentMode.REP_NORMALIZED
        }
        if (path.isEmpty() && userAnchors.size >= 4 && referenceAnchors.size >= 4) {
            path = anchoredPath(user, reference, userAnchors, referenceAnchors, profile)
            if (path.isNotEmpty()) mode = AlignmentMode.ANCHOR_CONSTRAINED_DTW
        }
        if (path.isEmpty()) {
            path = globalPath(user, reference, userStart, userEnd, referenceStart, referenceEnd, profile)
            mode = AlignmentMode.BANDED_GLOBAL_DTW
        }

        check(path.isNotEmpty()) { "banded DTW unexpectedly produced no path for non-empty active tracks" }
        val mappingIndices = buildMapping(path, user, reference)
        val mapping = buildPoints(
            user = user,
            reference = reference,
            mapping = mappingIndices,
            profile = profile,
            activeStartMs = user.frames[userStart].timestampMs,
            activeEndMs = user.frames[userEnd].timestampMs,
        )
        val repBoundariesMs = userBoundaries.map { user.frames[it].timestampMs }
        val windows = WindowEngine(config).detect(mapping, repBoundariesMs)

        return AlignmentResult(
            mode = mode,
            mapping = mapping,
            windows = windows,
            userPhase = diagnostics(user, userPhase, userTrimMs),
            referencePhase = diagnostics(reference, referencePhase, referenceTrimMs),
        )
    }

    private fun acceptedTrimmedStart(track: MotionTrack, phase: PhaseState, trimMs: Long): Int {
        if (trimMs <= 0L) return phase.activeStart
        val targetTimestamp = track.frames[phase.activeStart].timestampMs + trimMs
        val candidate = indexAtOrAfter(track, targetTimestamp)
        val remainingDuration = track.frames[phase.activeEnd].timestampMs - track.frames[candidate].timestampMs
        return if (remainingDuration >= config.minimumActiveDurationMs) candidate else phase.activeStart
    }

    private fun estimatePhaseTrim(
        user: MotionTrack,
        reference: MotionTrack,
        userPhase: PhaseState,
        referencePhase: PhaseState,
    ): PhaseTrim {
        val userStartMs = user.frames[userPhase.activeStart].timestampMs
        val userEndMs = user.frames[userPhase.activeEnd].timestampMs
        val referenceStartMs = reference.frames[referencePhase.activeStart].timestampMs
        val referenceEndMs = reference.frames[referencePhase.activeEnd].timestampMs
        val userDurationMs = userEndMs - userStartMs
        val referenceDurationMs = referenceEndMs - referenceStartMs
        val shortestDuration = min(userDurationMs, referenceDurationMs)
        if (shortestDuration < config.minimumPhaseOverlapMs) return PhaseTrim()

        val userNormalized = normalizeFinite(userPhase.phaseSignal)
        val referenceNormalized = normalizeFinite(referencePhase.phaseSignal)
        val userTimestamps = user.frames.map { it.timestampMs }
        val referenceTimestamps = reference.frames.map { it.timestampMs }
        val stepMs = max(
            config.phaseResampleStepMs,
            min(medianTimestampDelta(user), medianTimestampDelta(reference)),
        )
        val maximumLagMs = (shortestDuration * config.maximumPhaseShiftFraction).toLong()
        var bestLagMs = 0L
        var bestScore = Double.NEGATIVE_INFINITY
        var lagMs = -maximumLagMs

        while (lagMs <= maximumLagMs) {
            val elapsedStart = max(0L, -lagMs)
            val elapsedEnd = min(userDurationMs, referenceDurationMs - lagMs)
            if (elapsedEnd - elapsedStart >= config.minimumPhaseOverlapMs) {
                val pairs = mutableListOf<Pair<Double, Double>>()
                var elapsed = elapsedStart
                while (elapsed <= elapsedEnd) {
                    val userValue = interpolateSeries(
                        userTimestamps,
                        userNormalized,
                        userStartMs + elapsed,
                        config.maximumInterpolationGapMs,
                    )
                    val referenceValue = interpolateSeries(
                        referenceTimestamps,
                        referenceNormalized,
                        referenceStartMs + elapsed + lagMs,
                        config.maximumInterpolationGapMs,
                    )
                    if (userValue != null && referenceValue != null) pairs += userValue to referenceValue
                    elapsed += stepMs
                }
                if (pairs.size >= 3 && pairs.size.toLong() * stepMs >= config.minimumPhaseOverlapMs) {
                    val userMean = pairs.map { it.first }.average()
                    val referenceMean = pairs.map { it.second }.average()
                    val numerator = pairs.sumOf { (it.first - userMean) * (it.second - referenceMean) }
                    val denominator = sqrt(
                        pairs.sumOf { (it.first - userMean) * (it.first - userMean) } *
                            pairs.sumOf { (it.second - referenceMean) * (it.second - referenceMean) },
                    )
                    val correlation = if (denominator <= 1e-9) -1.0 else numerator / denominator
                    val lagPenalty = 0.08 * abs(lagMs).toDouble() / max(1L, maximumLagMs)
                    val score = correlation - lagPenalty
                    if (score > bestScore) {
                        bestScore = score
                        bestLagMs = lagMs
                    }
                }
            }
            lagMs += stepMs
        }

        if (bestScore < 0.05) return PhaseTrim()
        return when {
            bestLagMs > 0L -> PhaseTrim(referenceTrimMs = bestLagMs)
            bestLagMs < 0L -> PhaseTrim(userTrimMs = -bestLagMs)
            else -> PhaseTrim()
        }
    }

    private fun repNormalizedPath(
        user: MotionTrack,
        reference: MotionTrack,
        userBoundaries: List<Int>,
        referenceBoundaries: List<Int>,
        profile: ExerciseProfile,
    ): List<DtwNode> {
        val userRepCount = userBoundaries.size - 1
        val referenceRepCount = referenceBoundaries.size - 1
        val pairCount = max(userRepCount, referenceRepCount)
        if (pairCount <= 0) return emptyList()

        val assignments = (0 until pairCount).map { pairIndex ->
            val userRep = if (pairCount == 1) 0 else
                (pairIndex.toDouble() * (userRepCount - 1) / (pairCount - 1)).roundToInt()
            val referenceRep = if (pairCount == 1) 0 else
                (pairIndex.toDouble() * (referenceRepCount - 1) / (pairCount - 1)).roundToInt()
            userRep to referenceRep
        }
        val userOccurrences = assignments.groupingBy { it.first }.eachCount()
        val referenceOccurrences = assignments.groupingBy { it.second }.eachCount()
        val userSeen = mutableMapOf<Int, Int>()
        val referenceSeen = mutableMapOf<Int, Int>()
        val output = mutableListOf<DtwNode>()

        for ((userRep, referenceRep) in assignments) {
            val userOccurrence = userSeen.getOrDefault(userRep, 0)
            val referenceOccurrence = referenceSeen.getOrDefault(referenceRep, 0)
            userSeen[userRep] = userOccurrence + 1
            referenceSeen[referenceRep] = referenceOccurrence + 1

            val userInterval = splitInterval(
                user,
                userBoundaries[userRep],
                userBoundaries[userRep + 1],
                userOccurrence,
                userOccurrences.getValue(userRep),
            ) ?: continue
            val referenceInterval = splitInterval(
                reference,
                referenceBoundaries[referenceRep],
                referenceBoundaries[referenceRep + 1],
                referenceOccurrence,
                referenceOccurrences.getValue(referenceRep),
            ) ?: continue

            val userSegment = resampleSegment(user, userInterval.first, userInterval.last, profile)
            val referenceSegment = resampleSegment(reference, referenceInterval.first, referenceInterval.last, profile)
            val local = Dtw.path(userSegment.frames, referenceSegment.frames, profile, config)
            if (local.isEmpty()) continue
            val mapped = local.map { node ->
                node.copy(
                    user = userSegment.sourceIndices[node.user],
                    reference = referenceSegment.sourceIndices[node.reference],
                )
            }
            output += if (output.isEmpty()) mapped else mapped.drop(1)
        }
        return monotonicPath(output)
    }

    private fun splitInterval(
        track: MotionTrack,
        startIndex: Int,
        endIndex: Int,
        occurrence: Int,
        occurrenceCount: Int,
    ): IntRange? {
        if (endIndex <= startIndex) return null
        if (occurrenceCount <= 1) return startIndex..endIndex
        val startTimestamp = track.frames[startIndex].timestampMs
        val endTimestamp = track.frames[endIndex].timestampMs
        val duration = endTimestamp - startTimestamp
        val splitStartTimestamp = startTimestamp + duration * occurrence / occurrenceCount
        val splitEndTimestamp = if (occurrence == occurrenceCount - 1) {
            endTimestamp
        } else {
            startTimestamp + duration * (occurrence + 1) / occurrenceCount
        }
        val splitStart = indexAtOrAfter(track, splitStartTimestamp).coerceIn(startIndex, endIndex)
        val splitEnd = indexAtOrBefore(track, splitEndTimestamp).coerceIn(splitStart, endIndex)
        return if (splitEnd > splitStart) splitStart..splitEnd else null
    }

    private fun resampleSegment(
        track: MotionTrack,
        startIndex: Int,
        endIndex: Int,
        profile: ExerciseProfile,
    ): ResampledSegment {
        val startTimestamp = track.frames[startIndex].timestampMs
        val endTimestamp = track.frames[endIndex].timestampMs
        val timestamps = (0 until config.repNormalizationSamples).map { sampleIndex ->
            if (config.repNormalizationSamples == 1) startTimestamp else {
                startTimestamp +
                    (endTimestamp - startTimestamp) * sampleIndex / (config.repNormalizationSamples - 1)
            }
        }
        return ResampledSegment(
            frames = timestamps.map { interpolateFrame(track, it, profile, config) },
            sourceIndices = timestamps.map { nearestIndex(track, it).coerceIn(startIndex, endIndex) }.toIntArray(),
        )
    }

    private fun anchoredPath(
        user: MotionTrack,
        reference: MotionTrack,
        userAnchors: List<Int>,
        referenceAnchors: List<Int>,
        profile: ExerciseProfile,
    ): List<DtwNode> {
        val anchorCount = min(userAnchors.size, referenceAnchors.size)
        if (anchorCount < 2) return emptyList()
        val selectedUser = (0 until anchorCount).map { index ->
            userAnchors[(index.toDouble() * (userAnchors.size - 1) / (anchorCount - 1)).roundToInt()]
        }
        val selectedReference = (0 until anchorCount).map { index ->
            referenceAnchors[(index.toDouble() * (referenceAnchors.size - 1) / (anchorCount - 1)).roundToInt()]
        }

        val output = mutableListOf<DtwNode>()
        for (segmentIndex in 0 until anchorCount - 1) {
            val userStart = selectedUser[segmentIndex]
            val userEnd = selectedUser[segmentIndex + 1]
            val referenceStart = selectedReference[segmentIndex]
            val referenceEnd = selectedReference[segmentIndex + 1]
            if (userEnd <= userStart || referenceEnd <= referenceStart) continue
            val local = Dtw.path(
                user.frames.subList(userStart, userEnd + 1),
                reference.frames.subList(referenceStart, referenceEnd + 1),
                profile,
                config,
            ).map { node ->
                node.copy(user = node.user + userStart, reference = node.reference + referenceStart)
            }
            output += if (output.isEmpty()) local else local.drop(1)
        }
        return monotonicPath(output)
    }

    private fun globalPath(
        user: MotionTrack,
        reference: MotionTrack,
        userStart: Int,
        userEnd: Int,
        referenceStart: Int,
        referenceEnd: Int,
        profile: ExerciseProfile,
    ): List<DtwNode> = Dtw.path(
        user.frames.subList(userStart, userEnd + 1),
        reference.frames.subList(referenceStart, referenceEnd + 1),
        profile,
        config,
    ).map { node ->
        node.copy(user = node.user + userStart, reference = node.reference + referenceStart)
    }

    private fun monotonicPath(path: List<DtwNode>): List<DtwNode> {
        val output = mutableListOf<DtwNode>()
        var lastUser = -1
        var lastReference = -1
        for (node in path) {
            if (node.user < lastUser || node.reference < lastReference) continue
            if (node.user == lastUser && node.reference == lastReference) continue
            output += node
            lastUser = node.user
            lastReference = node.reference
        }
        return output
    }

    private fun clippedBoundaries(boundaries: List<Int>, start: Int, end: Int): List<Int> =
        (listOf(start) + boundaries.filter { it in (start + 1) until end } + listOf(end))
            .distinct()
            .sorted()

    private fun buildMapping(
        path: List<DtwNode>,
        user: MotionTrack,
        reference: MotionTrack,
    ): IntArray {
        val buckets = Array(user.frames.size) { mutableListOf<Int>() }
        for (node in path) {
            if (node.user in buckets.indices && node.reference in reference.frames.indices) {
                buckets[node.user] += node.reference
            }
        }
        val known = buckets.indices.filter { buckets[it].isNotEmpty() }
        check(known.isNotEmpty()) { "DTW path contained no valid user/reference pairs" }
        val knownReference = known.associateWith { userIndex ->
            median(buckets[userIndex].map(Int::toDouble)).roundToInt().coerceIn(0, reference.frames.lastIndex)
        }
        val output = IntArray(user.frames.size)

        for (userIndex in output.indices) {
            val targetReferenceTimestamp = when {
                userIndex in knownReference -> reference.frames[knownReference.getValue(userIndex)].timestampMs
                userIndex < known.first() -> interpolateTimestamp(
                    user.frames.first().timestampMs,
                    user.frames[known.first()].timestampMs,
                    reference.frames.first().timestampMs,
                    reference.frames[knownReference.getValue(known.first())].timestampMs,
                    user.frames[userIndex].timestampMs,
                )
                userIndex > known.last() -> interpolateTimestamp(
                    user.frames[known.last()].timestampMs,
                    user.frames.last().timestampMs,
                    reference.frames[knownReference.getValue(known.last())].timestampMs,
                    reference.frames.last().timestampMs,
                    user.frames[userIndex].timestampMs,
                )
                else -> {
                    val rightPosition = known.binarySearch(userIndex).let { if (it >= 0) it else -it - 1 }
                    val leftIndex = known[rightPosition - 1]
                    val rightIndex = known[rightPosition]
                    interpolateTimestamp(
                        user.frames[leftIndex].timestampMs,
                        user.frames[rightIndex].timestampMs,
                        reference.frames[knownReference.getValue(leftIndex)].timestampMs,
                        reference.frames[knownReference.getValue(rightIndex)].timestampMs,
                        user.frames[userIndex].timestampMs,
                    )
                }
            }
            output[userIndex] = nearestIndex(reference, targetReferenceTimestamp)
        }
        return regularizeMapping(output, user, reference)
    }

    private fun interpolateTimestamp(
        sourceStart: Long,
        sourceEnd: Long,
        targetStart: Long,
        targetEnd: Long,
        sourceValue: Long,
    ): Long {
        if (sourceEnd <= sourceStart) return targetStart
        val fraction = (sourceValue - sourceStart).toDouble() / (sourceEnd - sourceStart).toDouble()
        return (targetStart + fraction * (targetEnd - targetStart)).toLong()
    }

    private fun regularizeMapping(
        mapping: IntArray,
        user: MotionTrack,
        reference: MotionTrack,
    ): IntArray {
        val output = mapping.copyOf()
        for (index in output.indices) output[index] = output[index].coerceIn(0, reference.frames.lastIndex)
        for (index in 1 until output.size) output[index] = max(output[index], output[index - 1])

        var runStart = 0
        while (runStart < output.size) {
            var runEnd = runStart
            while (runEnd + 1 < output.size && output[runEnd + 1] == output[runStart]) runEnd += 1
            if (
                runEnd > runStart &&
                user.frames[runEnd].timestampMs - user.frames[runStart].timestampMs > config.maximumFrozenMappingMs
            ) {
                val targetEndIndex = if (runEnd + 1 < output.size) output[runEnd + 1] else reference.frames.lastIndex
                if (targetEndIndex > output[runStart]) {
                    val targetStartTimestamp = reference.frames[output[runStart]].timestampMs
                    val targetEndTimestamp = reference.frames[targetEndIndex].timestampMs
                    val sourceStartTimestamp = user.frames[runStart].timestampMs
                    val sourceEndTimestamp = user.frames[runEnd].timestampMs
                    for (index in runStart..runEnd) {
                        val desired = interpolateTimestamp(
                            sourceStartTimestamp,
                            sourceEndTimestamp,
                            targetStartTimestamp,
                            targetEndTimestamp,
                            user.frames[index].timestampMs,
                        )
                        output[index] = nearestIndex(reference, desired)
                    }
                }
            }
            runStart = runEnd + 1
        }
        for (index in 1 until output.size) output[index] = max(output[index], output[index - 1])
        return output
    }

    private fun buildPoints(
        user: MotionTrack,
        reference: MotionTrack,
        mapping: IntArray,
        profile: ExerciseProfile,
        activeStartMs: Long,
        activeEndMs: Long,
    ): List<MappingPoint> {
        val metrics = mapping.indices.map { index ->
            compareFrames(user.frames[index], reference.frames[mapping[index]], profile)
        }
        val blind = stabilizeBlindMask(
            user,
            metrics.map { it.coverage < config.minimumCommonFeatureCoverage }.toBooleanArray(),
        )
        val slopes = DoubleArray(mapping.size) { 1.0 }
        for (index in mapping.indices) {
            var lower = index
            var upper = index
            while (
                lower > 0 &&
                user.frames[index].timestampMs - user.frames[lower].timestampMs < config.slopeWindowMs
            ) lower -= 1
            while (
                upper < mapping.lastIndex &&
                user.frames[upper].timestampMs - user.frames[index].timestampMs < config.slopeWindowMs
            ) upper += 1
            val userDelta = max(1L, user.frames[upper].timestampMs - user.frames[lower].timestampMs)
            val referenceDelta = reference.frames[mapping[upper]].timestampMs -
                reference.frames[mapping[lower]].timestampMs
            slopes[index] = (referenceDelta.toDouble() / userDelta).coerceIn(0.001, 8.0)
        }

        return mapping.indices.map { index ->
            val metric = metrics[index]
            val slopeConfidence = max(
                config.slopeConfidenceFloor,
                exp(-config.slopeConfidenceLambda * abs(ln(slopes[index]))),
            )
            val confidence = if (blind[index]) {
                0.0
            } else {
                (slopeConfidence * sqrt(metric.coverage)).coerceIn(0.0, 1.0)
            }
            MappingPoint(
                userTimestampMs = user.frames[index].timestampMs,
                referenceTimestampMs = reference.frames[mapping[index]].timestampMs,
                alignmentConfidence = confidence,
                commonCoverage = metric.coverage,
                pathSlope = slopes[index],
                rawDifference = metric.rawDifference,
                maximumDifference = metric.maximumDifference,
                weightedDifference = metric.rawDifference * confidence,
                blind = blind[index],
                active = user.frames[index].timestampMs in activeStartMs..activeEndMs,
            )
        }
    }

    private fun stabilizeBlindMask(track: MotionTrack, initial: BooleanArray): BooleanArray {
        val output = initial.copyOf()
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (index in 0..initial.size) {
            if (index < initial.size && initial[index]) {
                if (start < 0) start = index
            } else if (start >= 0) {
                runs += start..(index - 1)
                start = -1
            }
        }
        if (runs.size < 2) return output

        var mergedStart = runs.first().first
        var mergedEnd = runs.first().last
        for (run in runs.drop(1)) {
            val gapMs = track.frames[run.first].timestampMs - track.frames[mergedEnd].timestampMs
            if (gapMs <= config.maximumInterpolationGapMs) {
                mergedEnd = run.last
            } else {
                markLongBlindSpan(track, output, mergedStart, mergedEnd)
                mergedStart = run.first
                mergedEnd = run.last
            }
        }
        markLongBlindSpan(track, output, mergedStart, mergedEnd)
        return output
    }

    private fun markLongBlindSpan(
        track: MotionTrack,
        mask: BooleanArray,
        start: Int,
        end: Int,
    ) {
        if (track.frames[end].timestampMs - track.frames[start].timestampMs > config.maximumConfidentBlindSpanMs) {
            for (index in start..end) mask[index] = true
        }
    }

    private fun linearFallback(
        user: MotionTrack,
        reference: MotionTrack,
        userPhase: PhaseState,
        referencePhase: PhaseState,
        profile: ExerciseProfile,
    ): AlignmentResult {
        val mapping = IntArray(user.frames.size) { index ->
            val targetTimestamp = interpolateTimestamp(
                user.frames.first().timestampMs,
                user.frames.last().timestampMs,
                reference.frames.first().timestampMs,
                reference.frames.last().timestampMs,
                user.frames[index].timestampMs,
            )
            nearestIndex(reference, targetTimestamp)
        }
        val points = buildPoints(
            user,
            reference,
            mapping,
            profile,
            activeStartMs = Long.MAX_VALUE,
            activeEndMs = Long.MIN_VALUE,
        ).map { point ->
            point.copy(
                alignmentConfidence = min(point.alignmentConfidence, 0.35),
                weightedDifference = point.rawDifference * min(point.alignmentConfidence, 0.35),
                active = false,
            )
        }
        return AlignmentResult(
            mode = AlignmentMode.LINEAR_INSUFFICIENT_MOTION,
            mapping = points,
            windows = emptyList(),
            userPhase = diagnostics(user, userPhase, 0L),
            referencePhase = diagnostics(reference, referencePhase, 0L),
        )
    }

    private fun diagnostics(track: MotionTrack, state: PhaseState?, shiftMs: Long): PhaseDiagnostics =
        PhaseDiagnostics(
            activeStartMs = state?.takeIf { it.activeStart in track.frames.indices }
                ?.let { track.frames[it.activeStart].timestampMs },
            activeEndMs = state?.takeIf { it.activeEnd in track.frames.indices }
                ?.let { track.frames[it.activeEnd].timestampMs },
            phaseShiftMs = shiftMs,
            repBoundariesMs = state?.repBoundaries.orEmpty()
                .filter { it in track.frames.indices }
                .map { track.frames[it].timestampMs },
            anchorsMs = state?.anchors.orEmpty()
                .filter { it in track.frames.indices }
                .map { track.frames[it].timestampMs },
            insufficientMotion = state?.insufficientMotion ?: true,
            motionStrength = state?.motionStrength ?: 0.0,
        )
}

private data class PhaseTrim(
    val userTrimMs: Long = 0L,
    val referenceTrimMs: Long = 0L,
)

private data class ResampledSegment(
    val frames: List<MotionFrame>,
    val sourceIndices: IntArray,
)
