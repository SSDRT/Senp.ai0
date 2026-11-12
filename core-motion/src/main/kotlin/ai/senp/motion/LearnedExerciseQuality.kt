package ai.senp.motion

import kotlin.math.abs
import kotlin.math.exp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class LearnedQualityStatus { COLLECTING, WEAK_POSE, UNSUPPORTED_EXERCISE, MODEL_MISMATCH, OUT_OF_DISTRIBUTION, SCORED }
data class LearnedQualityResult(
    val timestampMs: Long, val exerciseId: String, val status: LearnedQualityStatus,
    val uncalibratedBadFormScore: Double? = null, val needsReview: Boolean = false,
    val validationQualifiedThreshold: Double? = null, val sampleCount: Int = 0,
    val windowStartMs: Long? = null, val windowEndMs: Long? = null,
    val featureCoverage: Double = 0.0, val outlierFraction: Double? = null,
    val releaseApproved: Boolean = false,
)

/** Weak whole-video form labels produce auxiliary diagnostics, never anatomical prescriptions. */
class LearnedExerciseQualityModel private constructor(
    val featureNames: List<String>, val poseModelSha256: String, val releaseApproved: Boolean,
    private val policies: Map<String, Policy>,
) {
    internal data class Policy(val impute: List<Double>, val mean: List<Double>, val scale: List<Double>,
        val weights: List<Double>, val bias: Double, val threshold: Double?)
    val exerciseIds: Set<String> get() = policies.keys
    fun newEngine(exerciseId: String): LearnedExerciseQualityEngine = LearnedExerciseQualityEngine(this,exerciseId,policies[exerciseId])

    companion object {
        val builtIn: LearnedExerciseQualityModel by lazy {
            val input = requireNotNull(LearnedExerciseQualityModel::class.java.getResourceAsStream("/ai/senp/motion/learned-exercise-quality.json"))
            val root = input.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            require(root.getValue("schema").jsonPrimitive.content == "learned-exercise-quality/1")
            require(root.getValue("feature_schema").jsonPrimitive.content == "mp33-world-technique-features/1")
            require(root.getValue("window_ms").jsonPrimitive.content == "2400")
            require(root.getValue("minimum_span_ms").jsonPrimitive.content == "2000")
            require(root.getValue("sample_period_ms").jsonPrimitive.content == "500")
            for ((key,expected) in mapOf("maximum_gap_ms" to 750.0,"minimum_samples" to 4.0,
                "minimum_feature_samples" to 3.0,"minimum_feature_confidence" to 0.65,
                "minimum_feature_coverage" to 0.60,"maximum_outlier_fraction" to 0.10,"outlier_z" to 6.0)) {
                require(root.getValue(key).jsonPrimitive.double == expected) { "Unsupported learned-quality policy: $key" }
            }
            require(root.getValue("pose_model_sha256").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
            val names = root.getValue("feature_names").jsonArray.map { it.jsonPrimitive.content }
            require(names == ExerciseTechniqueCatalog.coachableFeatures.sorted())
            val policies = root.getValue("exercises").jsonArray.associate { element -> element.jsonObject.run {
                fun vector(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.double }.also { require(it.size == 100 && it.all(Double::isFinite)) }
                val impute = vector("impute"); val mean = vector("mean"); val scale = vector("scale"); val weights = vector("weights")
                val threshold = get("review_threshold")?.takeUnless { it is JsonNull }?.jsonPrimitive?.double
                val bias = getValue("bias").jsonPrimitive.double
                require(scale.all { it > 0.0 } && bias.isFinite())
                require(threshold == null || threshold.isFinite() && threshold in 0.5..1.0)
                getValue("exercise_id").jsonPrimitive.content to Policy(impute,mean,scale,weights,bias,threshold)
            } }
            require(policies.isNotEmpty())
            LearnedExerciseQualityModel(names,root.getValue("pose_model_sha256").jsonPrimitive.content,
                root.getValue("release_approved").jsonPrimitive.boolean,policies)
        }
    }
}

/** Causal 2.4-second rolling window, sampled at 500 ms, bounded to six observations. */
class LearnedExerciseQualityEngine internal constructor(
    private val model: LearnedExerciseQualityModel,
    private val exerciseId: String,
    private val policy: LearnedExerciseQualityModel.Policy?,
) {
    private data class Sample(val time: Long, val values: List<Double?>)
    private data class Context(val subject: String?, val evidence: SpatialEvidenceKind, val segment: Int?)
    private val samples = mutableListOf<Sample>()
    private var previousMs: Long? = null
    private var previousSampleMs: Long? = null
    private var context: Context? = null
    private var lastResult: LearnedQualityResult? = null

    @Synchronized fun reset() { samples.clear(); previousMs = null; previousSampleMs = null; context = null; lastResult = null }
    @Synchronized fun evaluate(frame: SpatialObservationFrame, poseModelSha256: String?): LearnedQualityResult {
        val now = frame.timestamp.value
        val before = previousMs
        require(before == null || now > before) { "quality timestamps must strictly increase" }
        previousMs = now
        val nextContext = Context(frame.selectedSubjectId,frame.evidenceKind,frame.spatialSegmentId)
        if (context != nextContext || before != null && now-before > 750) clearWindow()
        context = nextContext
        fun empty(status: LearnedQualityStatus): LearnedQualityResult {
            clearWindow()
            return LearnedQualityResult(now,exerciseId,status,releaseApproved = model.releaseApproved)
        }
        val activePolicy = policy ?: return empty(LearnedQualityStatus.UNSUPPORTED_EXERCISE)
        if (poseModelSha256 != model.poseModelSha256) return empty(LearnedQualityStatus.MODEL_MISMATCH)
        if (frame.evidenceKind != SpatialEvidenceKind.THREE_D) return empty(LearnedQualityStatus.WEAK_POSE)
        val descriptor = frame.intrinsicDescriptor
        val values = model.featureNames.map { name ->
            descriptor.values[name]?.takeIf { (descriptor.featureConfidences[name] ?: 0.0) >= 0.65 }
        }
        if (values.all { it == null }) return empty(LearnedQualityStatus.WEAK_POSE)
        val lastSample = previousSampleMs
        if (lastSample != null && now-lastSample < 500) {
            return lastResult?.copy(timestampMs = now) ?: LearnedQualityResult(now,exerciseId,LearnedQualityStatus.COLLECTING,sampleCount = samples.size)
        }
        previousSampleMs = now
        samples += Sample(now,values)
        while (samples.isNotEmpty() && now-samples.first().time > 2400) samples.removeAt(0)
        check(samples.size <= 6)
        if (samples.size < 4 || now-samples.first().time < 2000) {
            return LearnedQualityResult(now,exerciseId,LearnedQualityStatus.COLLECTING,sampleCount = samples.size,
                windowStartMs = samples.firstOrNull()?.time,windowEndMs = now)
        }
        val aggregate = mutableListOf<Double?>()
        var reliable = 0
        for (index in model.featureNames.indices) {
            val present = samples.mapNotNull { it.values[index] }
            if (present.size >= 3) {
                aggregate += listOf(quantile(present,0.1),quantile(present,0.5),quantile(present,0.9)); reliable++
            } else aggregate += listOf(null,null,null)
            aggregate += present.size.toDouble()/samples.size
        }
        val coverage = reliable.toDouble()/model.featureNames.size
        if (coverage < 0.60) {
            return LearnedQualityResult(now,exerciseId,LearnedQualityStatus.WEAK_POSE,sampleCount = samples.size,
                windowStartMs = samples.first().time,windowEndMs = now,featureCoverage = coverage).also { lastResult = it }
        }
        val normalized = aggregate.indices.map { ((aggregate[it] ?: activePolicy.impute[it])-activePolicy.mean[it])/activePolicy.scale[it] }
        val outliers = normalized.count { abs(it) > 6.0 }.toDouble()/normalized.size
        if (outliers > 0.10) {
            return LearnedQualityResult(now,exerciseId,LearnedQualityStatus.OUT_OF_DISTRIBUTION,sampleCount = samples.size,
                windowStartMs = samples.first().time,windowEndMs = now,featureCoverage = coverage,outlierFraction = outliers).also { lastResult = it }
        }
        val logit = (normalized.indices.sumOf { normalized[it]*activePolicy.weights[it] }+activePolicy.bias).coerceIn(-700.0,700.0)
        val score = 1.0/(1.0+exp(-logit))
        return LearnedQualityResult(now,exerciseId,LearnedQualityStatus.SCORED,score,
            model.releaseApproved && (activePolicy.threshold?.let { score >= it } ?: false),activePolicy.threshold,samples.size,
            samples.first().time,now,coverage,outliers,model.releaseApproved).also { lastResult = it }
    }
    private fun clearWindow() { samples.clear(); previousSampleMs = null; lastResult = null }
}
