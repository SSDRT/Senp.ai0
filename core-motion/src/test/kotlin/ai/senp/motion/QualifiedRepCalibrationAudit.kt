package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*

/** Explains calibration failures without weakening production gates or interpreting clip labels as truth. */
object QualifiedRepCalibrationAudit {
    @JvmStatic fun main(args: Array<String>) {
        val input = File(args[0]);val output = File(args[1]);output.mkdirs()
        val spatial = SpatialSynchronizationEngine(SpatialSynchronizationConfig(includeWorkoutFeatures = true))
        val ids = mapOf("squat" to "bodyweight_squat","push_up" to "push_up","bicep_curl" to "biceps_curl","shoulder" to "overhead_press")
        val rows = mutableListOf<JsonObject>()
        for (file in input.listFiles()!!.filter { it.name.matches(Regex("subject_(001|005)_.*_good_(front|side)\\.json\\.gz")) }.sortedBy { it.name }) {
            val payload = GZIPInputStream(file.inputStream()).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            val observations = payload.getValue("frames").jsonArray.map { QualifiedRepReplayTool.observation(it.jsonObject) }
            val frames = spatial.analyzeSequence(CanonicalObservationSequence(VideoRole.REFERENCE,DurationMs(observations.last().timestamp.value+1),
                ObservationSampling(analysisFramesPerSecond = 15.0),observations)).frames
            val sequence = SpatialSequenceAnalysis(VideoRole.REFERENCE,DurationMs(frames.last().timestamp.value+1),ObservationSampling(analysisFramesPerSecond = 15.0),frames,1.0)
            val identity = RepEvidenceFingerprint(payload.getValue("source_video_sha256").jsonPrimitive.content,payload.getValue("model_sha256").jsonPrimitive.content,
                declaredView = payload.getValue("view").jsonPrimitive.content)
            val id = ids.getValue(payload.getValue("exercise").jsonPrimitive.content)
            for (side in RepObservedSide.entries) {
                val config = QualifiedRepConfig(side = side)
                val result = QualifiedRepCompiler.compile(sequence,id,identity,config)
                // Diagnostic counterfactual only: exposes failed self-replay, never exported as a usable reference.
                val counterfactual = if (result is QualifiedRepCompilation.Unavailable && result.reason == RepReferenceFailure.LOW_SELF_RECONSTRUCTION)
                    QualifiedRepCompiler.compile(sequence,id,identity,config.copy(minimumReferenceReconstruction = 0.0)) else result
                val ref = (counterfactual as? QualifiedRepCompilation.Ready)?.reference
                val spec = repExerciseSpecs.getValue(id)
                val required = (spec.quality+spec.driver).map { "angle.${side.name.lowercase()}_$it" }
                val trace = mutableListOf<JsonObject>();val attempts = mutableListOf<JsonObject>()
                val engine = ref?.newEngine()
                for (f in frames) {
                    val decision = engine?.evaluate(f,identity.poseModelSha256,captureContext = identity.declaredView)
                    decision?.finalizedAttempts?.forEach { a -> attempts += buildJsonObject {
                        put("start_ms",a.startedMs);put("end_ms",a.completedMs);put("verdict",a.verdict.name)
                        put("reasons",JsonArray(a.reasons.map { JsonPrimitive(it.name) }));put("maximum_progress",a.maximumProgress)
                        put("ambiguous_fraction",a.ambiguousFraction);put("visible_fraction",a.visibleFraction)
                    } }
                    trace += buildJsonObject {
                        put("timestamp_ms",f.timestamp.value);put("stage",decision?.stage?.name ?: "NOT_COMPILED")
                        put("progress",decision?.progress?.let(::JsonPrimitive) ?: JsonNull)
                        put("values",buildJsonObject { for (name in required) put(name,f.intrinsicDescriptor.values[name]?.let(::JsonPrimitive) ?: JsonNull) })
                        put("confidence",buildJsonObject { for (name in required) put(name,f.intrinsicDescriptor.featureConfidences[name] ?: 0.0) })
                    }
                }
                val row = buildJsonObject {
                    put("video",payload.getValue("video"));put("side",side.name);put("exercise",id)
                    put("status",if (result is QualifiedRepCompilation.Ready) "READY" else (result as QualifiedRepCompilation.Unavailable).reason.name)
                    put("detail",(result as? QualifiedRepCompilation.Unavailable)?.detail ?: "Ready")
                    put("diagnostic_self_gate_disabled",result is QualifiedRepCompilation.Unavailable && ref != null)
                    put("driver",required.last())
                    put("features",buildJsonObject { for (name in required) {
                        val samples = frames.filter { f -> (f.intrinsicDescriptor.featureConfidences[name] ?: 0.0) >= config.minimumConfidence }
                            .mapNotNull { it.intrinsicDescriptor.values[name] }
                        put(name,buildJsonObject {
                            put("valid_samples",samples.size);put("total_samples",frames.size)
                            put("p10",if (samples.isNotEmpty()) JsonPrimitive(quantile(samples,.1)) else JsonNull)
                            put("p90",if (samples.isNotEmpty()) JsonPrimitive(quantile(samples,.9)) else JsonNull)
                        })
                    } })
                    if (ref != null) {
                        put("ready_angle",ref.readyAngle);put("far_angle",ref.farAngle)
                        put("selected_cycles",ref.calibration?.observedCycles ?: 0)
                        put("self_accepted_cycles",ref.calibration?.acceptedSelfCycles ?: 0)
                        put("self_acceptance_fraction",ref.calibration?.selfAcceptanceFraction ?: 0.0)
                        put("selected_first_ms",ref.calibration?.firstSelectedMs ?: 0);put("selected_last_ms",ref.calibration?.lastSelectedMs ?: 0)
                    }
                    put("attempts",JsonArray(attempts))
                }
                rows += row
                File(output,"${file.name.removeSuffix(".json.gz")}-${side.name.lowercase()}.json").writeText(Json.encodeToString(JsonObject.serializer(),
                    JsonObject(row+("trace" to JsonArray(trace)))))
            }
        }
        File(output,"summary.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("kind","development-calibration-failure-diagnosis-not-independent-accuracy");put("rows",JsonArray(rows))
        }))
        println("Audited ${rows.size} distinct reference/side configurations; diagnostic counterfactuals cannot qualify a release")
    }
}
