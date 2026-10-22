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
    fun value(frame: SpatialObservationFrame,name: String): Double? = frame.intrinsicDescriptor.values[name]?.takeIf {
        frame.evidenceKind == SpatialEvidenceKind.THREE_D && it in 0.0..180.0 &&
            (frame.intrinsicDescriptor.featureConfidences[name] ?: 0.0) >= config.minimumConfidence
    }
    val anchors = frames.mapNotNull { value(it,driver) }
    if (anchors.size < 30) return fail(RepReferenceFailure.MISSING_FEATURE,"Fewer than 30 directly observed selected-side driver samples")
    val lo = quantile(anchors,0.10);val hi = quantile(anchors,0.90)
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
        val p = progress(frame)
        if (p == null || index > 0 && frame.timestamp.value-frames[index-1].timestamp.value > config.maximumEvidenceGapMs) {
            if (start != null) rejected++
            reset();return@forEachIndexed
        }
        val visible = required.all { value(frame,it) != null }
        val now = frame.timestamp.value
        if (start == null) {
            if (p <= config.readyProgress && p >= -0.35 && visible) {
                if (readySince == null) { readySince = now;readyObservations = 0 }
                readyObservations++
                readyIndex = index
                if (readyObservations >= 2 && now-requireNotNull(readySince) >= config.readyConfirmationMs) armed = true
            } else if (!armed) { readySince = null;readyObservations = 0 }
            if (armed && visible && p >= config.departureProgress) {
                start = readyIndex;peak = index;maximum = p;farSince = null;farObservations = 0;farSeen = false
                returnSince = null;returnObservations = 0;armed = false
            } else return@forEachIndexed
        }
        val supportedPeak = min(priorProgress ?: p,p)
        if (supportedPeak > maximum) { maximum = supportedPeak;peak = index }
        priorProgress = p
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
    val centers = listOf(0.0,0.4,0.8,1.0,0.8,0.4)
    val states = mutableListOf<ActionStateProfile>()
    val duration = robustDistribution(cycles.map { (frames[it.end].timestamp.value-frames[it.start].timestamp.value).toDouble() },20.0)
    for ((stateIndex,center) in centers.withIndex()) {
        val phaseFrames = cycles.flatMap { cycle ->
            val indices = if (stateIndex in 1..2) cycle.start..cycle.peak else if (stateIndex >= 4) cycle.peak..cycle.end else cycle.start..cycle.end
            indices.map { frames[it] }.filter { frame ->
                val p = progress(frame) ?: return@filter false
                when (stateIndex) { 0 -> p <= 0.15;3 -> p >= 0.85;else -> abs(p-center) <= 0.15 }
            }
        }.distinctBy { it.timestamp }
        val features = ExerciseTechniqueCatalog.coachableFeatures.filter { it.startsWith("angle.") }.mapNotNull { name ->
            val observed = phaseFrames.mapNotNull { f -> value(f,name)?.let { Triple(it,requireNotNull(f.intrinsicDescriptor.featureConfidences[name]),requireNotNull(progress(f))) } }
            if (observed.size < 5) return@mapNotNull null
            // Remove within-bin movement before estimating repeat/noise bands. Otherwise real motion widens "noise" tolerances.
            val meanP = observed.map { it.third }.average();val meanV = observed.map { it.first }.average()
            val varianceP = observed.sumOf { (it.third-meanP)*(it.third-meanP) }/observed.size
            val slope = if (varianceP >= 0.0025) observed.sumOf { (it.third-meanP)*(it.first-meanV) }/(varianceP*observed.size) else 0.0
            val atCenter = observed.map { (it.first-slope*(it.third-center)).coerceIn(0.0,180.0) }
            val distribution = robustDistribution(atCenter,0.5).let { it.copy(lower = max(0.0,it.lower),upper = min(180.0,it.upper)) }
            val observability = observed.size.toDouble()/phaseFrames.size
            val confidence = observed.map { it.second }.average()*observability
            ActionFeatureProfile(name,ActionFeatureKind.GEOMETRY,distribution,max(2.0,1.4826*distribution.mad),
                1.0/(1.0+distribution.mad/10.0),1.0,observability,1.0,1.0,confidence)
        }
        if (!features.map { it.name }.containsAll(required)) return fail(RepReferenceFailure.MISSING_FEATURE,"Insufficient critical samples at reference milestone $stateIndex")
        states += ActionStateProfile("rep_phase_$stateIndex",stateIndex,stateIndex/6.0,(stateIndex+1)/6.0,
            duration.copy(median = duration.median/6,lower = duration.lower/6,upper = duration.upper/6,mad = duration.mad/6),features,
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
        states.indices.map { ActionTransitionProfile(it,(it+1)%6,it==5,confidence) },duration,
        states.flatMap { it.features }.groupBy { it.name }.mapValues { (_,features) -> features.maxOf { it.scale } },confidence,
        ActionProfileValidation(withinOwnBands,1.0,confidence,observedCoverage,states.flatMap { it.features }.map { it.reference.outlierFraction }.average()),
        referenceEvidenceKinds = setOf(SpatialEvidenceKind.THREE_D))
    return when (val result = QualifiedRepCompiler.compile(profile,exercise.id,fingerprint,config)) {
        is QualifiedRepCompilation.Unavailable -> result
        is QualifiedRepCompilation.Ready -> {
            val r = result.reference
            val engine = r.newEngine()
            val events = mutableListOf<RepAttemptResult>()
            for (frame in frames.filter { it.timestamp.value >= selected.first().timestamp.value-400 && it.timestamp.value <= selected.last().timestamp.value+200 }) {
                events += engine.evaluate(frame,fingerprint.poseModelSha256,captureContext = fingerprint.declaredView).finalizedAttempts
            }
            val matched = mutableSetOf<Long>()
            val accepted = cycles.count { cycle ->
                val end = frames[cycle.end].timestamp.value
                val event = events.filter { it.attemptId !in matched && abs(it.completedMs-end) <= 300 && it.startedMs < frames[cycle.peak].timestamp.value }
                    .minByOrNull { abs(it.completedMs-end) }
                event?.let { matched += it.attemptId }
                event?.verdict in setOf(RepVerdict.CORRECT,RepVerdict.ACCEPTABLE)
            }
            val reconstructed = accepted.toDouble()/cycles.size
            if (reconstructed < config.minimumReferenceReconstruction) return fail(RepReferenceFailure.LOW_SELF_RECONSTRUCTION,
                "Only $accepted/${cycles.size} selected cycles pass causal self-replay; reference withheld")
            QualifiedRepCompilation.Ready(QualifiedRepReference(r.profile,r.fingerprint,r.config,r.spec,r.driverFeature,r.requiredFeatures,r.readyAngle,r.farAngle,
                r.nodes,r.sourceTargets,r.sourceTolerances,RepReferenceCalibration(cycles.size,rejected,frames.size,selected.size,
                    selected.first().timestamp.value,selected.last().timestamp.value,accepted,reconstructed)))
        }
    }
}
