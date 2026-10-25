package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Reference-only research input; never opens a validation or final-test trajectory. */
object UiprmdPlaneReferenceInputs {
    private fun sha(path: File) = MessageDigest.getInstance("SHA-256").digest(path.readBytes()).joinToString("") { "%02x".format(it) }
    @JvmStatic fun main(args: Array<String>) {
        val directory = File(args[0]).canonicalFile
        val manifestFile = File(directory,"manifest.json")
        val manifest = Json.parseToJsonElement(manifestFile.readText()).jsonObject
        UiprmdSkeletalReplay.verifySubjects(manifest,false)
        val backend = manifest.getValue("backend_sha256").jsonPrimitive.content
        val pipeline = manifest.getValue("pipeline").jsonPrimitive.content
        require(pipeline == "uiprmd-vicon39-marker-angle/1")
        val references = manifest.getValue("records").jsonArray.map { it.jsonObject }.filter {
            it.getValue("subject").jsonPrimitive.content in setOf("01","02") &&
                it.getValue("movement").jsonPrimitive.content == "m07" && it.getValue("correct").jsonPrimitive.boolean
        }.map { entry ->
            val path = File(directory,entry.getValue("file").jsonPrimitive.content).canonicalFile
            require(path.parentFile == directory && sha(path) == entry.getValue("sha256").jsonPrimitive.content)
            val record = Json.parseToJsonElement(path.readText()).jsonObject
            for (key in listOf("subject","movement","correct")) require(record.getValue(key) == entry.getValue(key))
            require(record.getValue("pipeline").jsonPrimitive.content == pipeline && record.getValue("backend_sha256").jsonPrimitive.content == backend)
            val frames = record.getValue("frames").jsonArray.mapIndexed { i,item ->
                val row = item.jsonObject;val time = row.getValue("timestamp_ms").jsonPrimitive.long
                require(time == i*10L)
                val values = row.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.double }
                SpatialObservationFrame(TimestampMs(time),SpatialEvidenceKind.THREE_D,null,null,null,
                    SpatialIntrinsicDescriptor(values,if (values.isEmpty()) 0.0 else 1.0,values.mapValues { 1.0 }),0.0,
                    "uiprmd-s"+record.getValue("subject").jsonPrimitive.content,0)
            }
            val sequence = SpatialSequenceAnalysis(VideoRole.REFERENCE,DurationMs(record.getValue("duration_ms").jsonPrimitive.long),
                ObservationSampling(inputNominalFramesPerSecond = 100.0,analysisFramesPerSecond = 100.0),frames,1.0)
            val fingerprint = RepEvidenceFingerprint(record.getValue("source_sha256").jsonPrimitive.content,backend,pipeline,"vicon39-global-marker-proxies")
            val compiled = QualifiedRepCompiler.compile(sequence,"lateral_raise",fingerprint,QualifiedRepConfig(side = RepObservedSide.RIGHT))
            val reference = (compiled as QualifiedRepCompilation.Ready).reference
            val samples = mutableListOf<JsonObject>();val attempts = mutableListOf<RepAttemptResult>()
            val engine = QualifiedRepEngine(reference) { evidence -> samples += buildJsonObject {
                put("timestamp_ms",evidence.timestampMs);put("attempt_id",evidence.attemptId)
                put("progress",evidence.driverProgress);put("uses_return_branch",evidence.comparisonUsesReturnBranch)
            } }
            for (frame in frames) attempts += engine.evaluate(frame,backend,pipeline,fingerprint.declaredView).finalizedAttempts
            attempts += engine.finish(sequence.duration.value).finalizedAttempts
            val acceptedIds = attempts.filter { it.verdict in setOf(RepVerdict.CORRECT,RepVerdict.ACCEPTABLE) }.map { it.attemptId }.toSet()
            require(acceptedIds.size >= reference.config.minimumReferenceCycles)
            buildJsonObject {
                put("subject",record.getValue("subject"));put("source_member",record.getValue("source_member"))
                put("source_sha256",record.getValue("source_sha256"));put("export_sha256",sha(path))
                put("reference_key",reference.referenceKey);put("accepted_self_attempts",acceptedIds.size)
                put("self_replay_is_not_human_correctness",true)
                put("samples",JsonArray(samples.filter { it.getValue("attempt_id").jsonPrimitive.long in acceptedIds }))
            }
        }
        require(references.size == 2)
        val report = buildJsonObject {
            put("schema","uiprmd-plane-reference-inputs/1");put("compiler",QualifiedRepCompiler.VERSION)
            put("development_manifest_sha256",sha(manifestFile));put("validation_trajectories_opened",false)
            put("final_test_trajectories_opened",false);put("references",JsonArray(references))
        }
        File(args[1]).writeText(report.toString()+"\n")
        println("Exported two development-only reference self-replay inputs for the plane-guard experiment")
    }
}
