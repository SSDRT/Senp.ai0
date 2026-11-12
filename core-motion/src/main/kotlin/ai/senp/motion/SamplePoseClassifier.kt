package ai.senp.motion

import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.FrameValidityStatus
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double

enum class SamplePoseStatus { MATCHED, WEAK_POSE, INVALID_INPUT, OUT_OF_DISTRIBUTION, AMBIGUOUS }
data class SamplePoseClassification(
    val status: SamplePoseStatus, val exerciseId: String? = null, val state: String? = null,
    val nearestLabel: String? = null, val normalizedDistance: Double? = null, val margin: Double? = null,
    val modelVersion: String = "sample-pose-classifier/1",
)

/** A fitted auxiliary exercise/state classifier. Scores are distances, never form probabilities.
 * Pixel X/Y and image-Z must share width-based units; world landmarks are a different feature space.
 */
class SamplePoseClassifier private constructor(
    private val mean: List<Double>, private val scale: List<Double>, private val classes: List<PrototypeClass>,
    private val maximumDistance: Double, private val minimumMargin: Double,
) {
    private data class PrototypeClass(val label: String, val exerciseId: String, val state: String, val radius: Double, val centers: List<List<Double>>)
    fun classify(pose: PoseFrame, imageWidth: Int, imageHeight: Int, minimumConfidence: Double = 0.65): SamplePoseClassification {
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0)
        if (imageWidth <= 0 || imageHeight <= 0) return SamplePoseClassification(SamplePoseStatus.INVALID_INPUT)
        if (pose.validity.status != FrameValidityStatus.VALID || pose.validity.confidence < minimumConfidence)
            return SamplePoseClassification(SamplePoseStatus.WEAK_POSE)
        val indexed = pose.landmarks.associateBy { it.id.index }
        if (requiredIndices.any { index -> indexed[index]?.let { minOf(it.visibility ?: 0.0, it.presence ?: 0.0) < minimumConfidence } != false })
            return SamplePoseClassification(SamplePoseStatus.WEAK_POSE)
        val aspect = imageHeight.toDouble() / imageWidth
        val points = (0..32).map { index -> indexed[index]?.let { listOf(it.image.x, it.image.y * aspect, it.image.z) } ?: listOf(0.0, 0.0, 0.0) }
        return features(points)?.let(::classifyFeatures) ?: SamplePoseClassification(SamplePoseStatus.INVALID_INPUT)
    }

    internal fun classifyFeatures(features: List<Double>): SamplePoseClassification {
        if (features.size != 20 || features.any { !it.isFinite() }) return SamplePoseClassification(SamplePoseStatus.INVALID_INPUT)
        val normalized = features.indices.map { ((features[it] - mean[it]) / scale[it]).coerceIn(-8.0, 8.0) }
        val ranked = classes.map { cls ->
            cls to sqrt(cls.centers.minOf { center -> normalized.indices.sumOf { i -> (normalized[i] - center[i]).let { it * it } } / normalized.size })
        }.sortedWith(compareBy({ it.second }, { it.first.label }))
        val (best, distance) = ranked.first()
        val normalizedDistance = distance / best.radius
        val margin = (ranked[1].second - distance) / max(ranked[1].second, 1e-8)
        val status = when {
            normalizedDistance > maximumDistance -> SamplePoseStatus.OUT_OF_DISTRIBUTION
            margin < minimumMargin -> SamplePoseStatus.AMBIGUOUS
            else -> SamplePoseStatus.MATCHED
        }
        return SamplePoseClassification(status, best.exerciseId.takeIf { status == SamplePoseStatus.MATCHED },
            best.state.takeIf { status == SamplePoseStatus.MATCHED }, best.label, normalizedDistance, margin)
    }

    companion object {
        private val triplets = listOf(Triple(13,11,23), Triple(14,12,24), Triple(11,13,15), Triple(12,14,16),
            Triple(11,23,25), Triple(12,24,26), Triple(23,25,27), Triple(24,26,28), Triple(25,27,31), Triple(26,28,32), Triple(11,23,27), Triple(12,24,28))
        private val pairs = listOf(15 to 16, 27 to 28, 25 to 26, 13 to 14, 15 to 11, 16 to 12, 15 to 23, 16 to 24)
        private val requiredIndices = (triplets.flatMap { listOf(it.first, it.second, it.third) } + pairs.flatMap { listOf(it.first,it.second) }).toSet()
        internal fun features(points: List<List<Double>>): List<Double>? {
            if (points.size != 33 || points.any { it.size != 3 || it.any { v -> !v.isFinite() } }) return null
            fun minus(a: List<Double>, b: List<Double>) = a.zip(b) { x,y -> x-y }
            fun norm(a: List<Double>) = sqrt(a.sumOf { it * it })
            val angles = mutableListOf<Double>()
            for ((a,b,c) in triplets) {
                val u = minus(points[a],points[b]); val v = minus(points[c],points[b]); val denominator = norm(u) * norm(v)
                if (denominator <= 1e-8) return null
                angles += acos((u.zip(v) { x,y -> x*y }.sum() / denominator).coerceIn(-1.0,1.0)) / Math.PI
            }
            val shoulder = points[11].zip(points[12]) { x,y -> (x+y)/2 }
            val hip = points[23].zip(points[24]) { x,y -> (x+y)/2 }
            val torso = norm(minus(shoulder,hip))
            if (torso <= 1e-8) return null
            return angles + pairs.map { (a,b) -> norm(minus(points[a],points[b])) / torso }
        }
        val builtIn: SamplePoseClassifier by lazy {
            val input = requireNotNull(SamplePoseClassifier::class.java.getResourceAsStream("/ai/senp/motion/sample-pose-classifier.json"))
            val root = input.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            require(root.getValue("schema").jsonPrimitive.content == "sample-pose-classifier/1")
            require(root.getValue("feature_schema").jsonPrimitive.content == "mp33-image-3d-angles-distances/1")
            fun vector(value: kotlinx.serialization.json.JsonElement) = value.jsonArray.map { it.jsonPrimitive.double }.also { require(it.size == 20 && it.all(Double::isFinite)) }
            val mean = vector(root.getValue("mean")); val scale = vector(root.getValue("scale")); require(scale.all { it > 0 })
            val classes = root.getValue("classes").jsonArray.map { element -> element.jsonObject.run {
                PrototypeClass(getValue("label").jsonPrimitive.content, getValue("exercise_id").jsonPrimitive.content,
                    getValue("state").jsonPrimitive.content, getValue("radius").jsonPrimitive.double,
                    getValue("centers").jsonArray.map(::vector))
            } }
            require(classes.size >= 2 && classes.all { it.radius.isFinite() && it.radius > 0 && it.centers.isNotEmpty() })
            SamplePoseClassifier(mean,scale,classes,root.getValue("maximum_normalized_distance").jsonPrimitive.double,
                root.getValue("minimum_class_margin").jsonPrimitive.double)
        }
    }
}
