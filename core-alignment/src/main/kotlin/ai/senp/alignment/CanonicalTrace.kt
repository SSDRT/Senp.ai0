package ai.senp.alignment

import ai.senp.core.contracts.AnalysisConfiguration
import ai.senp.core.contracts.FeatureSample as CanonicalFeatureSample
import ai.senp.core.contracts.FrameValidity
import ai.senp.core.contracts.FrameValidityReason
import ai.senp.core.contracts.FrameValidityStatus
import ai.senp.core.contracts.MotionSeries
import ai.senp.core.contracts.PhaseSeries
import ai.senp.core.contracts.PoseModelConfiguration
import ai.senp.core.contracts.SamplingConfiguration
import ai.senp.core.contracts.Sha256
import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.TimestampMs
import ai.senp.core.contracts.VideoRole
import ai.senp.core.pipeline.AlignmentAnalysis
import ai.senp.core.pipeline.CanonicalAlignmentArtifacts
import ai.senp.core.pipeline.TimestampFirstAlignmentEngine
import ai.senp.core.pipeline.TimestampFirstPhaseDetector
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking

private const val CANONICAL_TRACE_PROFILE = "alignment-synthetic/1"

internal fun MotionTrack.toCanonicalMotion(role: VideoRole): MotionSeries = MotionSeries(
    role = role,
    features = frames.map { frame ->
        val blind = frame.features.values.none { it.value != null }
        CanonicalFeatureSample(
            timestamp = TimestampMs(frame.timestampMs),
            values = frame.features.mapValues { (_, feature) -> feature.value },
            validity = if (blind) {
                FrameValidity(
                    status = FrameValidityStatus.BLIND,
                    confidence = 0.0,
                    reasons = setOf(FrameValidityReason.LONG_GAP),
                )
            } else {
                FrameValidity.Valid
            },
        )
    },
    angles = emptyList(),
)

internal fun canonicalConfiguration(
    profileVersion: String = CANONICAL_TRACE_PROFILE,
): AnalysisConfiguration = AnalysisConfiguration(
    model = PoseModelConfiguration(Sha256("0".repeat(64))),
    pipelineVersion = "alignment-adapter/1",
    sampling = SamplingConfiguration(targetFramesPerSecond = 15, longEdgeCapPx = 640),
    normalizationVersion = "pelvis-torso-scale/1",
    exerciseProfileVersion = profileVersion,
)

internal suspend fun canonicalPhases(
    detector: TimestampFirstPhaseDetector,
    motion: MotionSeries,
    profileVersion: String = CANONICAL_TRACE_PROFILE,
): PhaseSeries = detector.detect(motion, profileVersion).valueOrThrow()

internal suspend fun canonicalAlign(
    source: MotionSeries,
    reference: MotionSeries,
    profileVersion: String = CANONICAL_TRACE_PROFILE,
): AlignmentAnalysis {
    val detector = TimestampFirstPhaseDetector()
    val engine = TimestampFirstAlignmentEngine()
    val sourcePhases = canonicalPhases(detector, source, profileVersion)
    val referencePhases = canonicalPhases(detector, reference, profileVersion)
    return engine.align(
        sourceMotion = source,
        sourcePhases = sourcePhases,
        referenceMotion = reference,
        referencePhases = referencePhases,
        configuration = canonicalConfiguration(profileVersion),
    ).valueOrThrow()
}

internal fun <T> StageResult<T>.valueOrThrow(): T = when (this) {
    is StageResult.Success -> value
    is StageResult.Failure -> error("${failure.stage}: ${failure.message}")
}

fun main(args: Array<String>) = runBlocking {
    val outputDirectory = File(args.firstOrNull() ?: "build/canonical-traces")
    outputDirectory.mkdirs()
    val scenarios = linkedMapOf(
        "equal-motion" to canonicalAlign(
            syntheticTrack(15).toCanonicalMotion(VideoRole.SOURCE),
            syntheticTrack(20).toCanonicalMotion(VideoRole.REFERENCE),
        ),
        "deliberate-error" to canonicalAlign(
            syntheticTrack(15, errorRange = 4_000L..5_000L).toCanonicalMotion(VideoRole.SOURCE),
            syntheticTrack(20).toCanonicalMotion(VideoRole.REFERENCE),
        ),
        "long-blind-span" to canonicalAlign(
            syntheticTrack(20, errorRange = 3_000L..5_000L, invalidRange = 3_000L..5_000L)
                .toCanonicalMotion(VideoRole.SOURCE),
            syntheticTrack(15).toCanonicalMotion(VideoRole.REFERENCE),
        ),
        "speed-only" to canonicalAlign(
            syntheticTrack(15, timingExponent = 1.18).toCanonicalMotion(VideoRole.SOURCE),
            syntheticTrack(20).toCanonicalMotion(VideoRole.REFERENCE),
        ),
        "different-reps" to canonicalAlign(
            syntheticTrack(15, reps = 3.0).toCanonicalMotion(VideoRole.SOURCE),
            syntheticTrack(15, reps = 5.0).toCanonicalMotion(VideoRole.REFERENCE),
        ),
        "single-cycle" to canonicalAlign(
            syntheticTrack(20, reps = 1.0).toCanonicalMotion(VideoRole.SOURCE),
            syntheticTrack(15, reps = 1.0).toCanonicalMotion(VideoRole.REFERENCE),
        ),
    )

    val summary = buildString {
        appendLine("scenario,mode,mapping_points,aggregate_confidence,genuine_windows,uncertain_windows")
        for ((name, analysis) in scenarios) {
            CanonicalAlignmentArtifacts.writeJson(analysis, File(outputDirectory, "$name.json"))
            CanonicalAlignmentArtifacts.writeCsv(analysis.alignment, File(outputDirectory, "$name.csv"))
            append(name).append(',')
                .append(analysis.alignment.mode).append(',')
                .append(analysis.alignment.points.size).append(',')
                .append(String.format(Locale.ROOT, "%.6f", analysis.alignment.aggregateConfidence)).append(',')
                .append(analysis.problems.count { it.certainty.name == "GENUINE" }).append(',')
                .append(analysis.problems.count { it.certainty.name == "UNCERTAIN" }).appendLine()
        }
    }
    File(outputDirectory, "summary.csv").writeText(summary)
    print(summary)
    println("canonical_trace_directory=${outputDirectory.absolutePath}")
}
