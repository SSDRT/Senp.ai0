package ai.senp.motion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Select directly observed cycles and build phase curves without the generic all-feature periodicity vote. */
internal fun compileRepCycles(sequence: SpatialSequenceAnalysis, exerciseId: String,
    fingerprint: RepEvidenceFingerprint, config: QualifiedRepConfig): QualifiedRepCompilation {
    fun fail(reason: RepReferenceFailure, detail: String) = QualifiedRepCompilation.Unavailable(reason,detail)
    val exercise = ExerciseTechniqueCatalog.builtIn.find(exerciseId) ?: return fail(RepReferenceFailure.UNKNOWN_EXERCISE,"Select an explicit exercise")
    val spec = repExerciseSpecs[exercise.id] ?: return fail(RepReferenceFailure.UNSUPPORTED_EXERCISE,"No rep policy for ${exercise.id}")
    val side = config.side.name.lowercase()
    val driver = "angle.${side}_${spec.driver}"
    val required = (spec.quality.map { "angle.${side}_$it" }+driver).toSet()
    val frames = sequence.frames
    val continuityBreaks = frames.zipWithNext().filter { (a,b) ->
        a.spatialSegmentId != b.spatialSegmentId || a.selectedSubjectId != b.selectedSubjectId
    }.mapTo(mutableSetOf()) { it.second.timestamp.value }
    fun value(frame: SpatialObservationFrame,name: String): Double? = frame.intrinsicDescriptor.values[name]?.takeIf {
        frame.evidenceKind == SpatialEvidenceKind.THREE_D && it in 0.0..180.0 &&
            (frame.intrinsicDescriptor.featureConfidences[name] ?: 0.0) >= config.minimumConfidence
    }
    // Reference cycles must belong to one explicitly tracked identity, just as live
    // attempts cannot cross subject changes. This is tracker provenance, not biometric
    // identification; matching tracker IDs still cannot prove the same real person.
    val identified = frames.filter { frame -> required.any { value(frame,it) != null } }
    if (identified.any { it.selectedSubjectId.isNullOrBlank() })
        return fail(RepReferenceFailure.MISSING_PROVENANCE,"Selected-side reference evidence has no tracked subject identity")
    if (identified.map { it.selectedSubjectId }.distinct().size > 1)
        return fail(RepReferenceFailure.AMBIGUOUS_REFERENCE_SUBJECT,"Reference spans multiple tracked subjects; cycles cannot be pooled across people")
    val anchors = frames.mapNotNull { value(it,driver) }
    if (anchors.size < 30) return fail(RepReferenceFailure.MISSING_FEATURE,"Fewer than 30 directly observed selected-side driver samples")
    for (name in required-driver) {
        val observed = frames.count { value(it,name) != null }
        if (observed < 30) return fail(RepReferenceFailure.MISSING_FEATURE,"Only $observed directly observed $name samples; 30 required")
    }
    val endpoints = repeatedRepEndpoints(frames.map { it.timestamp.value to value(it,driver) },config,continuityBreaks)
    val lo = endpoints?.first ?: quantile(anchors,0.10);val hi = endpoints?.second ?: quantile(anchors,0.90)
    if (hi-lo < config.minimumReferenceExcursionDegrees) return fail(RepReferenceFailure.INSUFFICIENT_EXCURSION,"Reference driver has insufficient robust excursion")
    val readyAngle = if (spec.readyAtMaximum) hi else lo;val farAngle = if (spec.readyAtMaximum) lo else hi
    fun progress(frame: SpatialObservationFrame) = value(frame,driver)?.let { (it-readyAngle)/(farAngle-readyAngle) }
    data class Cycle(val start: Int,val peak: Int,val end: Int)
    val cycles = mutableListOf<Cycle>()
    var rejected = 0;var readySince: Long? = null;var readyObservations = 0;var readyIndex = 0;var armed = false
    var start: Int? = null;var peak = 0;var maximum = 0.0;var farSince: Long? = null;var farObservations = 0;var farSeen = false
    var returnSince: Long? = null;var returnObservations = 0
    var priorProgress: Double? = null
    fun reset() { readySince = null;readyObservations = 0;armed = false;start = null;farSince = null;farObservations = 0;farSeen = false;returnSince = null;returnObservations = 0;priorProgress = null }
    frames.forEachIndexed { index,frame ->
        // A reference cycle must be observable under the same continuity rules as
        // live replay. Complete cycles from several bouts may contribute, but one
        // cycle or its ready dwell cannot cross a capture/spatial context boundary.
        if (frame.timestamp.value in continuityBreaks) {
            if (start != null) rejected++
            reset()
        }
        val p = progress(frame)
        if (p == null || index > 0 && frame.timestamp.value-frames[index-1].timestamp.value > config.maximumEvidenceGapMs) {
            if (start != null) rejected++
            reset();return@forEachIndexed
        }
        val visible = required.all { value(frame,it) != null }
        val now = frame.timestamp.value
        val beforeProgress = priorProgress
        priorProgress = p
        if (start == null) {
            // Live replay disarms when critical evidence disappears before an
            // attempt. Do not train a cycle that starts after blind departure.
            if (!visible) { armed = false;readySince = null;readyObservations = 0 }
            if (p <= config.readyProgress && p >= -0.35 && visible) {
                if (readySince == null) { readySince = now;readyObservations = 0 }
                readyObservations++
                readyIndex = index
                if (readyObservations >= 2 && now-requireNotNull(readySince) >= config.readyConfirmationMs) armed = true
            } else if (!armed) { readySince = null;readyObservations = 0 }
            if (armed && visible && p >= config.departureProgress) {
                start = readyIndex;peak = index;maximum = min(requireNotNull(beforeProgress),p);farSince = null;farObservations = 0;farSeen = false
                returnSince = null;returnObservations = 0;armed = false
            } else return@forEachIndexed
        }
        val supportedPeak = min(beforeProgress ?: p,p)
        if (supportedPeak > maximum) { maximum = supportedPeak;peak = index }
        if (p >= config.requiredProgress && visible) {
            if (farSince == null) { farSince = now;farObservations = 0 }
            farObservations++
            if (farObservations >= 2 && now-requireNotNull(farSince) >= config.milestoneConfirmationMs) farSeen = true
        } else { farSince = null;farObservations = 0 }
        if (p <= config.readyProgress && maximum-p >= config.reversalExcursion && visible) {
            if (returnSince == null) { returnSince = now;returnObservations = 0 }
            returnObservations++
            if (returnObservations >= 2 && now-requireNotNull(returnSince) >= config.readyConfirmationMs) {
                val first = requireNotNull(start)
                val chosen = frames.subList(first,index+1)
                val coverage = chosen.count { f -> required.all { value(f,it) != null } }.toDouble()/chosen.size
                if (farSeen && coverage >= config.minimumVisibleFraction && peak > first && peak < index) cycles += Cycle(first,peak,index) else rejected++
                reset();readyIndex = index;readySince = now;readyObservations = 1
            }
        } else { returnSince = null;returnObservations = 0 }
        if (start != null && now-frames[requireNotNull(start)].timestamp.value > config.maximumAttemptMs) { rejected++;reset() }
    }
    if (cycles.size < config.minimumReferenceCycles) return fail(RepReferenceFailure.INSUFFICIENT_REFERENCE_CYCLES,
        "Observed ${cycles.size} usable complete cycles; ${config.minimumReferenceCycles} required; $rejected candidates excluded")
    val selected = cycles.flatMap { frames.subList(it.start,it.end+1) }.distinctBy { it.timestamp }
    val centers = if (config.referenceCurveNodes == 10) listOf(0.0,0.2,0.4,0.6,0.8,1.0,0.8,0.6,0.4,0.2)
        else listOf(0.0,0.4,0.8,1.0,0.8,0.4)
    val farState = centers.indexOf(1.0)
    val stateCount = centers.size
    val states = mutableListOf<ActionStateProfile>()
    val duration = robustDistribution(cycles.map { (frames[it.end].timestamp.value-frames[it.start].timestamp.value).toDouble() },20.0)
    for ((stateIndex,center) in centers.withIndex()) {
        val phaseFrames = cycles.flatMap { cycle ->
            val indices = if (stateIndex in 1 until farState) cycle.start..cycle.peak else if (stateIndex > farState) cycle.peak..cycle.end else cycle.start..cycle.end
            indices.map { frames[it] }.filter { frame ->
                val p = progress(frame) ?: return@filter false
                when (stateIndex) { 0 -> p <= 0.15;farState -> p >= 0.85;else -> abs(p-center) <= 0.15 }
            }
        }.distinctBy { it.timestamp }
        val features = ExerciseTechniqueCatalog.coachableFeatures.filter { it.startsWith("angle.") }.mapNotNull { name ->
            val observed = phaseFrames.mapNotNull { f -> value(f,name)?.let { Triple(it,requireNotNull(f.intrinsicDescriptor.featureConfidences[name]),requireNotNull(progress(f))) } }
            if (observed.size < 5) return@mapNotNull null
            // Remove within-bin movement before estimating repeat/noise bands. Otherwise real motion widens "noise" tolerances.
            val meanP = observed.map { it.third }.average();val meanV = observed.map { it.first }.average()
            val varianceP = observed.sumOf { (it.third-meanP)*(it.third-meanP) }/observed.size
            // Driver progress is defined by these endpoints: its slope is exact, not a noisy regression.
            // Re-estimating it in a narrow endpoint bin shrinks the calibrated excursion and moves completion times.
            val slope = if (name == driver) farAngle-readyAngle else if (config.referenceCurveFit == RepReferenceCurveFit.MEDIAN_PAIRWISE)
                medianPairwiseRepSlope(observed.map { it.third to it.first }) else if (config.referenceCurveFit == RepReferenceCurveFit.BOUNDED_MEDIAN_PAIRWISE)
                boundedPairwiseRepSlope(observed.map { it.third to it.first }) else if (varianceP >= 0.0025)
                observed.sumOf { (it.third-meanP)*(it.first-meanV) }/(varianceP*observed.size) else 0.0
            val atCenter = observed.map { (it.first-slope*(it.third-center)).coerceIn(0.0,180.0) }
            val distribution = robustDistribution(atCenter,0.5).let { it.copy(lower = max(0.0,it.lower),upper = min(180.0,it.upper)) }
            val observability = observed.size.toDouble()/phaseFrames.size
            val confidence = observed.map { it.second }.average()*observability
            ActionFeatureProfile(name,ActionFeatureKind.GEOMETRY,distribution,max(2.0,1.4826*distribution.mad),
                1.0/(1.0+distribution.mad/10.0),1.0,observability,1.0,1.0,confidence)
        }
        if (!features.map { it.name }.containsAll(required)) return fail(RepReferenceFailure.MISSING_FEATURE,"Insufficient critical samples at reference milestone $stateIndex")
        states += ActionStateProfile("rep_phase_$stateIndex",stateIndex,stateIndex.toDouble()/stateCount,(stateIndex+1.0)/stateCount,
            duration.copy(median = duration.median/stateCount,lower = duration.lower/stateCount,upper = duration.upper/stateCount,mad = duration.mad/stateCount),features,
            features.filter { it.name in required }.minOf { it.confidence })
    }
    val confidence = states.minOf { it.confidence }
    val observedCoverage = selected.count { f -> required.all { value(f,it) != null } }.toDouble()/selected.size
    // These validation fields describe selected-cycle measurement coverage, not independent human accuracy.
    val withinOwnBands = selected.count { f ->
        val p = progress(f) ?: return@count false
        states.any { state ->
            val anchor = state.features.first { it.name == driver }
            val anchorP = (anchor.reference.median-readyAngle)/(farAngle-readyAngle)
            abs(anchorP-p) <= 0.2 && state.features.filter { it.name in required }.all { feature ->
                value(f,feature.name)?.let { it >= feature.reference.lower-config.acceptableAllowanceDegrees && it <= feature.reference.upper+config.acceptableAllowanceDegrees } == true
            }
        }
    }.toDouble()/selected.size
    val profile = ActionProfile(ReferenceActionVersions.PROFILE,true,confidence,cycles.size,states,
        states.indices.map { ActionTransitionProfile(it,(it+1)%stateCount,it==states.lastIndex,confidence) },duration,
        states.flatMap { it.features }.groupBy { it.name }.mapValues { (_,features) -> features.maxOf { it.scale } },confidence,
        ActionProfileValidation(withinOwnBands,1.0,confidence,observedCoverage,states.flatMap { it.features }.map { it.reference.outlierFraction }.average()),
        referenceEvidenceKinds = setOf(SpatialEvidenceKind.THREE_D))
    return when (val result = QualifiedRepCompiler.compile(profile,exercise.id,fingerprint,config)) {
        is QualifiedRepCompilation.Unavailable -> result
        is QualifiedRepCompilation.Ready -> {
            val base = result.reference
            fun reference(envelope: RepDriverEnvelope?, calibration: RepReferenceCalibration? = null) = QualifiedRepReference(
                base.profile,base.fingerprint,base.config,base.spec,base.driverFeature,base.requiredFeatures,base.readyAngle,base.farAngle,
                base.nodes,base.sourceTargets,base.sourceTolerances,calibration,envelope)
            fun reconstruct(r: QualifiedRepReference): Int {
                // Mirroring maps a live source onto the demonstrated reference side.
                // The original reference must reconstruct itself in its own orientation;
                // requiring its opposite arm here incorrectly rejects unilateral uploads.
                // Keep the returned reference/config/key in the requested source mode.
                val selfReference = QualifiedRepReference(r.profile,r.fingerprint,
                    r.config.copy(mirrorMode = ActionMirrorMode.DIRECT),r.spec,r.driverFeature,r.requiredFeatures,
                    r.readyAngle,r.farAngle,r.nodes,r.sourceTargets,r.sourceTolerances,r.calibration,r.driverEnvelope)
                val engine = selfReference.newEngine()
                val events = mutableListOf<RepAttemptResult>()
                for (frame in frames.filter { it.timestamp.value >= selected.first().timestamp.value-400 && it.timestamp.value <= selected.last().timestamp.value+200 }) {
                    events += engine.evaluate(frame,fingerprint.poseModelSha256,
                        preprocessingId = fingerprint.preprocessingId,captureContext = fingerprint.declaredView).finalizedAttempts
                }
                val matched = mutableSetOf<Long>()
                return cycles.count { cycle ->
                    val end = frames[cycle.end].timestamp.value
                    val event = events.filter { it.attemptId !in matched && abs(it.completedMs-end) <= 300 && it.startedMs < frames[cycle.peak].timestamp.value }
                        .minByOrNull { abs(it.completedMs-end) }
                    event?.let { matched += it.attemptId }
                    event?.verdict in setOf(RepVerdict.CORRECT,RepVerdict.ACCEPTABLE)
                }
            }
            val baselineAccepted = reconstruct(base)
            var accepted = baselineAccepted
            var envelope: RepDriverEnvelope? = null
            if (config.useObservedDriverEnvelope && accepted.toDouble()/cycles.size < config.minimumReferenceReconstruction) {
                val endpointsByCycle = cycles.map { cycle ->
                    val pairs = frames.subList(cycle.start,cycle.end+1).zipWithNext().mapNotNull { (a,b) ->
                        if (!required.all { value(a,it) != null && value(b,it) != null }) null
                        else requireNotNull(value(a,driver)) to requireNotNull(value(b,driver))
                    }
                    pairs.minOf { max(it.first,it.second) } to pairs.maxOf { min(it.first,it.second) }
                }
                val candidate = observedRepDriverEnvelope(endpointsByCycle,base.readyAngle,base.farAngle,config)
                if (candidate != null) {
                    val reconstructed = reconstruct(reference(candidate))
                    if (reconstructed.toDouble()/cycles.size >= config.minimumReferenceReconstruction) {
                        envelope = candidate;accepted = reconstructed
                    }
                }
            }
            val reconstructed = accepted.toDouble()/cycles.size
            if (reconstructed < config.minimumReferenceReconstruction) return fail(RepReferenceFailure.LOW_SELF_RECONSTRUCTION,
                "Only $accepted/${cycles.size} selected cycles pass causal self-replay; reference withheld")
            QualifiedRepCompilation.Ready(reference(envelope,RepReferenceCalibration(cycles.size,rejected,frames.size,selected.size,
                    selected.first().timestamp.value,selected.last().timestamp.value,accepted,reconstructed,baselineAccepted)))
        }
    }
}

internal fun observedRepDriverEnvelope(endpoints: List<Pair<Double,Double>>, ready: Double, far: Double,
    config: QualifiedRepConfig): RepDriverEnvelope? {
    if (endpoints.size < max(3,config.minimumReferenceCycles)) return null
    if (endpoints.any { (lo,hi) -> lo !in 0.0..180.0 || hi !in 0.0..180.0 || lo > hi }) return null
    val lower = endpoints.map { it.first }.sorted();val upper = endpoints.map { it.second }.sorted()
    for (samples in listOf(lower,upper)) {
        val distribution = robustDistribution(samples,.5)
        if (distribution.inlierCount < config.minimumReferenceCycles || distribution.outlierFraction > .25 ||
            distribution.mad > config.maximumReferenceMadDegrees ||
            distribution.upper-distribution.lower > config.maximumReferenceBandWidthDegrees) return null
    }
    // Second extrema require the edge on two different cycles. An isolated endpoint error
    // cannot widen the range; no quantile extrapolation or interpolated landmark is used.
    val lo = min(min(ready,far),lower[1]);val hi = max(max(ready,far),upper[upper.lastIndex-1])
    if (lo == min(ready,far) && hi == max(ready,far)) return null
    return RepDriverEnvelope(lo,hi,endpoints.size,robustDistribution(lower,.5).mad,robustDistribution(upper,.5).mad)
}

/** Reference-only fit: separated progress pairs avoid amplifying nearly identical noisy positions. */
internal fun medianPairwiseRepSlope(samples: List<Pair<Double,Double>>): Double {
    val slopes = mutableListOf<Double>()
    for (i in samples.indices) for (j in i+1 until samples.size) {
        val delta = samples[j].first-samples[i].first
        if (abs(delta) >= .05) slopes += (samples[j].second-samples[i].second)/delta
    }
    return if (slopes.size >= 3) median(slopes) else 0.0
}

/** At most 8,128 slopes per bin, even for long uploads. Only slope estimation is sampled;
 * confidence, noise distributions, critical endpoints and causal replay still use every observation. */
internal fun boundedPairwiseRepSlope(samples: List<Pair<Double,Double>>): Double {
    val selected = if (samples.size <= 128) samples else (0 until 128).map { index ->
        samples[(index.toLong()*(samples.size-1)/127).toInt()]
    }
    return medianPairwiseRepSlope(selected)
}

/** Estimate repeated extrema, so setup/rest duration cannot redefine the exercise's range.
 * Adjacent supported observations and sustained reversals reject isolated spikes. Final cycle,
 * confidence, visibility, curve-noise and self-replay gates still operate on the original samples.
 */
internal fun repeatedRepEndpoints(samples: List<Pair<Long,Double?>>, config: QualifiedRepConfig,
    continuityBreaks: Set<Long> = emptySet()): Pair<Double,Double>? {
    val minima = mutableListOf<Double>();val maxima = mutableListOf<Double>()
    val reverseDegrees = config.minimumReferenceExcursionDegrees*0.5
    var prior: Double? = null;var previousMs: Long? = null
    var direction = 0;var low = Double.POSITIVE_INFINITY;var high = Double.NEGATIVE_INFINITY
    var reversalSince: Long? = null;var reversalObservations = 0
    fun reset() { prior = null;direction = 0;low = Double.POSITIVE_INFINITY;high = Double.NEGATIVE_INFINITY;reversalSince = null;reversalObservations = 0 }
    for ((now,value) in samples) {
        if (now in continuityBreaks) reset()
        val gap = previousMs?.let { now-it > config.maximumEvidenceGapMs } ?: false
        previousMs = now
        if (value == null || gap) { reset();continue }
        val before = prior;prior = value
        if (before == null) continue
        val supportedLow = max(before,value);val supportedHigh = min(before,value)
        if (direction == 0) {
            low = min(low,supportedLow);high = max(high,supportedHigh)
            if (value-low >= config.minimumReferenceExcursionDegrees) direction = 1
            else if (high-value >= config.minimumReferenceExcursionDegrees) direction = -1
            continue
        }
        if (direction > 0) high = max(high,supportedHigh) else low = min(low,supportedLow)
        val reversing = if (direction > 0) high-value >= reverseDegrees else value-low >= reverseDegrees
        if (reversing) {
            if (reversalSince == null) { reversalSince = now;reversalObservations = 0 }
            reversalObservations++
            if (reversalObservations >= 2 && now-requireNotNull(reversalSince) >= config.reversalConfirmationMs) {
                if (direction > 0) maxima += high else minima += low
                direction = -direction;low = supportedLow;high = supportedHigh
                reversalSince = null;reversalObservations = 0
            }
        } else { reversalSince = null;reversalObservations = 0 }
    }
    if (minima.size < config.minimumReferenceCycles || maxima.size < config.minimumReferenceCycles) return null
    val lo = quantile(minima,0.5);val hi = quantile(maxima,0.5)
    return if (hi-lo >= config.minimumReferenceExcursionDegrees) lo to hi else null
}
