package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.*

/** Diagnostics only, on previously opened splits. Final-test scope is not an option. */
object UiprmdEvidenceAudit {
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun sequence(record: JsonObject, role: VideoRole): SpatialSequenceAnalysis {
        val frames = record.getValue("frames").jsonArray.mapIndexed { i,item ->
            val row = item.jsonObject;val time = row.getValue("timestamp_ms").jsonPrimitive.long
            require(time == i*10L)
            val values = row.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.double }
            SpatialObservationFrame(TimestampMs(time),SpatialEvidenceKind.THREE_D,null,null,null,
                SpatialIntrinsicDescriptor(values,if (values.isEmpty()) 0.0 else 1.0,values.mapValues { 1.0 }),0.0,
                "uiprmd-s"+record.getValue("subject").jsonPrimitive.content,0)
        }
        return SpatialSequenceAnalysis(role,DurationMs(record.getValue("duration_ms").jsonPrimitive.long),
            ObservationSampling(inputNominalFramesPerSecond = 100.0,analysisFramesPerSecond = 100.0),frames,1.0)
    }
    internal fun validationScope(scope: String): Boolean {
        require(scope in setOf("development","validation")) { "Final-test trajectories are forbidden" }
        return scope == "validation"
    }
    @JvmStatic fun main(args: Array<String>) {
        val scope = args[0];val validation = validationScope(scope) // Before opening a directory or file.
        val root = File(args[1]);val output = File(args[2]);output.mkdirs()
        fun manifest(split: String): Pair<File,JsonObject> {
            val path = File(root,"$split-vicon/manifest.json")
            val data = Json.parseToJsonElement(path.readText()).jsonObject
            UiprmdSkeletalReplay.verifySubjects(data,split == "validation")
            require(data.getValue("pipeline").jsonPrimitive.content == "uiprmd-vicon39-marker-angle/1")
            return path to data
        }
        val (sourceManifest,sourceIndex) = manifest(scope)
        val (referenceManifest,referenceIndex) = manifest("development")
        val backend = sourceIndex.getValue("backend_sha256").jsonPrimitive.content
        val pipeline = sourceIndex.getValue("pipeline").jsonPrimitive.content
        require(referenceIndex.getValue("backend_sha256").jsonPrimitive.content == backend)
        val reads = mutableListOf<JsonObject>()
        fun readRecord(split: String, row: JsonObject): JsonObject {
            val directory = File(root,"$split-vicon").canonicalFile
            val path = File(directory,row.getValue("file").jsonPrimitive.content).canonicalFile
            require(path.parentFile == directory && sha(path) == row.getValue("sha256").jsonPrimitive.content)
            val record = Json.parseToJsonElement(path.readText()).jsonObject
            for (key in listOf("subject","movement","correct")) require(record.getValue(key) == row.getValue(key))
            require(record.getValue("pipeline").jsonPrimitive.content == pipeline && record.getValue("backend_sha256").jsonPrimitive.content == backend)
            require(record.getValue("sample_period_ms").jsonPrimitive.int == 10)
            reads += buildJsonObject { put("split",split);put("file",path.name);put("sha256",sha(path)) }
            return record
        }
        val sources = sourceIndex.getValue("records").jsonArray.map { readRecord(scope,it.jsonObject) }
        val references = referenceIndex.getValue("records").jsonArray.map { it.jsonObject }.filter {
            it.getValue("subject").jsonPrimitive.content in setOf("01","02") && it.getValue("correct").jsonPrimitive.boolean
        }.map { readRecord("development",it) }
        val reports = mutableListOf<JsonObject>()
        for (source in sources) {
            val subject = source.getValue("subject").jsonPrimitive.content
            val movement = source.getValue("movement").jsonPrimitive.content
            val referenceSubject = if (!validation && subject == "01") "02" else "01"
            val record = references.single { it.getValue("subject").jsonPrimitive.content == referenceSubject && it.getValue("movement").jsonPrimitive.content == movement }
            val fingerprint = RepEvidenceFingerprint(record.getValue("source_sha256").jsonPrimitive.content,backend,pipeline,"vicon39-global-marker-proxies")
            val reference = (QualifiedRepCompiler.compile(sequence(record,VideoRole.REFERENCE),source.getValue("exercise_id").jsonPrimitive.content,
                fingerprint,QualifiedRepConfig(side = RepObservedSide.valueOf(source.getValue("side").jsonPrimitive.content))) as QualifiedRepCompilation.Ready).reference
            val samples = mutableListOf<JsonObject>()
            val engine = QualifiedRepEngine(reference) { e -> samples += buildJsonObject {
                put("timestamp_ms",e.timestampMs);put("attempt_id",e.attemptId);put("progress",e.driverProgress)
                put("uses_return_branch",e.comparisonUsesReturnBranch);put("reversal_confirmed",e.reversalConfirmed)
                put("far_endpoint_previously_observed",e.farEndpointPreviouslyObserved)
                put("features",JsonArray(e.features.map { f -> buildJsonObject {
                    put("reference_feature",f.referenceFeature);put("observed_feature",f.observedFeature)
                    put("raw_degrees",f.rawDegrees);put("confidence",f.confidence);put("observed_degrees",f.observedDegrees)
                    put("lower_degrees",f.lowerDegrees);put("upper_degrees",f.upperDegrees)
                    put("nominal_degrees",f.nominalAllowanceDegrees);put("acceptable_degrees",f.acceptableAllowanceDegrees)
                    put("reject_degrees",f.rejectAllowanceDegrees);put("deviation_degrees",f.deviationDegrees);put("rule_id",f.ruleId)
                } }))
            } }
            val ordinary = reference.newEngine();val attempts = mutableListOf<RepAttemptResult>()
            val sourceSequence = sequence(source,VideoRole.SOURCE)
            for (frame in sourceSequence.frames) {
                val result = engine.evaluate(frame,backend,pipeline,fingerprint.declaredView)
                check(result == ordinary.evaluate(frame,backend,pipeline,fingerprint.declaredView)) { "Diagnostic observer changed an output" }
                attempts += result.finalizedAttempts
            }
            val ended = engine.finish(sourceSequence.duration.value)
            check(ended == ordinary.finish(sourceSequence.duration.value))
            attempts += ended.finalizedAttempts
            val report = buildJsonObject {
                put("schema","uiprmd-reference-evidence-audit-record/1");put("scope",scope)
                for (key in listOf("subject","movement","exercise_id","correct","side","source_member","source_sha256","episodes")) put(key,source.getValue(key))
                put("reference_subject",referenceSubject);put("reference_sha256",record.getValue("source_sha256"))
                put("reference_key",reference.referenceKey);put("driver_feature",reference.driverFeature)
                put("required_progress",reference.config.requiredProgress);put("minimum_confidence",reference.config.minimumConfidence)
                put("comparison_outputs_identical_with_and_without_observer",true)
                put("attempts",JsonArray(attempts.map { a -> buildJsonObject {
                    put("attempt_id",a.attemptId);put("started_ms",a.startedMs);put("completed_ms",a.completedMs)
                    put("verdict",a.verdict.name);put("maximum_progress",a.maximumProgress)
                    put("visible_fraction",a.visibleFraction);put("ambiguous_fraction",a.ambiguousFraction)
                    put("reasons",JsonArray(a.reasons.map { JsonPrimitive(it.name) }))
                } }))
                put("samples",JsonArray(samples))
            }
            val filename = "$movement-s$subject-${if (source.getValue("correct").jsonPrimitive.boolean) "correct" else "incorrect"}.json.gz"
            val file = File(output,filename)
            GZIPOutputStream(file.outputStream()).bufferedWriter().use { it.write(report.toString()) }
            reports += buildJsonObject { put("file",filename);put("sha256",sha(file));put("subject",subject);put("movement",movement);put("correct",source.getValue("correct")) }
        }
        val report = buildJsonObject {
            put("schema","uiprmd-reference-evidence-audit/1");put("kind","diagnostic-not-new-labels-or-tuning-candidate")
            put("scope",scope);put("compiler",QualifiedRepCompiler.VERSION);put("measurement_domain","skeletal")
            put("confidence_semantics","binary original-marker observability, not MediaPipe probability")
            put("final_test_trajectories_opened",false);put("production_acceptance_policy_changed",false)
            put("source_manifest_sha256",sha(sourceManifest));put("reference_manifest_sha256",sha(referenceManifest))
            put("reads",JsonArray(reads));put("records",JsonArray(reports))
        }
        File(output,"manifest.json").writeText(report.toString()+"\n")
        println("Evidence audit $scope: ${reports.size} recordings; all observed/unobserved outputs identical; final test unopened")
    }
}
