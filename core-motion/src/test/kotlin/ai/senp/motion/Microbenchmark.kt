package ai.senp.motion

import java.io.File
import java.util.Locale

object Microbenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        Locale.setDefault(Locale.US)
        val outputFile = args.firstOrNull()?.let(::File)
        val measuredRuns = args.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(10) ?: 120
        val warmupRuns = 20
        val frames = SyntheticPose.withMissing(
            SyntheticPose.sequence(fps = 15, seconds = 10, noise = 0.01),
            4000L,
            4080L,
            setOf(LandmarkId.LEFT_WRIST),
        )
        val engine = MotionEngine(
            MotionConfig(
                maxRepairGapMs = 220L,
                continuityBreakGapMs = 300L,
                emaHalfLifeMs = 120L,
            ),
        )
        val profile = ExerciseProfiles.bicepsCurl.copy(sidePolicy = SidePolicy.LEFT_ONLY)

        var checksum = 0.0
        repeat(warmupRuns) {
            checksum += engine.analyze(frames, profile).sumOf { item -> item.quality.score }
        }

        val samplesMs = DoubleArray(measuredRuns)
        repeat(measuredRuns) { run ->
            val started = System.nanoTime()
            val result = engine.analyze(frames, profile)
            samplesMs[run] = (System.nanoTime() - started) / 1_000_000.0
            checksum += result.sumOf { item -> item.quality.score }
        }
        val sorted = samplesMs.sorted()
        val totalMs = samplesMs.sum()
        val meanMs = samplesMs.average()
        val p50Ms = percentile(sorted, 0.50)
        val p95Ms = percentile(sorted, 0.95)
        val framesPerSecond = frames.size * measuredRuns * 1000.0 / totalMs
        val json = buildString {
            append("{\n")
            append("  \"scenario\": \"synthetic-motion-core-10s\",\n")
            append("  \"duration_ms\": 10000,\n")
            append("  \"source_fps\": 15,\n")
            append("  \"frames_per_run\": ").append(frames.size).append(",\n")
            append("  \"warmup_runs\": ").append(warmupRuns).append(",\n")
            append("  \"measured_runs\": ").append(measuredRuns).append(",\n")
            append("  \"total_ms\": ").append(decimal(totalMs)).append(",\n")
            append("  \"mean_ms_per_run\": ").append(decimal(meanMs)).append(",\n")
            append("  \"p50_ms_per_run\": ").append(decimal(p50Ms)).append(",\n")
            append("  \"p95_ms_per_run\": ").append(decimal(p95Ms)).append(",\n")
            append("  \"processed_frames_per_second\": ").append(decimal(framesPerSecond)).append(",\n")
            append("  \"checksum\": ").append(decimal(checksum)).append(",\n")
            append("  \"java_version\": \"").append(System.getProperty("java.version")).append("\",\n")
            append("  \"os_arch\": \"").append(System.getProperty("os.arch")).append("\"\n")
            append("}\n")
        }
        if (outputFile != null) {
            outputFile.parentFile?.mkdirs()
            outputFile.writeText(json)
            println("benchmark_report=${outputFile.absolutePath}")
        }
        print(json)
    }

    private fun percentile(sorted: List<Double>, percentile: Double): Double {
        val index = ((sorted.size - 1) * percentile).toInt()
        return sorted[index]
    }

    private fun decimal(value: Double): String = "%.6f".format(Locale.US, value)
}
