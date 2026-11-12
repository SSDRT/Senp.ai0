package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*

/** Reference-only diagnosis. Does not promote a candidate or change production reference selection. */
object QualifiedRepReferenceContextAudit {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0]);val output = File(args[1]);val rows = mutableListOf<JsonObject>()
        val spatial = SpatialSynchronizationEngine(SpatialSynchronizationConfig(includeWorkoutFeatures = true))
        val ids = mapOf("squat" to "bodyweight_squat","push_up" to "push_up","bicep_curl" to "biceps_curl","shoulder" to "overhead_press")
        for (model in listOf("full","heavy")) for (file in File(root,"complete-reference-$model-cache").listFiles()!!.sortedBy { it.name }) {
            val payload = GZIPInputStream(file.inputStream()).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            val observations = payload.getValue("frames").jsonArray.map { QualifiedRepReplayTool.observation(it.jsonObject) }
            val sequence = spatial.analyzeSequence(CanonicalObservationSequence(VideoRole.REFERENCE,DurationMs(observations.last().timestamp.value+1),
                ObservationSampling(analysisFramesPerSecond = 15.0),observations))
            val contexts = sequence.frames.filter { it.evidenceKind == SpatialEvidenceKind.THREE_D && it.selectedSubjectId != null }
                .groupingBy { it.selectedSubjectId to it.spatialSegmentId }.eachCount()
            val identity = RepEvidenceFingerprint(payload.getValue("source_video_sha256").jsonPrimitive.content,
                payload.getValue("model_sha256").jsonPrimitive.content,declaredView = payload.getValue("view").jsonPrimitive.content)
            for (side in RepObservedSide.entries) {
                val result = QualifiedRepCompiler.compile(sequence,ids.getValue(payload.getValue("exercise").jsonPrimitive.content),identity,QualifiedRepConfig(side = side))
                rows += buildJsonObject {
                    put("video",payload.getValue("video"));put("model",model);put("side",side.name)
                    put("status",if (result is QualifiedRepCompilation.Ready) "READY" else (result as QualifiedRepCompilation.Unavailable).reason.name)
                    put("contexts",buildJsonArray { contexts.forEach { (context,count) -> add(buildJsonObject {
                        put("subject",context.first);put("segment",context.second?.let(::JsonPrimitive) ?: JsonNull);put("observations",count)
                    }) } })
                    put("context_count",contexts.size)
                }
            }
        }
        val data = QualifiedRepTestData
        val synthetic = (0 until 4).flatMap { rep -> data.stream(offset = rep*2100L).map { (time,p) ->
            data.frame(time,p).copy(selectedSubjectId = "different-person-$rep",spatialSegmentId = rep)
        } }
        val source = SpatialSequenceAnalysis(VideoRole.REFERENCE,DurationMs(synthetic.last().timestamp.value+1),ObservationSampling(analysisFramesPerSecond = 30.0),synthetic,1.0)
        val pooled = QualifiedRepCompiler.compile(source,"biceps_curl",data.identity)
        output.parentFile.mkdirs();output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("kind","reference-context-diagnosis-not-accuracy-or-production-promotion")
            put("independent_subject_cycles_pooled",pooled is QualifiedRepCompilation.Ready)
            put("independent_subjects",4);put("cycles_per_subject",1);put("required_reference_cycles",3)
            put("real_reference_configurations",rows.size);put("rows",JsonArray(rows))
        }))
        println("Reference context audit ${rows.size} model/reference/side configurations; different-person cycles pooled: ${pooled is QualifiedRepCompilation.Ready}")
    }
}
