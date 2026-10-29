package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.*

/** Passive diagnostics of the frozen failed projection. No fitting or policy mutation. */
object UiprmdFrontalAudit {
    private fun sha(file: File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun attempts(rows: List<RepAttemptResult>)=JsonArray(rows.map { a -> buildJsonObject {
        put("attempt_id",a.attemptId);put("started_ms",a.startedMs);put("completed_ms",a.completedMs);put("verdict",a.verdict.name)
        put("maximum_progress",a.maximumProgress);put("visible_fraction",a.visibleFraction);put("ambiguous_fraction",a.ambiguousFraction)
        put("reasons",JsonArray(a.reasons.map { JsonPrimitive(it.name) }))
        put("faults",JsonArray(a.faults.map { f -> buildJsonObject { put("reason",f.reason.name);put("feature",f.feature);put("first_ms",f.firstEvidenceMs);put("confirmed_ms",f.confirmedMs) } }))
    } })
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args[0]).canonicalFile;val protocol=File(root,"models/uiprmd-frontal-audit-protocol.json")
        val spec=Json.parseToJsonElement(protocol.readText()).jsonObject
        require(spec.getValue("schema").jsonPrimitive.content=="uiprmd-frontal-audit-protocol/1")
        require(spec.getValue("subjects").jsonArray.map { it.jsonPrimitive.content }==listOf("01","02","03","04"))
        require(!spec.getValue("allow_validation_access").jsonPrimitive.boolean && !spec.getValue("allow_final_test_access").jsonPrimitive.boolean)
        require(spec.getValue("compiler").jsonPrimitive.content==QualifiedRepCompiler.VERSION)
        for(item in spec.getValue("receipts").jsonArray) {
            val row=item.jsonObject;val file=File(root,row.getValue("path").jsonPrimitive.content).canonicalFile
            require(file.toPath().startsWith(root.toPath()))
            val actual=if("sha256_lf" in row) MessageDigest.getInstance("SHA-256").digest(file.readText().replace("\r\n","\n").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } else sha(file)
            require(actual==row.getValue(if("sha256_lf" in row) "sha256_lf" else "sha256").jsonPrimitive.content)
        }
        val input=File(root,"test-artifacts/exercise-knowledge/frontal-arm-projection-20261029")
        val manifest=Json.parseToJsonElement(File(input,"development/manifest.json").readText()).jsonObject
        require(manifest.getValue("subjects_opened").jsonArray.map { it.jsonPrimitive.content }==listOf("01","02","03","04"))
        require(!manifest.getValue("validation_trajectories_opened").jsonPrimitive.boolean && !manifest.getValue("test_trajectories_opened").jsonPrimitive.boolean)
        val expected=listOf("01","02","03","04").flatMap { s -> listOf("m01","m07").flatMap { m -> listOf(true,false).map { Triple(s,m,it) } } }.toSet()
        val entries=manifest.getValue("records").jsonArray.map { it.jsonObject }
        require(entries.size==16 && entries.map { Triple(it.getValue("subject").jsonPrimitive.content,it.getValue("movement").jsonPrimitive.content,it.getValue("correct").jsonPrimitive.boolean) }.toSet()==expected)
        val records=entries.map { entry ->
            val name=entry.getValue("file").jsonPrimitive.content
            require(name=="${entry.getValue("movement").jsonPrimitive.content}-s${entry.getValue("subject").jsonPrimitive.content}-${if(entry.getValue("correct").jsonPrimitive.boolean) "correct" else "incorrect"}.json")
            val file=File(input,"development/$name").canonicalFile
            require(file.parentFile==File(input,"development").canonicalFile && sha(file)==entry.getValue("sha256").jsonPrimitive.content)
            Json.parseToJsonElement(file.readText()).jsonObject.also { row ->
                for(key in listOf("subject","movement","correct")) require(row.getValue(key)==entry.getValue(key))
                require(row.getValue("episodes").jsonArray.size==10 && row.getValue("sample_period_ms").jsonPrimitive.int==10)
                require(row.getValue("pipeline")==manifest.getValue("pipeline") && row.getValue("backend_sha256")==manifest.getValue("backend_sha256"))
            }
        }
        fun sequence(record: JsonObject,role: VideoRole): SpatialSequenceAnalysis {
            val frames=record.getValue("frames").jsonArray.mapIndexed { i,item ->
                val row=item.jsonObject;require(row.getValue("timestamp_ms").jsonPrimitive.long==i*10L)
                val values=frontalArmResearchProjection(row.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.double },record.getValue("movement").jsonPrimitive.content,true)
                SpatialObservationFrame(TimestampMs(i*10L),SpatialEvidenceKind.THREE_D,null,null,null,SpatialIntrinsicDescriptor(values,if(values.isEmpty()) 0.0 else 1.0,values.mapValues { 1.0 }),0.0,"uiprmd-s"+record.getValue("subject").jsonPrimitive.content,0)
            }
            return SpatialSequenceAnalysis(role,DurationMs(record.getValue("duration_ms").jsonPrimitive.long),ObservationSampling(inputNominalFramesPerSecond=100.0,analysisFramesPerSecond=100.0),frames,1.0)
        }
        val prior=Json.parseToJsonElement(File(input,"frontal_arm-replay.json").readText()).jsonObject.getValue("rows").jsonArray.map { it.jsonObject }
        val output=File(root,"test-artifacts/exercise-knowledge/frontal-arm-audit-20261029");check(!output.exists());output.mkdirs();val reports=mutableListOf<JsonObject>()
        for(source in records) {
            val subject=source.getValue("subject").jsonPrimitive.content;val movement=source.getValue("movement").jsonPrimitive.content
            val refSubject=if(subject=="01") "02" else "01"
            val ref=records.single { it.getValue("subject").jsonPrimitive.content==refSubject && it.getValue("movement").jsonPrimitive.content==movement && it.getValue("correct").jsonPrimitive.boolean }
            val backend=source.getValue("backend_sha256").jsonPrimitive.content;val pipeline=source.getValue("pipeline").jsonPrimitive.content
            val identity=RepEvidenceFingerprint(ref.getValue("source_sha256").jsonPrimitive.content,backend,pipeline,"vicon39-explicit-research-vector-driver")
            val reference=(QualifiedRepCompiler.compile(sequence(ref,VideoRole.REFERENCE),source.getValue("exercise_id").jsonPrimitive.content,identity,QualifiedRepConfig(side=RepObservedSide.valueOf(source.getValue("side").jsonPrimitive.content))) as QualifiedRepCompilation.Ready).reference
            val samples=mutableListOf<JsonObject>();val frames=mutableListOf<JsonObject>();val events=mutableListOf<RepAttemptResult>()
            val engine=QualifiedRepEngine(reference) { e -> samples+=buildJsonObject {
                put("timestamp_ms",e.timestampMs);put("attempt_id",e.attemptId);put("progress",e.driverProgress);put("uses_return_branch",e.comparisonUsesReturnBranch)
                put("reversal_confirmed",e.reversalConfirmed);put("far_endpoint_previously_observed",e.farEndpointPreviouslyObserved)
                put("features",JsonArray(e.features.map { f -> buildJsonObject {
                    put("reference_feature",f.referenceFeature);put("observed_feature",f.observedFeature);put("raw_degrees",f.rawDegrees);put("confidence",f.confidence);put("observed_degrees",f.observedDegrees)
                    put("lower_degrees",f.lowerDegrees);put("upper_degrees",f.upperDegrees);put("nominal_degrees",f.nominalAllowanceDegrees);put("acceptable_degrees",f.acceptableAllowanceDegrees);put("reject_degrees",f.rejectAllowanceDegrees);put("deviation_degrees",f.deviationDegrees);put("rule_id",f.ruleId)
                } }))
            } }
            val ordinary=reference.newEngine();val sourceSequence=sequence(source,VideoRole.SOURCE)
            for(frame in sourceSequence.frames) {
                val actual=engine.evaluate(frame,backend,pipeline,identity.declaredView)
                check(actual==ordinary.evaluate(frame,backend,pipeline,identity.declaredView))
                events+=actual.finalizedAttempts
                frames+=buildJsonObject {
                    put("timestamp_ms",frame.timestamp.value);put("stage",actual.stage.name);put("progress",actual.progress);put("active_attempt_id",actual.activeAttemptId);put("unavailable_reason",actual.unavailableReason?.name)
                    put("critical_values",buildJsonObject { reference.requiredFeatures.sorted().forEach { name -> put(name,frame.intrinsicDescriptor.values[name]) } })
                    val side=source.getValue("side").jsonPrimitive.content.lowercase()
                    put("out_of_plane_degrees",frame.intrinsicDescriptor.values["angle.${side}_arm_out_of_frontal_plane"])
                    put("projected_fraction",frame.intrinsicDescriptor.values["relation.${side}_arm_projected_fraction"])
                }
            }
            val ended=engine.finish(sourceSequence.duration.value);check(ended==ordinary.finish(sourceSequence.duration.value));events+=ended.finalizedAttempts
            val previous=prior.single { it.getValue("subject")==source.getValue("subject") && it.getValue("movement")==source.getValue("movement") && it.getValue("correct")==source.getValue("correct") }
            check(previous.getValue("reference_key").jsonPrimitive.content==reference.referenceKey && previous.getValue("attempts")==attempts(events))
            val report=buildJsonObject {
                put("schema","uiprmd-frontal-audit-record/1");for(key in listOf("subject","movement","correct","side","source_member","source_sha256","episodes")) put(key,source.getValue(key))
                put("reference_subject",refSubject);put("reference_key",reference.referenceKey);put("driver_feature",reference.driverFeature);put("required_progress",reference.config.requiredProgress)
                put("ready_angle",reference.readyAngle);put("far_angle",reference.farAngle)
                put("ready_progress",reference.config.readyProgress);put("minimum_confidence",reference.config.minimumConfidence);put("comparison_outputs_identical_with_and_without_observer",true);put("all_previous_attempt_fields_equal",true)
                put("attempts",attempts(events));put("samples",JsonArray(samples));put("frames",JsonArray(frames))
            }
            val filename="$movement-s$subject-${if(source.getValue("correct").jsonPrimitive.boolean) "correct" else "incorrect"}.json.gz";val file=File(output,filename)
            GZIPOutputStream(file.outputStream()).bufferedWriter().use { it.write(report.toString()) }
            reports+=buildJsonObject { put("file",filename);put("sha256",sha(file));for(key in listOf("subject","movement","correct")) put(key,source.getValue(key)) }
        }
        File(output,"manifest.json").writeText(buildJsonObject {
            put("schema","uiprmd-frontal-audit/1");put("compiler",QualifiedRepCompiler.VERSION);put("protocol_sha256",sha(protocol));put("source_manifest_sha256",sha(File(input,"development/manifest.json")))
            put("scope","development");put("records",JsonArray(reports));put("validation_accessed",false);put("final_test_accessed",false);put("production_changed",false);put("readiness_points_earned",0)
        }.toString()+"\n")
        println("16 development records audited; all prefix/finish outputs and previous attempt fields equal")
    }
}
