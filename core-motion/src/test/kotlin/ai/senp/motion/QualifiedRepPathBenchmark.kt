package ai.senp.motion

import java.io.File
import kotlinx.serialization.json.*

/** Generated observation-coverage controls. An unobserved path is uncertain, not a human form label. */
object QualifiedRepPathBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        val rows = mutableListOf<JsonObject>()
        for (exercise in QualifiedRepCompiler.supportedExerciseIds) for (side in RepObservedSide.entries)
            for (mirror in listOf(ActionMirrorMode.DIRECT,ActionMirrorMode.MIRRORED)) for (fps in listOf(10,15,30,60)) {
                val config = QualifiedRepConfig(side = side,mirrorMode = mirror)
                for (kind in listOf("endpoint_jump","missing_outbound","missing_return","complete_0.75","complete_1.0","complete_2.0")) {
                    val scale = kind.removePrefix("complete_").toDoubleOrNull()
                    val samples = if (scale != null) QualifiedRepTestData.stream(fps,scale = scale) else {
                        generateSequence(0) { it+1 }.map { (it*1000.0/fps).toLong() }.takeWhile { it <= 1600 }.map { time ->
                            val p = when (kind) {
                                "endpoint_jump" -> if (time in 400 until 700) 1.0 else 0.0
                                "missing_outbound" -> when { time < 400 -> 0.0;time < 700 -> 1.0;time < 1100 -> (1100-time)/400.0;else -> 0.0 }
                                else -> when { time < 300 -> 0.0;time < 700 -> (time-300)/400.0;time < 900 -> 1.0;else -> 0.0 }
                            }
                            time to p
                        }.toList()
                    }
                    fun replay(requirePath: Boolean): QualifiedRepCounts {
                        val ref = QualifiedRepTestData.reference(exercise,config.copy(requireObservedPath = requirePath))
                        val engine = ref.newEngine()
                        for ((time,p) in samples) engine.evaluate(QualifiedRepTestData.frame(time,p,exercise),QualifiedRepTestData.identity.poseModelSha256)
                        return engine.finish(samples.last().first+1).counts
                    }
                    val before = replay(false);val after = replay(true)
                    val passed = if (scale != null) before.qualified == 1 && after.qualified == 1 else before.qualified == 1 && after.qualified == 0 && after.unassessable == 1 && after.rejected == 0
                    rows += buildJsonObject {
                        put("exercise",exercise);put("side",side.name);put("mirror",mirror.name);put("fps",fps);put("scenario",kind)
                        put("counterfactual_endpoint_only_accepted",before.qualified);put("observed_path_accepted",after.qualified)
                        put("observed_path_unassessable",after.unassessable);put("observed_path_rejected",after.rejected);put("passed",passed)
                    }
                }
            }
        val failures = rows.count { !it.getValue("passed").jsonPrimitive.boolean }
        val report = buildJsonObject {
            put("kind","generated-observation-path-controls-not-human-form-precision");put("scenario_count",rows.size);put("failures",failures)
            put("rows",JsonArray(rows))
        }
        File(args[0]).apply { parentFile.mkdirs() }.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(),report))
        println("Observed path scenarios ${rows.size}; failures $failures")
        check(failures == 0) { "Observed path regression failed" }
    }
}
