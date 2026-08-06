package ai.senp.alignment

import ai.senp.core.contracts.VideoRole
import ai.senp.core.pipeline.TimestampFirstAlignmentEngine
import ai.senp.core.pipeline.TimestampFirstPhaseDetector
import java.util.Locale
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("fps,source_frames,reference_frames,iterations,total_ms,mean_ms,checksum")
    for (fps in listOf(10, 15, 20, 30)) {
        val source = syntheticTrack(fps, durationMs = 10_000L, timingExponent = 1.08)
            .toCanonicalMotion(VideoRole.SOURCE)
        val reference = syntheticTrack(fps, durationMs = 10_000L)
            .toCanonicalMotion(VideoRole.REFERENCE)
        val detector = TimestampFirstPhaseDetector()
        val engine = TimestampFirstAlignmentEngine()
        val sourcePhases = canonicalPhases(detector, source)
        val referencePhases = canonicalPhases(detector, reference)
        val configuration = canonicalConfiguration()

        repeat(5) {
            engine.align(source, sourcePhases, reference, referencePhases, configuration).valueOrThrow()
        }

        val iterations = 30
        var checksum = 0L
        val started = System.nanoTime()
        repeat(iterations) {
            val result = engine.align(
                source,
                sourcePhases,
                reference,
                referencePhases,
                configuration,
            ).valueOrThrow()
            checksum += result.alignment.points.sumOf { point -> point.referenceTimestamp.value }
            checksum += result.problems.size
        }
        val totalMs = (System.nanoTime() - started) / 1_000_000.0
        println(
            listOf(
                fps,
                source.features.size,
                reference.features.size,
                iterations,
                String.format(Locale.ROOT, "%.3f", totalMs),
                String.format(Locale.ROOT, "%.3f", totalMs / iterations),
                checksum,
            ).joinToString(","),
        )
    }
}
