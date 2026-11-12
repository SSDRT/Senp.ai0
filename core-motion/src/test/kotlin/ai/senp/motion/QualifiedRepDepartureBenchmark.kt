package ai.senp.motion

import java.io.File
import kotlinx.serialization.json.*

/** One isolated departure-frame error; later evidence is unchanged and directly observed. */
object QualifiedRepDepartureBenchmark {
    internal fun samples(fps: Int, amplitude: Double, spike: Boolean): List<Pair<Long,Double>> {
        var injected = false
        return QualifiedRepTestData.stream(fps,amplitude = amplitude).map { (time,p) ->
            val replace = spike && !injected && time > 300
            if (replace) injected = true
            time to if (replace) .98 else p
        }
    }
    @JvmStatic fun main(args: Array<String>) {
        val rows = mutableListOf<JsonObject>()
        for (exercise in QualifiedRepCompiler.supportedExerciseIds) for (side in RepObservedSide.entries)
            for (mirror in listOf(ActionMirrorMode.DIRECT,ActionMirrorMode.MIRRORED)) for (fps in listOf(10,15,30,60)) {
                for ((kind,amplitude) in listOf("complete" to 1.0,"shallow" to .45)) for (spike in listOf(false,true)) {
                    val reference = QualifiedRepTestData.reference(exercise,QualifiedRepConfig(side = side,mirrorMode = mirror))
                    val engine = reference.newEngine()
                    val outputs = samples(fps,amplitude,spike).map { (time,p) ->
                        engine.evaluate(QualifiedRepTestData.frame(time,p,exercise),QualifiedRepTestData.identity.poseModelSha256)
                    }
                    val counts = outputs.last().counts
                    val earlyReturn = outputs.any { it.timestampMs < 1100 && it.stage == RepStage.RETURNING }
                    val passed = !earlyReturn && if (kind == "complete") counts.qualified == 1 else counts.rejected == 1 && counts.qualified == 0
                    rows += buildJsonObject {
                        put("exercise",exercise);put("side",side.name);put("mirror",mirror.name);put("fps",fps)
                        put("scenario",kind);put("departure_spike",spike);put("premature_return_phase",earlyReturn)
                        put("qualified",counts.qualified);put("rejected",counts.rejected);put("unassessable",counts.unassessable);put("passed",passed)
                    }
                }
            }
        val failures = rows.count { !it.getValue("passed").jsonPrimitive.boolean }
        File(args[0]).apply { parentFile.mkdirs() }.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("kind","generated-single-departure-spike-controls-not-human-form-precision")
            put("scenarios",rows.size);put("failures",failures);put("rows",JsonArray(rows))
        }))
        println("Departure-frame scenarios ${rows.size}; failures $failures")
        check(failures == 0) { "Departure-frame peak support regression" }
    }
}
