package ai.senp.motion

import java.io.File
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Synthetic truth, not a claim of human-labelled pose accuracy. Runs the production filter. */
object LivePoseTuningBenchmark {
    data class Metrics(val jitter: Double, val movingRmse: Double)

    fun measure(config: LivePoseStabilizer.Config, fps: Int, irregular: Boolean = false): Metrics {
        fun run(moving: Boolean): Double {
            val random = Random(20261022)
            val filter = LivePoseStabilizer(config)
            var error = 0.0
            var count = 0
            var timeMs = 0.0
            repeat(fps * 12) { index ->
                timeMs += 1000.0 / fps * if (irregular) (0.6 + random.nextDouble() * 0.8) else 1.0
                val truth = 0.5 + if (moving) 0.18 * sin(2 * PI * 0.8 * timeMs / 1000.0) else 0.0
                val noisy = truth + (random.nextDouble() - 0.5) * 0.04
                val result = filter.update(liveFrame(timeMs.roundToLong(), noisy), 640, 480)
                if (index >= fps) { error += (result.landmarks[15].image.x - truth).pow(2); count++ }
            }
            return sqrt(error / count)
        }
        return Metrics(run(false), run(true))
    }

    @JvmStatic fun main(args: Array<String>) {
        val rows = mutableListOf<String>()
        for (fps in listOf(10, 15, 30, 60)) for (irregular in listOf(false, true)) {
            val raw = measure(LivePoseStabilizer.Config(enabled = false), fps, irregular)
            val candidates = listOf("raw" to LivePoseStabilizer.Config(enabled = false)) +
                listOf(3.0, 5.0, 8.0).flatMap { cutoff -> listOf(0.0, 20.0, 40.0, 80.0).map { beta ->
                    "euro-$cutoff-$beta" to LivePoseStabilizer.Config(minCutoffHz = cutoff, imageBeta = beta)
                } }
            for ((name, config) in candidates) {
                val metric = measure(config, fps, irregular)
                rows += """{"profile":"$name","fps":$fps,"irregular":$irregular,"static_rmse":${metric.jitter},"moving_rmse":${metric.movingRmse},"jitter_reduction":${1 - metric.jitter / raw.jitter},"moving_error_ratio":${metric.movingRmse / raw.movingRmse}}"""
            }
        }
        val report = File(args[0])
        report.parentFile.mkdirs()
        report.writeText("""{"schema":1,"kind":"synthetic-filter-only","seed":20261022,"noise_amplitude":0.02,"motion_hz":0.8,"duration_seconds":12,"rows":[${rows.joinToString(",\n")}]}""")
        println("Live pose synthetic parameter sweep: ${report.absolutePath}")
    }
}
