package ai.senp.motion

import java.io.File
import kotlinx.serialization.json.*

/** Deliberately constructed observation controls, not independently labelled human repetitions. */
object QualifiedRepEvidenceBenchmark {
    internal fun reference(exercise: String, config: QualifiedRepConfig, asymmetric: Boolean): QualifiedRepReference {
        val quality = repExerciseSpecs.getValue(exercise).quality.single()
        val profile = QualifiedRepTestData.profile(exercise).let { base -> if (!asymmetric) base else base.copy(
            states = base.states.mapIndexed { index, state -> state.copy(features = state.features.map { feature ->
                if (feature.name.endsWith("_$quality")) {
                    val v = if (index in 4..5) 65.0 else 15.0
                    feature.copy(reference = feature.reference.copy(median = v,lower = v-2,upper = v+2))
                } else feature
            }) }) }
        return (QualifiedRepCompiler.compile(profile,exercise,QualifiedRepTestData.identity,config) as QualifiedRepCompilation.Ready).reference
    }

    internal fun samples(fps: Int, kind: String): List<Pair<Long,Double>> {
        val step = 1000.0/fps
        if (kind.startsWith("phase_")) {
            // Sustained far endpoint, then immediately observed branch-specific return geometry.
            return generateSequence(0) { it+1 }.map { (it*step).toLong() }.takeWhile { it <= 2000 }.map { t ->
                val p = when {
                    t < 300 -> 0.0
                    t < 700 -> (t-300)/400.0
                    t < 1000 -> 1.0
                    t < 1500 -> (1500-t)/500.0
                    else -> 0.0
                }
                t to p
            }.toList()
        }
        val points = List(fps/3+2) { 0.0 }+listOf(.5)+List(fps*5) { 1.0 }+listOf(.5)+List(fps) { 0.0 }
        return points.mapIndexed { index,p -> (index*step).toLong() to p }
    }

    internal fun frame(reference: QualifiedRepReference, time: Long, p: Double, kind: String): SpatialObservationFrame {
        val exercise = reference.exerciseId
        val quality = repExerciseSpecs.getValue(exercise).quality.single()
        val onReturn = time >= 1000
        val changes = if (kind.startsWith("phase_")) {
            val band = reference.band("angle.${reference.config.side.name.lowercase()}_$quality",p,
                if (kind == "phase_wrong_return") false else onReturn)
            val v = (band.lower+band.upper)/2
            listOf("left","right").associate { "angle.${it}_$quality" to v }
        } else if (p == .5 && (kind == "incompatible_both" ||
                kind == "incompatible_outbound" && !onReturn || kind == "incompatible_return" && onReturn)) {
            listOf("left","right").associate { side ->
                val key = "angle.${side}_$quality"
                val original = QualifiedRepTestData.features(exercise,p).getValue(key)
                key to if (original > 90) original-50 else original+50
            }
        } else emptyMap()
        return QualifiedRepTestData.frame(time,p,exercise,changes)
    }

    @JvmStatic fun main(args: Array<String>) {
        val rows = mutableListOf<JsonObject>()
        for (exercise in QualifiedRepCompiler.supportedExerciseIds) for (side in RepObservedSide.entries)
            for (mirror in listOf(ActionMirrorMode.DIRECT,ActionMirrorMode.MIRRORED)) for (fps in listOf(10,15,30,60))
                for (kind in listOf("compatible_sparse","incompatible_outbound","incompatible_return","incompatible_both","phase_correct","phase_wrong_return")) {
                    val ref = reference(exercise,QualifiedRepConfig(side = side,mirrorMode = mirror),kind.startsWith("phase_"))
                    val engine = ref.newEngine()
                    val samples = samples(fps,kind)
                    for ((time,p) in samples) engine.evaluate(frame(ref,time,p,kind),QualifiedRepTestData.identity.poseModelSha256)
                    val counts = engine.finish(samples.last().first+1).counts
                    val positive = kind in setOf("compatible_sparse","phase_correct")
                    rows += buildJsonObject {
                        put("exercise",exercise);put("side",side.name);put("mirror",mirror.name);put("fps",fps);put("scenario",kind)
                        put("qualified",counts.qualified);put("rejected",counts.rejected);put("unassessable",counts.unassessable)
                        put("expected_qualified",if (positive) 1 else 0);put("passed",counts.qualified == if (positive) 1 else 0)
                    }
                }
        val report = buildJsonObject {
            put("kind","constructed-compatible-path-and-asymmetric-phase-controls-not-human-accuracy")
            put("scenario_count",rows.size);put("failures",rows.count { !it.getValue("passed").jsonPrimitive.boolean })
            put("rows",JsonArray(rows))
        }
        File(args[0]).apply { parentFile.mkdirs() }.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(),report))
        println("Evidence controls ${rows.size}; failures ${report["failures"]}; ${args[0]}")
        if (args.getOrNull(1) != "report-only") check(report.getValue("failures").jsonPrimitive.int == 0)
    }
}
