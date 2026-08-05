package ai.senp.motion

/** Batch-oriented pure Kotlin/JVM entry point for motion quality processing. */
class MotionEngine(
    private val config: MotionConfig = MotionConfig(),
    guardrailConfig: GuardrailConfig = GuardrailConfig(minScale = config.minScale),
) {
    private val processor = TrackProcessor(config)
    private val qualityGate = QualityGate(config)
    private val guardrails = PoseGuardrails(guardrailConfig)

    fun analyze(
        frames: List<PoseFrame>,
        profile: ExerciseProfile,
        signals: List<FrameSignals> = frames.map { FrameSignals() },
    ): List<ProcessedFrame> {
        require(frames.size == signals.size) { "one FrameSignals value is required per frame" }
        if (frames.isEmpty()) return emptyList()

        val correctedFrames = ArrayList<PoseFrame>(frames.size)
        val flags = ArrayList<GuardrailFlags>(frames.size)
        for (frame in frames) {
            val inspection = guardrails.inspect(frame, correctedFrames.lastOrNull())
            correctedFrames += inspection.frame
            flags += inspection.flags
        }

        val tracked = processor.processDetailed(correctedFrames)
        val quality = qualityGate.evaluateTracked(tracked, profile, signals, flags)
        return tracked.indices.map { index ->
            ProcessedFrame(
                frame = tracked[index].frame,
                quality = quality[index],
                repairedLandmarks = tracked[index].repairedLandmarks,
                continuityBreakLandmarks = tracked[index].continuityBreakLandmarks,
                guardrails = flags[index],
            )
        }
    }
}
