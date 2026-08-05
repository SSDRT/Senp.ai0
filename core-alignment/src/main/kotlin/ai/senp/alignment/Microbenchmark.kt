package ai.senp.alignment

import java.util.Locale

fun main() {
    println("fps,user_frames,reference_frames,iterations,total_ms,mean_ms,checksum")
    for (fps in listOf(10, 15, 20, 30)) {
        val user = syntheticTrack(fps, durationMs = 10_000L, timingExponent = 1.08)
        val reference = syntheticTrack(fps, durationMs = 10_000L)
        val engine = AlignmentEngine()
        repeat(5) { engine.align(user, reference, DEFAULT_PROFILE) }

        val iterations = 30
        var checksum = 0L
        val started = System.nanoTime()
        repeat(iterations) {
            val result = engine.align(user, reference, DEFAULT_PROFILE)
            checksum += result.mapping.sumOf { point -> point.referenceTimestampMs }
            checksum += result.windows.size
        }
        val totalMs = (System.nanoTime() - started) / 1_000_000.0
        val meanMs = totalMs / iterations
        println(
            listOf(
                fps,
                user.frames.size,
                reference.frames.size,
                iterations,
                String.format(Locale.ROOT, "%.3f", totalMs),
                String.format(Locale.ROOT, "%.3f", meanMs),
                checksum,
            ).joinToString(","),
        )
    }
}
