package ai.senp.motion

import ai.senp.core.contracts.FrameValidity
import ai.senp.core.contracts.FrameValidityReason
import ai.senp.core.contracts.FrameValidityStatus
import ai.senp.core.contracts.ImageLandmark
import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.WorldLandmark
import kotlin.math.PI
import kotlin.math.abs

/** Causal, bounded-state 1 Euro filtering. No interpolation, confidence inflation or bone constraints.
 * Algorithm: Casiez, Roussel & Vogel, CHI 2012, https://github.com/casiez/OneEuroFilter.
 * Image xy is aspect-corrected before filtering; world coordinates retain metre units.
 */
class LivePoseStabilizer(private val config: Config = Config()) {
    data class Config(
        val enabled: Boolean = true,
        val minCutoffHz: Double = 3.0,
        val imageBeta: Double = 80.0,
        val worldBeta: Double = 5.0,
        val derivativeCutoffHz: Double = 1.0,
        val minimumConfidence: Double = 0.5,
        val minimumBodyLandmarks: Int = 6,
        val minimumTorsoLandmarks: Int = 2,
        val resetGapMs: Long = 500,
    ) {
        init {
            require(minCutoffHz.isFinite() && minCutoffHz > 0)
            require(imageBeta.isFinite() && imageBeta >= 0)
            require(worldBeta.isFinite() && worldBeta >= 0)
            require(derivativeCutoffHz.isFinite() && derivativeCutoffHz > 0)
            require(minimumConfidence in 0.0..1.0)
            require(minimumBodyLandmarks in 1..12 && minimumTorsoLandmarks in 1..4)
            require(resetGapMs > 0)
        }
    }

    private val imageFilters = Array(33) { Array(3) { EuroFilter() } }
    private val worldFilters = Array(33) { Array(3) { EuroFilter() } }
    private var previousMs: Long? = null
    private var dimensions: Pair<Int, Int>? = null

    @Synchronized
    fun reset() {
        clearFilters()
        previousMs = null
        dimensions = null
    }

    @Synchronized
    fun update(raw: PoseFrame, width: Int, height: Int): PoseFrame {
        require(width > 0 && height > 0)
        val now = raw.timestamp.value
        val previous = previousMs
        require(previous == null || now > previous) { "live filter timestamps must strictly increase" }
        val restart = dimensions != (width to height) || (previous != null && now - previous > config.resetGapMs)
        if (restart) {
            clearFilters()
        }
        dimensions = width to height
        previousMs = now
        val usable = raw.landmarks.map { joint ->
            minOf(joint.visibility ?: 0.0, joint.presence ?: 0.0) >= config.minimumConfidence &&
                joint.image.x in 0.0..1.0 && joint.image.y in 0.0..1.0
        }
        val bodyCount = BODY.count { usable[it] }
        val torsoCount = TORSO.count { usable[it] }
        if (raw.validity.status == FrameValidityStatus.BLIND ||
            raw.validity.status == FrameValidityStatus.CONTINUITY_BREAK ||
            bodyCount < config.minimumBodyLandmarks || torsoCount < config.minimumTorsoLandmarks
        ) {
            clearFilters()
            if (raw.validity.status in setOf(FrameValidityStatus.BLIND, FrameValidityStatus.CONTINUITY_BREAK)) return raw
            val outOfFrame = BODY.any { raw.landmarks[it].image.x !in 0.0..1.0 || raw.landmarks[it].image.y !in 0.0..1.0 }
            return raw.copy(validity = FrameValidity(
                FrameValidityStatus.BLIND, 0.0,
                setOf(if (outOfFrame) FrameValidityReason.OUT_OF_FRAME else FrameValidityReason.UNUSABLE_TRACKING),
            ))
        }
        if (!config.enabled) return raw
        val dt = if (previous == null) 0.0 else (now - previous) / 1000.0
        val longEdge = maxOf(width, height).toDouble()
        val sx = width / longEdge
        val sy = height / longEdge
        val filtered = raw.copy(landmarks = raw.landmarks.mapIndexed { index, joint ->
            if (!usable[index]) {
                imageFilters[index].forEach(EuroFilter::reset)
                worldFilters[index].forEach(EuroFilter::reset)
                joint // Retain this observation, never substitute a previous reliable coordinate.
            } else {
                fun image(axis: Int, value: Double): Double = imageFilters[index][axis].update(value, dt, config.imageBeta)
                val world = joint.world?.let {
                    val filters = worldFilters[index]
                    WorldLandmark(
                        filters[0].update(it.xMeters, dt, config.worldBeta),
                        filters[1].update(it.yMeters, dt, config.worldBeta),
                        filters[2].update(it.zMeters, dt, config.worldBeta),
                    )
                }
                if (world == null) worldFilters[index].forEach(EuroFilter::reset)
                joint.copy(
                    image = ImageLandmark(image(0, joint.image.x * sx) / sx, image(1, joint.image.y * sy) / sy, image(2, joint.image.z * sx) / sx),
                    world = world,
                )
            }
        })
        return if (restart || previous == null) raw else filtered
    }

    private fun clearFilters() {
        imageFilters.forEach { it.forEach(EuroFilter::reset) }
        worldFilters.forEach { it.forEach(EuroFilter::reset) }
    }

    private inner class EuroFilter {
        private var raw: Double? = null
        private var filtered = 0.0
        private var derivative = 0.0
        fun reset() { raw = null; derivative = 0.0 }
        fun update(value: Double, dt: Double, beta: Double): Double {
            val before = raw
            raw = value
            if (before == null || dt <= 0.0) { filtered = value; return value }
            val da = alpha(config.derivativeCutoffHz, dt)
            derivative += da * ((value - before) / dt - derivative)
            val a = alpha(config.minCutoffHz + beta * abs(derivative), dt)
            filtered += a * (value - filtered)
            return filtered
        }
    }

    companion object {
        private val BODY = intArrayOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
        private val TORSO = intArrayOf(11, 12, 23, 24)
        private fun alpha(cutoff: Double, dt: Double): Double = 1.0 / (1.0 + 1.0 / (2.0 * PI * cutoff * dt))
    }
}
