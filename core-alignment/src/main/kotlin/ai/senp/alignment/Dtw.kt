package ai.senp.alignment

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class DtwNode(
    val user: Int,
    val reference: Int,
    val coverage: Double,
    val blind: Boolean,
    val normalizedCost: Double,
)

internal object Dtw {
    fun path(
        user: List<MotionFrame>,
        reference: List<MotionFrame>,
        profile: ExerciseProfile,
        config: AlignmentConfig,
    ): List<DtwNode> {
        val userCount = user.size
        val referenceCount = reference.size
        if (userCount == 0 || referenceCount == 0) return emptyList()

        val band = max(
            abs(userCount - referenceCount) + 2,
            (max(userCount, referenceCount) * config.dtwBandFraction).toInt().coerceAtLeast(2),
        )
        val accumulated = Array(userCount + 1) {
            DoubleArray(referenceCount + 1) { Double.POSITIVE_INFINITY }
        }
        val moves = Array(userCount + 1) { ByteArray(referenceCount + 1) { UNSET } }
        val metrics = Array(userCount) { arrayOfNulls<FeatureDistance>(referenceCount) }
        accumulated[0][0] = 0.0

        for (userIndex in 1..userCount) {
            val expectedReference = userIndex.toDouble() * referenceCount / userCount
            val referenceStart = max(1, expectedReference.toInt() - band)
            val referenceEnd = min(referenceCount, expectedReference.toInt() + band)
            for (referenceIndex in referenceStart..referenceEnd) {
                val metric = compareFrames(
                    user[userIndex - 1],
                    reference[referenceIndex - 1],
                    profile,
                )
                metrics[userIndex - 1][referenceIndex - 1] = metric
                val blind = metric.coverage < config.minimumCommonFeatureCoverage
                val localCost = metric.normalizedDifference +
                    config.missingFeaturePenalty * (1.0 - metric.coverage) +
                    if (blind) config.blindCellPenalty else 0.0

                val diagonal = accumulated[userIndex - 1][referenceIndex - 1]
                val vertical = accumulated[userIndex - 1][referenceIndex]
                val horizontal = accumulated[userIndex][referenceIndex - 1]
                when {
                    diagonal <= vertical && diagonal <= horizontal -> {
                        accumulated[userIndex][referenceIndex] = diagonal + localCost
                        moves[userIndex][referenceIndex] = DIAGONAL
                    }
                    vertical <= horizontal -> {
                        accumulated[userIndex][referenceIndex] = vertical + localCost
                        moves[userIndex][referenceIndex] = VERTICAL
                    }
                    else -> {
                        accumulated[userIndex][referenceIndex] = horizontal + localCost
                        moves[userIndex][referenceIndex] = HORIZONTAL
                    }
                }
            }
        }

        if (!accumulated[userCount][referenceCount].isFinite()) return emptyList()

        val reversed = mutableListOf<DtwNode>()
        var userIndex = userCount
        var referenceIndex = referenceCount
        while (userIndex > 0 && referenceIndex > 0) {
            val metric = metrics[userIndex - 1][referenceIndex - 1]
                ?: compareFrames(user[userIndex - 1], reference[referenceIndex - 1], profile)
            reversed += DtwNode(
                user = userIndex - 1,
                reference = referenceIndex - 1,
                coverage = metric.coverage,
                blind = metric.coverage < config.minimumCommonFeatureCoverage,
                normalizedCost = metric.normalizedDifference,
            )
            when (moves[userIndex][referenceIndex]) {
                DIAGONAL -> {
                    userIndex -= 1
                    referenceIndex -= 1
                }
                VERTICAL -> userIndex -= 1
                HORIZONTAL -> referenceIndex -= 1
                else -> return emptyList()
            }
        }

        while (userIndex > 0) {
            userIndex -= 1
            val metric = compareFrames(user[userIndex], reference.first(), profile)
            reversed += DtwNode(
                userIndex,
                0,
                metric.coverage,
                metric.coverage < config.minimumCommonFeatureCoverage,
                metric.normalizedDifference,
            )
        }
        while (referenceIndex > 0) {
            referenceIndex -= 1
            val metric = compareFrames(user.first(), reference[referenceIndex], profile)
            reversed += DtwNode(
                0,
                referenceIndex,
                metric.coverage,
                metric.coverage < config.minimumCommonFeatureCoverage,
                metric.normalizedDifference,
            )
        }

        return reversed.asReversed().deduplicateConsecutive()
    }

    private fun List<DtwNode>.deduplicateConsecutive(): List<DtwNode> {
        val output = ArrayList<DtwNode>(size)
        for (node in this) {
            if (output.lastOrNull()?.let { it.user == node.user && it.reference == node.reference } != true) {
                output += node
            }
        }
        return output
    }

    private const val UNSET: Byte = -1
    private const val DIAGONAL: Byte = 0
    private const val VERTICAL: Byte = 1
    private const val HORIZONTAL: Byte = 2
}
