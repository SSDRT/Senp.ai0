package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Independent skeletal labels never enter the engine. No fake MP33/confidence/weights. */
object UiprmdDevelopmentReplay {
    private val json = Json { prettyPrint = true }
    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    @JvmStatic fun main(args: Array<String>) {
        val directory = File(args[0]);val output = File(args[1])
        val manifest = Json.parseToJsonElement(File(directory,"manifest.json").readText()).jsonObject
        require(manifest.getValue("schema").jsonPrimitive.content == "uiprmd-development-export/1")
        require(manifest.getValue("subjects_opened").jsonArray.map { it.jsonPrimitive.content }.toSet() == setOf("01","02","03","04"))
        require(!manifest.getValue("validation_trajectories_opened").jsonPrimitive.boolean)
        require(!manifest.getValue("test_trajectories_opened").jsonPrimitive.boolean)
        val backend = manifest.getValue("backend_sha256").jsonPrimitive.content
        val pipeline = manifest.getValue("pipeline").jsonPrimitive.content
        require(pipeline == "uiprmd-vicon39-marker-angle/1")
        val records = manifest.getValue("records").jsonArray.map { item ->
            val row = item.jsonObject
            val file = File(directory,row.getValue("file").jsonPrimitive.content)
            require(file.canonicalFile.parentFile == directory.canonicalFile && sha(file) == row.getValue("sha256").jsonPrimitive.content)
            Json.parseToJsonElement(file.readText()).jsonObject.also { r ->
                require(r.getValue("subject").jsonPrimitive.content in setOf("01","02","03","04"))
                require(r.getValue("backend_sha256").jsonPrimitive.content == backend && r.getValue("pipeline").jsonPrimitive.content == pipeline)
                require(r.getValue("sample_period_ms").jsonPrimitive.int == 10)
            }
        }
        require(records.size == 16)
        fun sequence(record: JsonObject, role: VideoRole): SpatialSequenceAnalysis {
            val frames = record.getValue("frames").jsonArray.mapIndexed { index,item ->
                val row = item.jsonObject;val time = row.getValue("timestamp_ms").jsonPrimitive.long
                require(time == index*10L)
                val values = row.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.double }
                SpatialObservationFrame(TimestampMs(time),SpatialEvidenceKind.THREE_D,null,null,null,
                    // Binary acquisition observability, not MediaPipe probability or expert form certainty.
                    SpatialIntrinsicDescriptor(values,if (values.isEmpty()) 0.0 else 1.0,values.mapValues { 1.0 }),0.0,
                    "uiprmd-s"+record.getValue("subject").jsonPrimitive.content,0)
            }
            return SpatialSequenceAnalysis(role,DurationMs(record.getValue("duration_ms").jsonPrimitive.long),
                ObservationSampling(inputNominalFramesPerSecond = 100.0,analysisFramesPerSecond = 100.0),frames,1.0)
        }
        val rows = records.map { record ->
            val subject = record.getValue("subject").jsonPrimitive.content
            val movement = record.getValue("movement").jsonPrimitive.content
            // Fixed cross-person reference, chosen without source outcomes. Final subjects will use s01 only.
            val referenceSubject = if (subject == "01") "02" else "01"
            val reference = records.single { it.getValue("subject").jsonPrimitive.content == referenceSubject &&
                it.getValue("movement").jsonPrimitive.content == movement && it.getValue("correct").jsonPrimitive.boolean }
            val fingerprint = RepEvidenceFingerprint(reference.getValue("source_sha256").jsonPrimitive.content,backend,pipeline,"vicon39-global-marker-proxies")
            val config = QualifiedRepConfig(side = RepObservedSide.valueOf(record.getValue("side").jsonPrimitive.content))
            val compiled = QualifiedRepCompiler.compile(sequence(reference,VideoRole.REFERENCE),record.getValue("exercise_id").jsonPrimitive.content,fingerprint,config)
            buildJsonObject {
                for (key in listOf("subject","movement","exercise_id","correct","side","source_member","source_sha256","episodes")) put(key,record.getValue(key))
                put("reference_subject",referenceSubject)
                put("reference_sha256",reference.getValue("source_sha256"))
                if (compiled is QualifiedRepCompilation.Unavailable) {
                    put("status","REFERENCE_UNAVAILABLE");put("failure",compiled.reason.name);put("detail",compiled.detail)
                    put("attempts",JsonArray(emptyList()))
                } else {
                    val ready = (compiled as QualifiedRepCompilation.Ready).reference
                    val engine = ready.newEngine();val attempts = mutableListOf<RepAttemptResult>()
                    sequence(record,VideoRole.SOURCE).let { source ->
                        for (frame in source.frames) attempts += engine.evaluate(frame,backend,pipeline,fingerprint.declaredView).finalizedAttempts
                        attempts += engine.finish(source.duration.value).finalizedAttempts
                    }
                    put("status","REPLAYED");put("reference_key",ready.referenceKey)
                    put("reference_self_cycles",ready.calibration?.acceptedSelfCycles ?: 0)
                    put("attempts",JsonArray(attempts.map { a -> buildJsonObject {
                        put("attempt_id",a.attemptId);put("started_ms",a.startedMs);put("completed_ms",a.completedMs)
                        put("verdict",a.verdict.name);put("maximum_progress",a.maximumProgress)
                        put("visible_fraction",a.visibleFraction);put("ambiguous_fraction",a.ambiguousFraction)
                        put("reasons",JsonArray(a.reasons.map { JsonPrimitive(it.name) }))
                        put("faults",JsonArray(a.faults.map { f -> buildJsonObject {
                            put("reason",f.reason.name);put("feature",f.feature);put("first_ms",f.firstEvidenceMs);put("confirmed_ms",f.confirmedMs)
                        } }))
                    } }))
                }
            }
        }
        val report = buildJsonObject {
            put("schema","uiprmd-development-replay/1");put("measurement_domain","skeletal")
            put("kind","development-only-independent-label-diagnostic-not-held-out-accuracy")
            put("manifest_sha256",sha(File(directory,"manifest.json")));put("compiler",QualifiedRepCompiler.VERSION)
            put("raw_landmark_network_trained",false);put("config","production-defaults-reference-match")
            put("rows",JsonArray(rows))
        }
        output.parentFile.mkdirs();output.writeText(json.encodeToString(JsonObject.serializer(),report))
        println("UI-PRMD development: ${rows.count { it.getValue("status").jsonPrimitive.content == "REPLAYED" }}/${rows.size} recordings replayed; $output")
    }
}
