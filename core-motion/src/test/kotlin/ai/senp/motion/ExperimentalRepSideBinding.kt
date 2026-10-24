package ai.senp.motion

/**
 * Research-only, explicitly interchangeable unilateral shoulder-abduction variant.
 * Never loaded by the app. Driver evidence chooses a mapping before the first
 * turnaround; form scores, verdicts and publisher labels cannot choose a side.
 */
internal class ExperimentalRepSideBinding(private val reference: QualifiedRepReference) {
    companion object { const val VERSION = "causal-unilateral-side-binding/1" }
    enum class State { WAITING, LOCKED, BLOCKED }
    data class Result(val state: State, val side: RepObservedSide?, val lockedMs: Long?,
        val reason: String?, val output: QualifiedRepOutput?)
    private data class Context(val subject: String?, val segment: Int?, val capture: String?)
    private val config = reference.config
    private val opposite = if (config.side == RepObservedSide.LEFT) RepObservedSide.RIGHT else RepObservedSide.LEFT
    private val mirrored = QualifiedRepReference(reference.profile,reference.fingerprint,
        config.copy(mirrorMode = ActionMirrorMode.MIRRORED),reference.spec,reference.driverFeature,
        reference.requiredFeatures,reference.readyAngle,reference.farAngle,reference.nodes,
        reference.sourceTargets,reference.sourceTolerances,reference.calibration,reference.driverEnvelope)
    private val engines = mapOf(config.side to reference.newEngine(),opposite to mirrored.newEngine())
    private var context: Context? = null
    private var previousMs: Long? = null
    private var readySince: Long? = null
    private var readyObservations = 0
    private var armed = false
    private var candidate: RepObservedSide? = null
    private var candidateSince: Long? = null
    private var candidateObservations = 0
    private var selected: RepObservedSide? = null
    private var lockedMs: Long? = null
    private var blocked: String? = null

    init {
        require(reference.exerciseId == "lateral_raise") { "Only the declared interchangeable unilateral research variant is supported" }
        require(config.mirrorMode == ActionMirrorMode.DIRECT)
    }

    fun reset() {
        engines.values.forEach { it.reset() }
        context = null; previousMs = null; readySince = null; readyObservations = 0; armed = false
        candidate = null; candidateSince = null; candidateObservations = 0
        selected = null; lockedMs = null; blocked = null
    }

    fun evaluate(frame: SpatialObservationFrame, model: String?, pipeline: String, capture: String?): Result {
        val now = frame.timestamp.value
        val before = previousMs
        require(before == null || now > before)
        previousMs = now
        // A locked session never changes arms, including after evidence loss,
        // poor form, a new subject or a later movement on the opposite arm.
        selected?.let { return Result(State.LOCKED,it,lockedMs,null,engines.getValue(it).evaluate(frame,model,pipeline,capture)) }
        blocked?.let { return Result(State.BLOCKED,null,null,it,null) }
        fun block(reason: String): Result {
            blocked = reason
            return Result(State.BLOCKED,null,null,reason,null)
        }
        val nextContext = Context(frame.selectedSubjectId,frame.spatialSegmentId,capture)
        if (context != null && context != nextContext) return block("CONTEXT_CHANGED_BEFORE_BINDING")
        context = nextContext
        if (before != null && now-before > config.maximumEvidenceGapMs) return block("EVIDENCE_GAP_BEFORE_BINDING")
        if (model != reference.fingerprint.poseModelSha256) return block("MODEL_MISMATCH")
        if (pipeline != reference.fingerprint.preprocessingId) return block("PREPROCESSING_MISMATCH")
        fun progress(side: RepObservedSide): Double? {
            val name = "angle.${side.name.lowercase()}_${reference.spec.driver}"
            return frame.intrinsicDescriptor.values[name]?.takeIf {
                frame.evidenceKind == SpatialEvidenceKind.THREE_D && !frame.selectedSubjectId.isNullOrBlank() &&
                    it in 0.0..180.0 && (frame.intrinsicDescriptor.featureConfidences[name] ?: 0.0) >= config.minimumConfidence
            }?.let { (it-reference.readyAngle)/(reference.farAngle-reference.readyAngle) }
        }
        val values = RepObservedSide.entries.associateWith(::progress)
        if (values.values.any { it == null }) {
            if (armed) return block("MISSING_BILATERAL_DRIVER_AFTER_READY")
            clearWarmup()
            return Result(State.WAITING,null,null,"MISSING_BILATERAL_DRIVER",null)
        }
        val p = values.mapValues { requireNotNull(it.value) }
        fun ready(value: Double) = value >= -0.35 && value <= config.readyProgress
        if (!armed && !p.values.all(::ready)) {
            clearWarmup()
            return Result(State.WAITING,null,null,"NEED_BILATERAL_READY",null)
        }
        val outputs = engines.mapValues { it.value.evaluate(frame,model,pipeline,capture) }
        if (!armed) {
            if (readySince == null) readySince = now
            readyObservations++
            armed = readyObservations >= 2 && now-requireNotNull(readySince) >= config.readyConfirmationMs
            return Result(State.WAITING,null,null,null,null)
        }
        if (p.values.any { it >= config.requiredProgress }) return block("TURNAROUND_REACHED_BEFORE_BINDING")
        val departing = p.filterValues { it >= config.departureProgress }.keys
        if (departing.isEmpty()) {
            // A candidate reversal/spike cannot be used as stable active-side evidence.
            if (candidate != null) return block("UNSTABLE_DEPARTURE")
            return Result(State.WAITING,null,null,null,null)
        }
        if (departing.size != 1) return block("BILATERAL_DEPARTURE")
        val active = departing.single()
        val inactive = if (active == RepObservedSide.LEFT) RepObservedSide.RIGHT else RepObservedSide.LEFT
        if (!ready(p.getValue(inactive))) return block("OTHER_SIDE_NOT_STATIONARY_AT_READY")
        if (candidate != null && candidate != active) return block("UNSTABLE_DEPARTURE")
        candidate = active
        if (candidateSince == null) candidateSince = now
        candidateObservations++
        if (candidateObservations >= 2 && now-requireNotNull(candidateSince) >= config.milestoneConfirmationMs) {
            selected = active; lockedMs = now
            return Result(State.LOCKED,active,now,null,outputs.getValue(active))
        }
        return Result(State.WAITING,null,null,null,null)
    }

    fun finish(timestampMs: Long): QualifiedRepOutput? {
        require(timestampMs >= (previousMs ?: 0))
        return selected?.let { engines.getValue(it).finish(timestampMs) }
    }

    private fun clearWarmup() {
        engines.values.forEach { it.reset() }
        readySince = null; readyObservations = 0; armed = false
    }
}
