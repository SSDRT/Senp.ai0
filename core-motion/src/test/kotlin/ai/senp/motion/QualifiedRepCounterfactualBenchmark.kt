package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*

/** Controlled feature corruption over real timestamps. Mutations are explicit test oracles, not human labels. */
object QualifiedRepCounterfactualBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0]);val output = File(args[1])
        val spatial = SpatialSynchronizationEngine(SpatialSynchronizationConfig(includeWorkoutFeatures = true))
        data class Video(val payload: JsonObject,val sequence: SpatialSequenceAnalysis)
        val cache = mutableMapOf<String,Video>()
        fun read(directory: String,name: String,role: VideoRole): Video = cache.getOrPut("$directory/$name/$role") {
            val payload = GZIPInputStream(File(root,"$directory/$name.json.gz").inputStream()).bufferedReader()
                .use { Json.parseToJsonElement(it.readText()).jsonObject }
            val observations = payload.getValue("frames").jsonArray.map { QualifiedRepReplayTool.observation(it.jsonObject) }
            Video(payload,spatial.analyzeSequence(CanonicalObservationSequence(role,DurationMs(observations.last().timestamp.value+1),
                ObservationSampling(analysisFramesPerSecond = 15.0),observations)))
        }
        val registry = Json.parseToJsonElement(File(root,"multipass-summary/complete-reference-backend-selection.json").readText()).jsonArray
        val rows = mutableListOf<JsonObject>()
        var originalAccepted = 0
        for (entry in registry) {
            val choice = entry.jsonObject;val model = choice.getValue("selected_model").jsonPrimitive.content
            if (model == "unavailable") continue
            val sha = choice.getValue("reference_video_sha256").jsonPrimitive.content
            val referenceDirectory = "complete-reference-$model-cache"
            val refFile = File(root,referenceDirectory).listFiles()!!.single { file ->
                GZIPInputStream(file.inputStream()).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
                    .getValue("source_video_sha256").jsonPrimitive.content == sha
            }
            val refVideo = read(referenceDirectory,refFile.name.removeSuffix(".json.gz"),VideoRole.REFERENCE)
            val identity = RepEvidenceFingerprint(sha,refVideo.payload.getValue("model_sha256").jsonPrimitive.content,
                declaredView = choice.getValue("view").jsonPrimitive.content)
            val config = QualifiedRepConfig(side = RepObservedSide.valueOf(choice.getValue("side").jsonPrimitive.content),
                referenceCurveFit = RepReferenceCurveFit.valueOf(choice.getValue("selected_fit").jsonPrimitive.content))
            val reference = (QualifiedRepCompiler.compile(refVideo.sequence,choice.getValue("exercise").jsonPrimitive.content,identity,config) as QualifiedRepCompilation.Ready).reference
            val reportDirectory = "multipass-complete-$model-${if (config.referenceCurveFit == RepReferenceCurveFit.ORDINARY_LEAST_SQUARES) "standard" else "robust"}"
            val reports = Json.parseToJsonElement(File(root,"$reportDirectory/summary.json").readText()).jsonObject.getValue("rows").jsonArray
                .map { it.jsonObject }.filter { it["reference_key"]?.jsonPrimitive?.content == reference.referenceKey }
            for (report in reports) {
                val name = report.getValue("video").jsonPrimitive.content.removeSuffix(".mp4")
                val source = read(if (model == "full") "rep-world-feature-cache" else "autonomous-heavy-cache",name,VideoRole.SOURCE)
                val targets = report.getValue("attempts").jsonArray.map { it.jsonObject }
                    .filter { it.getValue("verdict").jsonPrimitive.content in setOf("CORRECT","ACCEPTABLE") }
                originalAccepted += targets.size
                for (target in targets) for (mutation in listOf("severe_quality","missing_driver","model_mismatch","shallow_range")) {
                    val start = target.getValue("started_ms").jsonPrimitive.long
                    val end = target.getValue("completed_ms").jsonPrimitive.long
                    val quality = reference.requiredFeatures.first { it != reference.driverFeature }
                    val engine = reference.newEngine();val events = mutableListOf<RepAttemptResult>()
                    for (frame in source.sequence.frames) {
                        val inside = frame.timestamp.value in start..end
                        val changed = if (!inside) frame else {
                            val values = frame.intrinsicDescriptor.values.toMutableMap()
                            val confidences = frame.intrinsicDescriptor.featureConfidences.toMutableMap()
                            if (mutation == "missing_driver") confidences[reference.driverFeature] = 0.0
                            if (mutation == "shallow_range") {
                                val angle = values.getValue(reference.driverFeature)
                                values[reference.driverFeature] = reference.readyAngle+(angle-reference.readyAngle)*.45
                            }
                            if (mutation == "severe_quality") {
                                val p = (values.getValue(reference.driverFeature)-reference.readyAngle)/(reference.farAngle-reference.readyAngle)
                                val bands = listOf(false,true).map { reference.evidenceBand(quality,p,it) }
                                values[quality] = listOf(0.0,180.0).maxBy { v -> bands.minOf { b -> maxOf(b.lower-v,v-b.upper,0.0)-b.reject } }
                            }
                            frame.copy(intrinsicDescriptor = frame.intrinsicDescriptor.copy(values = values,featureConfidences = confidences))
                        }
                        events += engine.evaluate(changed,if (inside && mutation == "model_mismatch") "f".repeat(64) else identity.poseModelSha256,
                            captureContext = identity.declaredView).finalizedAttempts
                    }
                    events += engine.finish(source.sequence.duration.value).finalizedAttempts
                    val accepted = events.filter { it.verdict in setOf(RepVerdict.CORRECT,RepVerdict.ACCEPTABLE) }
                    val overlap = accepted.count { it.startedMs < end && it.completedMs > start }
                    val passed = overlap == 0 && accepted.size <= targets.size-1
                    rows += buildJsonObject {
                        put("video",name);put("reference_key",reference.referenceKey);put("side",config.side.name)
                        put("target_start_ms",start);put("target_end_ms",end);put("mutation",mutation)
                        put("overlapping_accepted",overlap);put("baseline_accepted",targets.size);put("mutated_accepted",accepted.size)
                        put("target_rejected",events.any { it.verdict == RepVerdict.REJECTED && it.startedMs < end && it.completedMs > start })
                        put("passed",passed)
                    }
                }
            }
        }
        val failures = rows.count { !it.getValue("passed").jsonPrimitive.boolean }
        output.parentFile.mkdirs();output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("kind","controlled-feature-corruption-over-original-video-timestamps-not-human-accuracy")
            put("original_accepted_outputs",originalAccepted);put("scenario_count",rows.size);put("failures",failures);put("rows",JsonArray(rows))
        }))
        println("Real-timestamp controlled corruptions ${rows.size}; failures $failures; original accepted $originalAccepted")
        check(originalAccepted == 31 && failures == 0)
    }
}
