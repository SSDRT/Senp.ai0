package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Explicit research slot projection. This is never an anatomical/MP33 shoulder feature. */
internal fun centralTorsoResearchProjection(values: Map<String,Double>, movement: String, candidate: Boolean): Map<String,Double> {
    if (!candidate || movement != "m07") return values
    return values.toMutableMap().apply {
        for (side in listOf("left","right")) {
            remove("angle.${side}_shoulder")
            values["angle.${side}_arm_central_torso"]?.let { put("angle.${side}_shoulder",it) }
            remove("angle.${side}_arm_central_torso")
        }
    }
}

/** Development only; continuous causal engine/default thresholds, no source label in any frame. */
object UiprmdCentralTorsoReplay {
    private fun sha(file: File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args[0]).canonicalFile
        val protocolFile=File(root,"models/uiprmd-central-torso-protocol.json")
        val protocol=Json.parseToJsonElement(protocolFile.readText()).jsonObject
        require(protocol.getValue("schema").jsonPrimitive.content=="uiprmd-central-torso-protocol/1")
        require(protocol.getValue("compiler").jsonPrimitive.content==QualifiedRepCompiler.VERSION)
        require(!protocol.getValue("allow_validation_access").jsonPrimitive.boolean && !protocol.getValue("allow_final_test_access").jsonPrimitive.boolean)
        for (item in protocol.getValue("receipts").jsonArray) {
            val row=item.jsonObject;val file=File(root,row.getValue("path").jsonPrimitive.content).canonicalFile
            require(file.toPath().startsWith(root.toPath()))
            val actual=if ("sha256_lf" in row) MessageDigest.getInstance("SHA-256").digest(file.readText().replace("\r\n","\n").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } else sha(file)
            require(actual==row.getValue(if ("sha256_lf" in row) "sha256_lf" else "sha256").jsonPrimitive.content)
        }
        val directory=File(root,"test-artifacts/exercise-knowledge/central-torso-driver-20261028/development")
        val manifest=Json.parseToJsonElement(File(directory,"manifest.json").readText()).jsonObject
        require(manifest.getValue("schema").jsonPrimitive.content=="uiprmd-central-torso-development-export/1")
        require(manifest.getValue("protocol_sha256").jsonPrimitive.content==sha(protocolFile))
        require(manifest.getValue("subjects_opened").jsonArray.map { it.jsonPrimitive.content }==listOf("01","02","03","04"))
        require(!manifest.getValue("validation_trajectories_opened").jsonPrimitive.boolean && !manifest.getValue("test_trajectories_opened").jsonPrimitive.boolean)
        val entries=manifest.getValue("records").jsonArray.map { it.jsonObject }
        val expected=listOf("01","02","03","04").flatMap { s -> listOf("m01","m07").flatMap { m -> listOf(true,false).map { Triple(s,m,it) } } }.toSet()
        require(entries.size==16 && entries.map { Triple(it.getValue("subject").jsonPrimitive.content,it.getValue("movement").jsonPrimitive.content,it.getValue("correct").jsonPrimitive.boolean) }.toSet()==expected)
        fun read(file: File, digest: String): JsonObject {
            require(file.canonicalFile.toPath().startsWith(root.toPath()) && sha(file)==digest)
            return Json.parseToJsonElement(file.readText()).jsonObject.also {
                require(it.getValue("subject").jsonPrimitive.content in setOf("01","02","03","04"))
                require(it.getValue("episodes").jsonArray.size==10 && it.getValue("sample_period_ms").jsonPrimitive.int==10)
            }
        }
        val candidate=entries.map { read(File(directory,it.getValue("file").jsonPrimitive.content),it.getValue("sha256").jsonPrimitive.content) }
        val baseline=entries.map { read(File(root,it.getValue("original_file").jsonPrimitive.content),it.getValue("original_sha256").jsonPrimitive.content) }
        candidate.zip(baseline).forEach { (a,b) ->
            for(key in listOf("episodes","subject","movement","exercise_id","side","correct","duration_ms","source_sha256")) require(a.getValue(key)==b.getValue(key))
            require(a.getValue("pipeline")==manifest.getValue("pipeline") && a.getValue("backend_sha256")==manifest.getValue("backend_sha256"))
            require(a.getValue("frames").jsonArray.size==b.getValue("frames").jsonArray.size)
            a.getValue("frames").jsonArray.zip(b.getValue("frames").jsonArray).forEach { (av,bv) ->
                require(av.jsonObject.getValue("timestamp_ms")==bv.jsonObject.getValue("timestamp_ms"))
                bv.jsonObject.getValue("values").jsonObject.forEach { (key,value) -> require(av.jsonObject.getValue("values").jsonObject[key]==value) }
            }
        }
        fun sequence(record: JsonObject, role: VideoRole, changed: Boolean): SpatialSequenceAnalysis {
            val movement=record.getValue("movement").jsonPrimitive.content
            val frames=record.getValue("frames").jsonArray.mapIndexed { index,item ->
                val row=item.jsonObject;val time=row.getValue("timestamp_ms").jsonPrimitive.long;require(time==index*10L)
                val values=centralTorsoResearchProjection(row.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.double },movement,changed)
                SpatialObservationFrame(TimestampMs(time),SpatialEvidenceKind.THREE_D,null,null,null,
                    SpatialIntrinsicDescriptor(values,if(values.isEmpty()) 0.0 else 1.0,values.mapValues { 1.0 }),0.0,
                    "uiprmd-s"+record.getValue("subject").jsonPrimitive.content,0)
            }
            return SpatialSequenceAnalysis(role,DurationMs(record.getValue("duration_ms").jsonPrimitive.long),ObservationSampling(inputNominalFramesPerSecond=100.0,analysisFramesPerSecond=100.0),frames,1.0)
        }
        for ((mode,records) in listOf("baseline" to baseline,"central_torso" to candidate)) {
            val output=File(directory.parentFile,"$mode-replay.json");check(!output.exists()) { "Preserve prior replay" }
            val rows=records.map { record ->
                val subject=record.getValue("subject").jsonPrimitive.content;val movement=record.getValue("movement").jsonPrimitive.content
                val referenceSubject=if(subject=="01") "02" else "01"
                val reference=records.single { it.getValue("subject").jsonPrimitive.content==referenceSubject && it.getValue("movement").jsonPrimitive.content==movement && it.getValue("correct").jsonPrimitive.boolean }
                val backend=record.getValue("backend_sha256").jsonPrimitive.content;val pipeline=record.getValue("pipeline").jsonPrimitive.content
                val fingerprint=RepEvidenceFingerprint(reference.getValue("source_sha256").jsonPrimitive.content,backend,pipeline,"vicon39-explicit-research-vector-driver")
                val compiled=QualifiedRepCompiler.compile(sequence(reference,VideoRole.REFERENCE,mode=="central_torso"),record.getValue("exercise_id").jsonPrimitive.content,fingerprint,QualifiedRepConfig(side=RepObservedSide.valueOf(record.getValue("side").jsonPrimitive.content)))
                buildJsonObject {
                    for(key in listOf("subject","movement","correct","episodes","source_sha256")) put(key,record.getValue(key))
                    put("reference_subject",referenceSubject);put("reference_sha256",reference.getValue("source_sha256"))
                    if(compiled is QualifiedRepCompilation.Unavailable) {
                        put("status","REFERENCE_UNAVAILABLE");put("failure",compiled.reason.name);put("detail",compiled.detail);put("attempts",JsonArray(emptyList()))
                    } else {
                        val ready=(compiled as QualifiedRepCompilation.Ready).reference;val engine=ready.newEngine();val attempts=mutableListOf<RepAttemptResult>()
                        val source=sequence(record,VideoRole.SOURCE,mode=="central_torso")
                        source.frames.forEach { attempts+=engine.evaluate(it,backend,pipeline,fingerprint.declaredView).finalizedAttempts }
                        attempts+=engine.finish(source.duration.value).finalizedAttempts
                        put("status","REPLAYED");put("reference_key",ready.referenceKey);put("reference_self_cycles",ready.calibration?.acceptedSelfCycles ?: 0)
                        put("attempts",JsonArray(attempts.map { a -> buildJsonObject {
                            put("attempt_id",a.attemptId);put("started_ms",a.startedMs);put("completed_ms",a.completedMs);put("verdict",a.verdict.name)
                            put("maximum_progress",a.maximumProgress);put("visible_fraction",a.visibleFraction);put("ambiguous_fraction",a.ambiguousFraction)
                            put("reasons",JsonArray(a.reasons.map { JsonPrimitive(it.name) }))
                            put("faults",JsonArray(a.faults.map { f -> buildJsonObject { put("reason",f.reason.name);put("feature",f.feature);put("first_ms",f.firstEvidenceMs);put("confirmed_ms",f.confirmedMs) } }))
                        } }))
                    }
                }
            }
            output.writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),buildJsonObject {
                put("schema","uiprmd-central-torso-replay/1");put("mode",mode);put("compiler",QualifiedRepCompiler.VERSION);put("protocol_sha256",sha(protocolFile))
                put("kind","development-only-skeletal-research-not-human-live-accuracy");put("rows",JsonArray(rows))
            }))
            println("$mode: ${rows.count { it.getValue("status").jsonPrimitive.content=="REPLAYED" }}/16 recordings replayed")
        }
    }
}
