package ai.senp.video

import kotlin.math.floor

/** Timestamp-domain sampler with no frame-index assumptions or accumulated integer drift. */
class TimestampSampler(val targetFps: Double = 15.0) {
    private val intervalUs = 1_000_000.0 / targetFps
    private var anchorUs: Long? = null
    private var emissionIndex = 0L

    init {
        require(targetFps > 0.0 && targetFps <= 120.0)
    }

    fun shouldEmit(presentationTimeUs: Long): Boolean {
        val anchor = anchorUs ?: presentationTimeUs.also { anchorUs = it }
        val threshold = anchor + floor(emissionIndex * intervalUs + 0.5).toLong()
        if (presentationTimeUs < threshold) return false
        do {
            emissionIndex++
        } while (presentationTimeUs >= anchor + floor(emissionIndex * intervalUs + 0.5).toLong())
        return true
    }
}

internal class MonotonicTimestampGuard {
    private var previousUs: Long? = null

    fun accept(currentUs: Long) {
        val previous = previousUs
        if (previous != null && currentUs <= previous) {
            throw VideoDecodeException.NonMonotonic(previous, currentUs)
        }
        previousUs = currentUs
    }
}
