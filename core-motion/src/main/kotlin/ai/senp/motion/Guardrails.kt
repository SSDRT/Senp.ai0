package ai.senp.motion

import kotlin.math.max
import kotlin.math.min

internal data class GuardrailConfig(
    val minimumSwapPairs: Int = 4,
    val swapCostRatio: Double = 0.55,
    val minimumSwapImprovement: Double = 0.03,
    val minimumSegmentToTorsoRatio: Double = 0.10,
    val maximumSegmentToTorsoRatio: Double = 2.80,
    val maximumLeftRightSegmentRatio: Double = 3.50,
    val minScale: Double = 1e-6,
) {
    init {
        require(minimumSwapPairs >= 2)
        require(swapCostRatio.isFinite() && swapCostRatio in 0.0..1.0)
        require(minimumSwapImprovement.isFinite() && minimumSwapImprovement >= 0.0)
        require(minimumSegmentToTorsoRatio > 0.0)
        require(maximumSegmentToTorsoRatio > minimumSegmentToTorsoRatio)
        require(maximumLeftRightSegmentRatio >= 1.0)
        require(minScale > 0.0)
    }
}

internal data class GuardrailInspection(
    val frame: PoseFrame,
    val flags: GuardrailFlags,
    val originalContinuityCost: Double? = null,
    val swappedContinuityCost: Double? = null,
    val comparedPairs: Int = 0,
)

/** Conservative label/proportion checks. No bone length is ever rewritten. */
internal class PoseGuardrails(private val config: GuardrailConfig = GuardrailConfig()) {
    fun inspect(frame: PoseFrame, previous: PoseFrame? = null): GuardrailInspection {
        val comparison = previous?.let { compareContinuity(it, frame) }
        val shouldSwap = comparison != null &&
            comparison.pairCount >= config.minimumSwapPairs &&
            comparison.swappedCost <= comparison.originalCost * config.swapCostRatio &&
            comparison.originalCost - comparison.swappedCost >= config.minimumSwapImprovement
        val corrected = if (shouldSwap) swapSides(frame) else frame
        return GuardrailInspection(
            frame = corrected,
            flags = GuardrailFlags(
                leftRightSwapApplied = shouldSwap,
                impossibleProportions = hasImpossibleProportions(corrected),
            ),
            originalContinuityCost = comparison?.originalCost,
            swappedContinuityCost = comparison?.swappedCost,
            comparedPairs = comparison?.pairCount ?: 0,
        )
    }

    private data class ContinuityComparison(
        val originalCost: Double,
        val swappedCost: Double,
        val pairCount: Int,
    )

    private fun compareContinuity(previous: PoseFrame, current: PoseFrame): ContinuityComparison? {
        var original = 0.0
        var swapped = 0.0
        var count = 0
        for ((left, right) in MAJOR_SIDE_PAIRS) {
            val previousLeft = previous[left].image
            val previousRight = previous[right].image
            val currentLeft = current[left].image
            val currentRight = current[right].image
            if (listOf(previousLeft, previousRight, currentLeft, currentRight).any { it?.finite() != true }) continue
            original += (currentLeft!! - previousLeft!!).norm() + (currentRight!! - previousRight!!).norm()
            swapped += (currentRight - previousLeft).norm() + (currentLeft - previousRight).norm()
            count += 1
        }
        if (count == 0) return null
        return ContinuityComparison(original / (2.0 * count), swapped / (2.0 * count), count)
    }

    private fun swapSides(frame: PoseFrame): PoseFrame {
        val landmarks = frame.landmarks.toMutableList()
        for ((left, right) in LandmarkId.SIDE_PAIRS) {
            val leftValue = landmarks[left.index]
            landmarks[left.index] = landmarks[right.index]
            landmarks[right.index] = leftValue
        }
        return frame.copy(landmarks = landmarks)
    }

    private fun hasImpossibleProportions(frame: PoseFrame): Boolean {
        val shoulderCenter = center(frame, LandmarkId.LEFT_SHOULDER, LandmarkId.RIGHT_SHOULDER)
        val hipCenter = center(frame, LandmarkId.LEFT_HIP, LandmarkId.RIGHT_HIP)
        val torso = if (shoulderCenter != null && hipCenter != null) {
            (shoulderCenter - hipCenter).norm()
        } else {
            listOfNotNull(
                distance(frame, LandmarkId.LEFT_SHOULDER, LandmarkId.LEFT_HIP),
                distance(frame, LandmarkId.RIGHT_SHOULDER, LandmarkId.RIGHT_HIP),
            ).averageOrNull() ?: return false
        }
        if (!torso.isFinite() || torso < config.minScale) return true

        for ((a, b) in LIMB_SEGMENTS) {
            val length = distance(frame, a, b) ?: continue
            val ratio = length / torso
            if (ratio !in config.minimumSegmentToTorsoRatio..config.maximumSegmentToTorsoRatio) return true
        }

        for ((leftSegment, rightSegment) in HOMOLOGOUS_SEGMENTS) {
            val leftLength = distance(frame, leftSegment.first, leftSegment.second) ?: continue
            val rightLength = distance(frame, rightSegment.first, rightSegment.second) ?: continue
            if (min(leftLength, rightLength) < config.minScale) return true
            if (max(leftLength, rightLength) / min(leftLength, rightLength) > config.maximumLeftRightSegmentRatio) return true
        }
        return false
    }

    private fun center(frame: PoseFrame, first: LandmarkId, second: LandmarkId): Vec3? {
        val a = frame[first].image?.takeIf { it.finite() } ?: return null
        val b = frame[second].image?.takeIf { it.finite() } ?: return null
        return (a + b) / 2.0
    }

    private fun distance(frame: PoseFrame, first: LandmarkId, second: LandmarkId): Double? {
        val a = frame[first].image?.takeIf { it.finite() } ?: return null
        val b = frame[second].image?.takeIf { it.finite() } ?: return null
        return (a - b).norm()
    }

    private fun Iterable<Double>.averageOrNull(): Double? {
        var total = 0.0
        var count = 0
        for (value in this) {
            total += value
            count += 1
        }
        return if (count == 0) null else total / count
    }

    companion object {
        private val MAJOR_SIDE_PAIRS = listOf(
            LandmarkId.LEFT_SHOULDER to LandmarkId.RIGHT_SHOULDER,
            LandmarkId.LEFT_ELBOW to LandmarkId.RIGHT_ELBOW,
            LandmarkId.LEFT_WRIST to LandmarkId.RIGHT_WRIST,
            LandmarkId.LEFT_HIP to LandmarkId.RIGHT_HIP,
            LandmarkId.LEFT_KNEE to LandmarkId.RIGHT_KNEE,
            LandmarkId.LEFT_ANKLE to LandmarkId.RIGHT_ANKLE,
        )

        private val LIMB_SEGMENTS = listOf(
            LandmarkId.LEFT_SHOULDER to LandmarkId.LEFT_ELBOW,
            LandmarkId.LEFT_ELBOW to LandmarkId.LEFT_WRIST,
            LandmarkId.RIGHT_SHOULDER to LandmarkId.RIGHT_ELBOW,
            LandmarkId.RIGHT_ELBOW to LandmarkId.RIGHT_WRIST,
            LandmarkId.LEFT_HIP to LandmarkId.LEFT_KNEE,
            LandmarkId.LEFT_KNEE to LandmarkId.LEFT_ANKLE,
            LandmarkId.RIGHT_HIP to LandmarkId.RIGHT_KNEE,
            LandmarkId.RIGHT_KNEE to LandmarkId.RIGHT_ANKLE,
        )

        private val HOMOLOGOUS_SEGMENTS = listOf(
            (LandmarkId.LEFT_SHOULDER to LandmarkId.LEFT_ELBOW) to
                (LandmarkId.RIGHT_SHOULDER to LandmarkId.RIGHT_ELBOW),
            (LandmarkId.LEFT_ELBOW to LandmarkId.LEFT_WRIST) to
                (LandmarkId.RIGHT_ELBOW to LandmarkId.RIGHT_WRIST),
            (LandmarkId.LEFT_HIP to LandmarkId.LEFT_KNEE) to
                (LandmarkId.RIGHT_HIP to LandmarkId.RIGHT_KNEE),
            (LandmarkId.LEFT_KNEE to LandmarkId.LEFT_ANKLE) to
                (LandmarkId.RIGHT_KNEE to LandmarkId.RIGHT_ANKLE),
        )
    }
}
