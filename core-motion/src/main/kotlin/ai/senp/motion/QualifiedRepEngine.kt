package ai.senp.motion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Causal visible-side reference matching. Does not use the legacy repetition count or learned form scores. */
class QualifiedRepEngine internal constructor(private val reference: QualifiedRepReference,
    private val evidenceObserver: ((QualifiedRepFrameEvidence) -> Unit)? = null) {
    private val config = reference.config
    private data class Context(val subject: String?, val segment: Int?, val capture: String?)
    private data class Confirmation(val since: Long, var observations: Int = 1)
    private data class PendingFault(val confirmation: Confirmation, val ruleKey: String)
    private class Attempt(val id: Long, val start: Long, progress: Double, priorProgress: Double, visible: Boolean) {
        var lastMs = start
        var previousVisible = visible
        var previousAmbiguous = false
        var elapsedMs = 0L
        var visibleMs = 0L
        var ambiguousMs = 0L
        var maximumProgress = min(priorProgress,progress)
        var previousProgress = priorProgress
        var returning = false
        var reversal: Confirmation? = null
        var far: Confirmation? = null
        var farObserved = false
        var returned: Confirmation? = null
        var minorDeviation = false
        var outboundPathObserved = false
        var returnPathObserved = false
        val faults = linkedMapOf<String,RepFault>()
        val pending = mutableMapOf<String,PendingFault>()
    }
    private var context: Context? = null
    private var previousMs: Long? = null
    private var previousObservedProgress: Double? = null
    private var ready: Confirmation? = null
    private val readyFaults = mutableMapOf<String,PendingFault>()
    private var armed = false
    private var primedOutboundPathObserved = false
    private var nextId = 1L
    private var active: Attempt? = null
    private var counts = QualifiedRepCounts()

    @Synchronized fun reset() {
        context = null; previousMs = null; previousObservedProgress = null; ready = null; armed = false; primedOutboundPathObserved = false; nextId = 1; active = null; counts = QualifiedRepCounts()
        readyFaults.clear()
    }

    @Synchronized fun evaluate(frame: SpatialObservationFrame, poseModelSha256: String?,
        preprocessingId: String = RepEvidenceFingerprint.RAW_WORLD_PIPELINE, captureContext: String? = null): QualifiedRepOutput {
        val now = frame.timestamp.value
        val before = previousMs
        require(before == null || now > before) { "qualified-rep timestamps must strictly increase" }
        previousMs = now
        val finalized = mutableListOf<RepAttemptResult>()
        val newFaults = mutableListOf<RepFault>()
        fun close(reason: RepReason) {
            active?.let { finalized += finalize(it,now,reason) }
            active = null; ready = null; armed = false; primedOutboundPathObserved = false; previousObservedProgress = null
            readyFaults.clear()
        }
        val nextContext = Context(frame.selectedSubjectId,frame.spatialSegmentId,captureContext)
        if (context != null && context != nextContext) close(RepReason.CONTEXT_CHANGED)
        if (before != null && now-before > config.maximumEvidenceGapMs) close(RepReason.EVIDENCE_GAP)
        context = nextContext
        fun unavailable(reason: RepReason): QualifiedRepOutput {
            close(reason)
            return output(now,RepStage.UNAVAILABLE,finalized = finalized,unavailable = reason)
        }
        if (poseModelSha256 != reference.fingerprint.poseModelSha256) return unavailable(RepReason.MODEL_MISMATCH)
        if (preprocessingId != reference.fingerprint.preprocessingId) return unavailable(RepReason.PREPROCESSING_MISMATCH)
        if (frame.evidenceKind != SpatialEvidenceKind.THREE_D || frame.selectedSubjectId.isNullOrBlank()) return unavailable(RepReason.MISSING_CRITICAL_EVIDENCE)
        fun value(name: String): Double? {
            val key = config.side.observedFeature(name,config.mirrorMode)
            return frame.intrinsicDescriptor.values[key]?.takeIf {
                it in 0.0..180.0 && (frame.intrinsicDescriptor.featureConfidences[key] ?: 0.0) >= config.minimumConfidence
            }
        }
        val driver = value(reference.driverFeature) ?: return unavailable(RepReason.MISSING_CRITICAL_EVIDENCE)
        val progress = (driver-reference.readyAngle)/(reference.farAngle-reference.readyAngle)
        val visible = reference.requiredFeatures.all { value(it) != null }
        fun compatible(returning: Boolean): Boolean = reference.requiredFeatures.all { feature ->
            val observed = value(feature) ?: return@all false
            val band = reference.evidenceBand(feature,progress,returning)
            observed >= band.lower-band.acceptable && observed <= band.upper+band.acceptable
        }
        var attempt = active
        if (attempt != null && now-attempt.start > config.maximumAttemptMs) {
            close(RepReason.ATTEMPT_TIMEOUT); attempt = null
        }
        val priorProgress = previousObservedProgress
        previousObservedProgress = progress
        if (attempt == null) {
            // Only directly observed, persistent CLEAR disagreement can revoke ready dwell.
            // Grey/transient preparation keeps its existing semantics. Match the demonstrated
            // start here; absolute technique targets retain their active-attempt fault checks.
            val atReady = progress <= config.readyProgress && progress >= -0.35 && visible
            var clearReadyFault = false
            // Ready dwell remains borrowed until actual departure. A persistent
            // clear discrepancy in that armed interval must revoke it as well.
            val preparing = armed && visible && progress > config.readyProgress && progress < config.departureProgress
            if (atReady || preparing) {
                for (feature in reference.requiredFeatures) {
                    val observed = requireNotNull(value(feature))
                    val band = reference.evidenceBand(feature,progress,false,includeTechniqueTargets = false)
                    val distance = maxOf(band.lower-observed,observed-band.upper,0.0)
                    if (distance > band.reject) {
                        val ruleKey = "${band.ruleId}|${if (observed < band.lower) -1 else 1}"
                        val old = readyFaults[feature]?.takeIf { it.ruleKey == ruleKey }
                        val pending = PendingFault(confirm(old?.confirmation,now),ruleKey)
                        readyFaults[feature] = pending
                        if (confirmed(pending.confirmation,now,config.faultConfirmationMs)) clearReadyFault = true
                    } else readyFaults.remove(feature)
                }
            } else readyFaults.clear()
            // An observed start endpoint is mandatory; recovery in the middle of a rep cannot create a count.
            if (atReady && !clearReadyFault) {
                ready = confirm(ready,now)
                if (confirmed(ready,now,config.readyConfirmationMs)) armed = true
            } else if (!armed) ready = null
            if (progress <= config.readyProgress) primedOutboundPathObserved = false
            if (!visible || clearReadyFault) { armed = false; ready = null; primedOutboundPathObserved = false }
            // Early visible motion after arming still belongs to this path even before the
            // departure threshold starts the attempt. Returning to ready discards that evidence.
            if (armed && visible && progress > config.readyProgress && progress < config.requiredProgress && compatible(false)) primedOutboundPathObserved = true
            if (armed && visible && progress >= config.departureProgress) {
                // Arming guarantees an earlier compatible observation. The departure frame
                // needs the same adjacent peak support as every subsequent frame.
                attempt = Attempt(nextId++,now,progress,requireNotNull(priorProgress),true).also { it.outboundPathObserved = primedOutboundPathObserved }
                active = attempt; armed = false; ready = null; primedOutboundPathObserved = false
                readyFaults.clear()
            } else {
                return output(now,if (armed) RepStage.READY else RepStage.SYNCHRONIZING,progress,finalized = finalized)
            }
        }
        val current = requireNotNull(attempt)
        // Require adjacent observations for the peak used by reversal logic; one spike cannot invent a turnaround.
        current.maximumProgress = max(current.maximumProgress,min(current.previousProgress,progress))
        current.previousProgress = progress
        val reversal = current.maximumProgress-progress >= config.reversalExcursion
        // Endpoint dwell alone cannot establish that the movement between endpoints was observed.
        // One directly observed, compatible intermediate pose in each direction avoids requiring a dense
        // sampling grid. It must precede the far endpoint outbound, and follow an apparent
        // reversal returning. No interpolation or speed/biomechanical label is inferred here.
        val intermediate = progress > config.readyProgress && progress < config.requiredProgress
        if (!current.farObserved && progress <= config.readyProgress) current.outboundPathObserved = false
        if (!current.farObserved || progress >= config.requiredProgress) current.returnPathObserved = false
        if (!current.returning && !reversal) current.returnPathObserved = false
        if (!current.returning) {
            current.reversal = if (reversal) confirm(current.reversal,now) else null
            if (confirmed(current.reversal,now,config.reversalConfirmationMs)) {
                current.returning = true
                if (current.maximumProgress >= config.departureProgress && current.maximumProgress < config.clearlyIncompleteProgress) {
                    latch(current,"range",RepFault(RepReason.EARLY_REVERSAL,config.side.observedFeature(reference.driverFeature,config.mirrorMode),
                        requireNotNull(current.reversal).since,now),newFaults)
                }
            }
        }
        var ambiguous = false
        var endpointAcceptable = visible
        // Before reversal dwell is confirmed, either branch is causally possible. A
        // directly observed, fully compatible return pose after the far endpoint may
        // use the return curve immediately. This does not confirm reversal, endpoints
        // or a count, and never combines different branches feature by feature.
        val evidenceReturning = current.returning || (current.farObserved && progress < current.maximumProgress && compatible(true))
        val evidence = if (evidenceObserver != null) mutableListOf<QualifiedRepFeatureEvidence>() else null
        val pendingKeys = mutableSetOf<String>()
        for (feature in reference.requiredFeatures) {
            val observed = value(feature)
            if (observed == null && evidence == null) { current.pending.remove(feature); continue }
            val band = reference.evidenceBand(feature,progress,evidenceReturning)
            val distance = observed?.let { when { it < band.lower -> band.lower-it; it > band.upper -> it-band.upper; else -> 0.0 } }
            if (evidence != null) {
                val sourceFeature = config.side.observedFeature(feature,config.mirrorMode)
                evidence += QualifiedRepFeatureEvidence(feature,sourceFeature,frame.intrinsicDescriptor.values[sourceFeature],
                    frame.intrinsicDescriptor.featureConfidences[sourceFeature] ?: 0.0,observed,
                    band.lower,band.upper,band.nominal,band.acceptable,band.reject,distance,band.ruleId)
            }
            if (observed == null) { current.pending.remove(feature); continue }
            requireNotNull(distance)
            if (distance > band.nominal) current.minorDeviation = true
            if (distance > band.acceptable) { ambiguous = true; endpointAcceptable = false }
            val ruleKey = "${band.ruleId}|${if (observed < band.lower) -1 else 1}"
            if (distance > band.reject) {
                pendingKeys += feature
                val old = current.pending[feature]?.takeIf { it.ruleKey == ruleKey }
                val pending = PendingFault(confirm(old?.confirmation,now),ruleKey)
                current.pending[feature] = pending
                if (confirmed(pending.confirmation,now,config.faultConfirmationMs)) {
                    latch(current,feature,RepFault(RepReason.CLEAR_FORM_DEVIATION,
                        config.side.observedFeature(feature,config.mirrorMode),pending.confirmation.since,now,distance,band.ruleId),newFaults)
                }
            } else current.pending.remove(feature)
        }
        evidenceObserver?.invoke(QualifiedRepFrameEvidence(now,reference.referenceKey,current.id,progress,
            evidenceReturning,current.returning,current.farObserved,requireNotNull(evidence).toList()))
        current.pending.keys.filter { it !in pendingKeys }.forEach(current.pending::remove)
        // A visible but incompatible pose cannot be the sole evidence of the observed
        // path. Brief faults still keep their original dwell; lack of a compatible
        // intermediate observation makes completion uncertain instead of inventing a rep.
        if (endpointAcceptable && intermediate && !current.farObserved && !current.returning && !reversal) current.outboundPathObserved = true
        if (endpointAcceptable && intermediate && current.farObserved && reversal) current.returnPathObserved = true
        val elapsed = now-current.lastMs
        current.elapsedMs += elapsed
        if (visible && current.previousVisible) current.visibleMs += elapsed
        if (ambiguous || current.previousAmbiguous) current.ambiguousMs += elapsed
        current.previousVisible = visible; current.previousAmbiguous = ambiguous; current.lastMs = now
        // Endpoints require direct, acceptable evidence on two distinct observations, not gap repair or state skips.
        if (!current.farObserved) {
            current.far = if (progress >= config.requiredProgress && endpointAcceptable) confirm(current.far,now) else null
            if (confirmed(current.far,now,config.milestoneConfirmationMs)) current.farObserved = true
        }
        current.returned = if (current.returning && progress <= config.readyProgress && visible) confirm(current.returned,now) else null
        if (confirmed(current.returned,now,config.readyConfirmationMs)) {
            val reason = when {
                !endpointAcceptable -> RepReason.AMBIGUOUS_FORM
                !current.farObserved -> if (current.maximumProgress >= config.departureProgress && current.maximumProgress < config.clearlyIncompleteProgress)
                    RepReason.EARLY_REVERSAL else RepReason.INSUFFICIENT_RANGE
                config.requireObservedPath && (!current.outboundPathObserved || !current.returnPathObserved) -> RepReason.INSUFFICIENT_PATH_EVIDENCE
                else -> null
            }
            finalized += finalize(current,now,reason)
            active = null
            primedOutboundPathObserved = false
            armed = endpointAcceptable; ready = if (armed) current.returned else null
            return output(now,if (armed) RepStage.READY else RepStage.SYNCHRONIZING,progress,newFaults,finalized)
        }
        return output(now,if (current.returning) RepStage.RETURNING else RepStage.OUTBOUND,progress,newFaults,finalized)
    }

    /** End a recording/session without converting a partial cycle into an accepted rep. */
    @Synchronized fun finish(timestampMs: Long): QualifiedRepOutput {
        require(timestampMs >= (previousMs ?: 0))
        val events = active?.let { listOf(finalize(it,timestampMs,RepReason.INTERRUPTED)) }.orEmpty()
        active = null; ready = null; armed = false; primedOutboundPathObserved = false; previousObservedProgress = null; previousMs = timestampMs
        readyFaults.clear()
        return output(timestampMs,RepStage.SYNCHRONIZING,finalized = events)
    }

    private fun confirm(old: Confirmation?, now: Long): Confirmation = old?.also { it.observations++ } ?: Confirmation(now)
    private fun confirmed(confirmation: Confirmation?, now: Long, duration: Long): Boolean =
        confirmation != null && confirmation.observations >= 2 && now-confirmation.since >= duration

    private fun latch(attempt: Attempt, key: String, fault: RepFault, events: MutableList<RepFault>) {
        if (key !in attempt.faults) { attempt.faults[key] = fault; events += fault }
    }
    private fun finalize(attempt: Attempt, now: Long, reason: RepReason?): RepAttemptResult {
        val visible = if (attempt.elapsedMs > 0) attempt.visibleMs.toDouble()/attempt.elapsedMs else 0.0
        val ambiguous = if (attempt.elapsedMs > 0) attempt.ambiguousMs.toDouble()/attempt.elapsedMs else 0.0
        val reasons = attempt.faults.values.map { it.reason }.toMutableSet()
        reason?.let(reasons::add)
        if (visible < config.minimumVisibleFraction) reasons += RepReason.INSUFFICIENT_COVERAGE
        if (ambiguous > config.maximumAmbiguousFraction) reasons += RepReason.AMBIGUOUS_FORM
        val verdict = when {
            attempt.faults.isNotEmpty() -> RepVerdict.REJECTED
            reasons.isNotEmpty() -> RepVerdict.UNASSESSABLE
            attempt.minorDeviation || attempt.maximumProgress < config.nominalProgress -> RepVerdict.ACCEPTABLE
            else -> RepVerdict.CORRECT
        }
        counts = when (verdict) {
            RepVerdict.CORRECT -> counts.copy(correct = counts.correct+1)
            RepVerdict.ACCEPTABLE -> counts.copy(acceptable = counts.acceptable+1)
            RepVerdict.REJECTED -> counts.copy(rejected = counts.rejected+1)
            RepVerdict.UNASSESSABLE -> counts.copy(unassessable = counts.unassessable+1)
        }
        return RepAttemptResult(attempt.id,attempt.start,now,verdict,reasons.toSet(),attempt.faults.values.toList(),attempt.maximumProgress,visible,ambiguous,
            attempt.outboundPathObserved,attempt.returnPathObserved)
    }
    private fun output(now: Long, stage: RepStage, progress: Double? = null, faults: List<RepFault> = emptyList(),
        finalized: List<RepAttemptResult> = emptyList(), unavailable: RepReason? = null): QualifiedRepOutput =
        QualifiedRepOutput(now,reference.referenceKey,reference.exerciseId,stage,counts,active?.id,progress,
            active?.faults?.isNotEmpty() == true,faults.toList(),finalized.toList(),unavailable,
            observedSide = if (config.mirrorMode == ActionMirrorMode.MIRRORED) {
                if (config.side == RepObservedSide.LEFT) RepObservedSide.RIGHT else RepObservedSide.LEFT
            } else config.side,policyMode = config.mode)
}
