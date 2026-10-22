package ai.senp.pose.mediapipe

/** Single-producer capture-time admission. Drops stale/over-budget frames before bitmap allocation.
 * A new camera stream must use a new gate; a clock reversal is never rewritten into invented time.
 */
class LiveFrameGate(private val minimumIntervalMs: Long = 33) {
    init { require(minimumIntervalMs >= 0) }
    private var previousCaptureNs: Long? = null
    private var acceptedMs: Long? = null
    var droppedFrames: Long = 0
        private set

    fun accept(timestampNs: Long): Long? {
        val previous = previousCaptureNs
        if (timestampNs < 0 || (previous != null && timestampNs <= previous)) return drop()
        previousCaptureNs = timestampNs
        val timestampMs = timestampNs / 1_000_000
        val accepted = acceptedMs
        if (accepted != null && (timestampMs <= accepted || timestampMs - accepted < minimumIntervalMs)) return drop()
        acceptedMs = timestampMs
        return timestampMs
    }

    private fun drop(): Long? { droppedFrames++; return null }
}
