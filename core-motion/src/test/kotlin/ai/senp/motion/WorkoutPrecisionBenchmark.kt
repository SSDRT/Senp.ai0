package ai.senp.motion

import java.io.File
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Known-phase decision tests, not camera/pose/workout-classification accuracy measurements. */
object WorkoutPrecisionBenchmark {
    val families = linkedMapOf(
        "squat" to listOf("angle.left_knee", "angle.right_hip"),
        "lunge" to listOf("angle.right_knee", "distance.foot_separation"),
        "split_squat" to listOf("angle.left_knee", "angle.left_hip"),
        "step_up" to listOf("angle.right_knee", "angle.right_hip"),
        "hip_hinge" to listOf("angle.left_hip", "angle.left_body_line"),
        "deadlift_pattern" to listOf("angle.right_hip", "angle.right_body_line"),
        "glute_bridge" to listOf("angle.left_hip", "angle.right_hip"),
        "push_up" to listOf("angle.left_elbow", "angle.left_body_line"),
        "horizontal_press" to listOf("angle.right_elbow", "distance.hand_separation"),
        "overhead_press" to listOf("angle.left_shoulder", "angle.right_elbow"),
        "pull_up" to listOf("angle.right_shoulder", "angle.left_elbow"),
        "row" to listOf("angle.right_elbow", "distance.right_hand_to_hip"),
        "biceps_curl" to listOf("angle.left_elbow", "angle.right_elbow"),
        "triceps_extension" to listOf("angle.right_elbow", "angle.left_shoulder"),
        "lateral_raise" to listOf("angle.left_shoulder", "angle.right_shoulder"),
        "leg_raise" to listOf("angle.left_hip", "angle.left_knee"),
        "crunch_pattern" to listOf("angle.left_body_line", "angle.right_body_line"),
        "plank_hold" to listOf("angle.left_body_line", "angle.right_body_line"),
        "side_plank_hold" to listOf("angle.left_shoulder", "angle.left_body_line"),
        "wall_sit_hold" to listOf("angle.left_knee", "angle.right_knee"),
        "calf_raise" to listOf("angle.left_ankle", "angle.right_ankle"),
        "jumping_jack" to listOf("angle.right_shoulder", "distance.foot_separation"),
        "mountain_climber" to listOf("angle.right_hip", "angle.left_knee"),
        "burpee_pattern" to listOf("angle.left_shoulder", "angle.right_elbow", "angle.right_hip", "angle.left_knee"),
        "unlisted_custom_workout" to WorkoutPrecisionTestData.angles + WorkoutPrecisionTestData.distances,
    )

    data class Metrics(val trueEvents: Int, val falseEvents: Int, val expectedEpisodes: Int, val detectedEpisodes: Int, val weakActiveFrames: Int, val maxDelayMs: Long)
    fun run(features: List<String>, fps: Int, confirmationMs: Long, seed: Int): Metrics {
        val profile = WorkoutPrecisionTestData.profile()
        val coach = WorkoutPrecisionCoach(profile, WorkoutPrecisionConfig(confirmationMs = confirmationMs))
        val random = Random(seed)
        val found = mutableSetOf<String>()
        val firstEvidence = mutableMapOf<String, Long>()
        var trueEvents = 0; var falseEvents = 0; var weakActiveFrames = 0; var maxDelay = 0L
        var time = 0.0
        while (time < 8000.0) {
            val ms = time.roundToLong()
            val fault = ms in 2000L..3999L
            val spike = ms in 800L..999L
            val weak = ms >= 6000L
            val changes = (WorkoutPrecisionTestData.angles + WorkoutPrecisionTestData.distances).associateWith { name ->
                val angle = name.startsWith("angle.")
                val noise = (random.nextDouble() * 2.0 - 1.0) * if (angle) 2.0 else 0.02
                val sign = if (seed % 2 == 0) 1 else -1
                val delta = if (name in features && (fault || spike || weak)) sign * if (angle) 18.0 else 0.22 else 0.0
                (if (angle) 90.0 else 0.8) + noise + delta
            }
            var frame = WorkoutPrecisionTestData.frame(ms, profile, changes)
            if (weak) frame = frame.copy(intrinsicDescriptor = frame.intrinsicDescriptor.copy(
                featureConfidences = frame.intrinsicDescriptor.featureConfidences.mapValues { (key, confidence) -> if (key in features) 0.2 else confidence },
            ))
            if (fault) features.forEach { firstEvidence.putIfAbsent(it, ms) }
            val result = coach.evaluate(frame, WorkoutPrecisionTestData.estimate(ms))
            if (weak) weakActiveFrames += result.activeSuggestions.count { it.feature in features }
            result.triggers.forEach { event ->
                if (fault && event.feature in features) {
                    trueEvents++; found += event.feature
                    maxDelay = max(maxDelay, ms - firstEvidence.getValue(event.feature))
                } else falseEvents++
            }
            time += 1000.0 / fps * (0.8 + random.nextDouble() * 0.4)
        }
        return Metrics(trueEvents, falseEvents, features.size, found.size, weakActiveFrames, maxDelay)
    }

    @JvmStatic fun main(args: Array<String>) {
        val rows = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val candidates = listOf(0L, 80L, 120L, 180L, 240L, 320L)
        val aggregate = linkedMapOf<Long, MutableList<Metrics>>()
        for (dwell in candidates) for ((family, features) in families) for (fps in listOf(10, 15, 30, 60)) for (seed in listOf(20261022, 20261023)) {
            val metric = run(features, fps, dwell, seed)
            aggregate.getOrPut(dwell) { mutableListOf() } += metric
            rows += buildJsonObject {
                put("family_scenario", family); put("fps", fps); put("seed", seed); put("confirmation_ms", dwell)
                put("true_events", metric.trueEvents); put("false_events", metric.falseEvents)
                put("expected_episodes", metric.expectedEpisodes); put("detected_episodes", metric.detectedEpisodes)
                put("weak_active_frames", metric.weakActiveFrames); put("max_trigger_delay_ms", metric.maxDelayMs)
            }
        }
        val summaries = aggregate.map { (dwell, metrics) ->
            val trueEvents = metrics.sumOf { it.trueEvents }; val falseEvents = metrics.sumOf { it.falseEvents }
            buildJsonObject {
                put("confirmation_ms", dwell); put("scenario_count", metrics.size)
                put("true_events", trueEvents); put("false_events", falseEvents)
                put("decision_precision", trueEvents.toDouble() / max(1, trueEvents + falseEvents))
                put("episode_recall", metrics.sumOf { it.detectedEpisodes }.toDouble() / metrics.sumOf { it.expectedEpisodes })
                put("weak_active_frames", metrics.sumOf { it.weakActiveFrames })
                put("max_trigger_delay_ms", metrics.maxOf { it.maxDelayMs })
            }
        }
        val report = buildJsonObject {
            put("schema", 1); put("kind", "synthetic-known-phase-threshold-logic-only")
            put("not_measured", "human landmark accuracy, exercise classification, expert form correctness, phone performance")
            put("family_scenarios", families.size); put("phase_source", "oracle-known-state")
            put("noise", "plus/minus 2 degrees and 0.02 torso units; timestamps jittered +/-20 percent")
            put("transient_spike_ms", 200); put("persistent_error_ms", 2000)
            put("selected_confirmation_ms", 240); put("summaries", JsonArray(summaries)); put("rows", JsonArray(rows))
            put("feature_inventory", JsonArray((WorkoutPrecisionTestData.angles + WorkoutPrecisionTestData.distances).map(::JsonPrimitive)))
        }
        val destination = File(args[0]); destination.parentFile.mkdirs()
        destination.writeText(Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), report))
        println("Workout threshold benchmark: ${destination.absolutePath}")
        summaries.forEach { println(it) }
        val selected = aggregate.getValue(240)
        check(selected.all { it.falseEvents == 0 && it.detectedEpisodes == it.expectedEpisodes && it.weakActiveFrames == 0 })
    }
}
