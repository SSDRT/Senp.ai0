package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*

/** Frozen selected-reference consequence audit; clip labels never define rep truth. */
object InImageSourceAudit {
    private fun sha(f: File, norm: Boolean=false)=MessageDigest.getInstance("SHA-256").digest(if(norm) f.readText().replace("\r\n","\n").toByteArray(Charsets.UTF_8) else f.readBytes()).joinToString("") { "%02x".format(it) }
    private fun read(f: File)=GZIPInputStream(f.inputStream()).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    private fun attempt(a: RepAttemptResult)=buildJsonObject {
        put("attempt_id",a.attemptId);put("started_ms",a.startedMs);put("completed_ms",a.completedMs);put("verdict",a.verdict.name)
        put("reasons",JsonArray(a.reasons.map { JsonPrimitive(it.name) }));put("maximum_progress",a.maximumProgress)
        put("visible_fraction",a.visibleFraction);put("ambiguous_fraction",a.ambiguousFraction)
        put("outbound_path_observed",a.outboundPathObserved);put("return_path_observed",a.returnPathObserved)
    }
    private fun fault(f: RepFault)=buildJsonObject {
        put("reason",f.reason.name);put("feature",f.feature?.let(::JsonPrimitive) ?: JsonNull)
        put("first_evidence_ms",f.firstEvidenceMs);put("confirmed_ms",f.confirmedMs);put("deviation_degrees",f.deviationDegrees?.let(::JsonPrimitive) ?: JsonNull)
    }
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args[0]);val protocol=File(root,"models/in-image-source-protocol-v2.json");val spec=Json.parseToJsonElement(protocol.readText()).jsonObject
        require(spec.getValue("schema").jsonPrimitive.content=="senpai-in-image-source-protocol/2")
        require(spec.getValue("allow_source_replay").jsonPrimitive.boolean && !spec.getValue("allow_validation_access").jsonPrimitive.boolean && !spec.getValue("allow_final_test_access").jsonPrimitive.boolean)
        require(spec.getValue("compiler").jsonPrimitive.content==QualifiedRepCompiler.VERSION)
        for(item in spec.getValue("receipts").jsonArray) { val r=item.jsonObject;val norm="sha256_lf" in r;require(sha(File(root,r.getValue("path").jsonPrimitive.content),norm)==r.getValue(if(norm) "sha256_lf" else "sha256").jsonPrimitive.content) { r.toString() } }
        val output=File(root,"test-artifacts/exercise-knowledge/in-image-source-screen");check(!output.exists()) { "Preserve original audit" };output.mkdirs()
        val choices=Json.parseToJsonElement(File(root,spec.getValue("reference_screen").jsonPrimitive.content).readText()).jsonObject.getValue("choices").jsonArray.map { it.jsonObject };require(choices.size==32)
        val sources=spec.getValue("sources").jsonArray.map { it.jsonObject };require(sources.size==48 && sources.map { it.getValue("video") }.distinct().size==48)
        val spatial=SpatialSynchronizationEngine(SpatialSynchronizationConfig(includeWorkoutFeatures=true));val seqs=mutableMapOf<String,SpatialSequenceAnalysis>();val refs=mutableMapOf<String,QualifiedRepReference>();val rows=mutableListOf<JsonObject>()
        fun sequence(path: String,variant: String,role: VideoRole): SpatialSequenceAnalysis=seqs.getOrPut("$path|$variant|$role") {
            val payload=read(File(root,path));require(payload.getValue("sample_period_ms").jsonPrimitive.int==66)
            val raw=payload.getValue("frames").jsonArray.map { QualifiedRepReplayTool.observation(it.jsonObject) }
            val frames=if(variant=="original") raw else raw.map(InImageMp33Evidence::mask)
            require(frames.map { it.timestamp }==raw.map { it.timestamp })
            require(frames.zip(raw).all { (a,b) -> a.channels.first()==b.channels.first() })
            spatial.analyzeSequence(CanonicalObservationSequence(role,DurationMs(frames.last().timestamp.value+1),ObservationSampling(analysisFramesPerSecond=15.0),frames))
        }
        for(source in sources) for(side in RepObservedSide.entries) {
            val name=source.getValue("video").jsonPrimitive.content;require(name.matches(Regex("subject_[0-9]{3}_(bicep_curl|push_up|shoulder|squat)_(good|bad)_(front|side)\\.mp4")))
            val refName=source.getValue("reference_video").jsonPrimitive.content
            val choice=choices.single { it.getValue("video").jsonPrimitive.content==refName && it.getValue("side").jsonPrimitive.content==side.name }
            require(choice.getValue("baseline_available")==choice.getValue("masked_available"))
            if(!choice.getValue("baseline_available").jsonPrimitive.boolean) {
                rows+=buildJsonObject { put("video",name);put("side",side.name);put("reference_video",refName);put("status","REFERENCE_UNAVAILABLE_BOTH_VARIANTS") };continue
            }
            for(variant in listOf("original","in_image")) {
                val selected=choice.getValue(if(variant=="original") "original_choice" else "masked_choice").jsonObject
                val original=choice.getValue("original_choice").jsonObject
                require(selected.getValue("model")==original.getValue("model") && selected.getValue("fit")==original.getValue("fit"))
                val model=selected.getValue("model").jsonPrimitive.content;val fit=selected.getValue("fit").jsonPrimitive.content
                val refPath=spec.getValue("references").jsonArray.map { it.jsonObject }.single { it.getValue("video").jsonPrimitive.content==refName }.getValue("${model}_cache_path").jsonPrimitive.content
                val srcPath=source.getValue("${model}_cache_path").jsonPrimitive.content;val payload=read(File(root,srcPath));val refPayload=read(File(root,refPath))
                val modelSha=spec.getValue("models").jsonObject.getValue(model).jsonPrimitive.content
                require(payload.getValue("model_sha256").jsonPrimitive.content==modelSha && refPayload.getValue("model_sha256").jsonPrimitive.content==modelSha)
                require(payload.getValue("source_video_sha256")==source.getValue("source_sha256") && refPayload.getValue("source_video_sha256")==choice.getValue("reference_video_sha256"))
                require(payload.getValue("maximum_sampled_duration_ms").jsonPrimitive.int==24000 && refPayload.getValue("maximum_sampled_duration_ms")==JsonNull)
                val view=choice.getValue("view").jsonPrimitive.content
                val reference=refs.getOrPut("$refPath|$variant|$side|$fit") {
                    val identity=RepEvidenceFingerprint(choice.getValue("reference_video_sha256").jsonPrimitive.content,modelSha,if(variant=="original") RepEvidenceFingerprint.RAW_WORLD_PIPELINE else InImageMp33Evidence.PIPELINE,view)
                    val compiled=QualifiedRepCompiler.compile(sequence(refPath,variant,VideoRole.REFERENCE),choice.getValue("exercise").jsonPrimitive.content,identity,QualifiedRepConfig(side=side,referenceCurveFit=RepReferenceCurveFit.valueOf(fit)))
                    require(compiled is QualifiedRepCompilation.Ready);compiled.reference.also { require(it.referenceKey==selected.getValue("reference_key").jsonPrimitive.content) }
                }
                val seq=sequence(srcPath,variant,VideoRole.SOURCE);val engine=reference.newEngine();val attempts=mutableListOf<JsonObject>();val faults=mutableListOf<JsonObject>();val trace=mutableListOf<JsonObject>()
                for(frame in seq.frames) {
                    val result=engine.evaluate(frame,modelSha,captureContext=view);attempts+=result.finalizedAttempts.map(::attempt);faults+=result.newFaults.map(::fault)
                    trace+=buildJsonObject { put("timestamp_ms",frame.timestamp.value);put("stage",result.stage.name);put("progress",result.progress?.let(::JsonPrimitive) ?: JsonNull);put("active_disqualified",result.activeDisqualified);put("qualified_count",result.counts.qualified);put("unavailable_reason",result.unavailableReason?.name?.let(::JsonPrimitive) ?: JsonNull) }
                }
                val result=engine.finish(seq.duration.value);attempts+=result.finalizedAttempts.map(::attempt)
                val counts=buildJsonObject { put("qualified",result.counts.qualified);put("correct",result.counts.correct);put("acceptable",result.counts.acceptable);put("rejected",result.counts.rejected);put("unassessable",result.counts.unassessable) }
                if(variant=="original") {
                    val fitDir=if(fit=="ORDINARY_LEAST_SQUARES") "standard" else "robust"
                    val baseline=Json.parseToJsonElement(File(root,"test-artifacts/exercise-knowledge/preparation-fault-complete-$model-$fitDir/${name.removeSuffix(".mp4")}-${side.name.lowercase()}.json").readText()).jsonObject
                    require(baseline.getValue("reference_key").jsonPrimitive.content==reference.referenceKey)
                    require(baseline.getValue("attempts")==JsonArray(attempts) && baseline.getValue("faults")==JsonArray(faults)) { "Original event parity failed: $name/$side" }
                    require(counts.all { (k,v) -> baseline.getValue("counts").jsonObject.getValue(k)==v })
                    require(baseline.getValue("trace").jsonArray.size==trace.size)
                    require(trace.zip(baseline.getValue("trace").jsonArray).all { (a,b) -> a.all { (k,v) -> b.jsonObject.getValue(k)==v } }) { "Original timestamp trace parity failed" }
                }
                val record=buildJsonObject { put("video",name);put("reference_video",refName);put("side",side.name);put("view",view);put("model",model);put("fit",fit);put("variant",variant);put("status","REPLAYED");put("source_sha256",source.getValue("source_sha256"));put("reference_key",reference.referenceKey);put("weak_whole_clip_label",payload.getValue("quality"));put("counts",counts);put("attempts",JsonArray(attempts));put("faults",JsonArray(faults));put("trace",JsonArray(trace)) }
                File(output,"${name.removeSuffix(".mp4")}-${side.name.lowercase()}-$variant.json").writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),record))
                rows+=JsonObject(record.filterKeys { it!="trace" })
            }
        }
        val replayed=rows.count { it.getValue("status").jsonPrimitive.content=="REPLAYED" };require(replayed==76 && rows.size==134)
        require(spec.getValue("expected_variant_replays").jsonPrimitive.int==replayed && spec.getValue("reference_qualified_source_sides").jsonPrimitive.int==38)
        val summary=buildJsonObject { put("schema","senpai-in-image-source-audit/2");put("protocol_sha256",sha(protocol));put("fixed_source_side_total",96);put("reference_qualified_source_side_runs",38);put("variant_replays",76);put("original_baseline_events_counts_and_trace_verified",true);put("source_model_selection","frozen-reference-only");put("production_changed",false);put("human_rep_precision",JsonNull);put("rows",JsonArray(rows)) }
        File(output,"summary.json").writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),summary));println("Selected-reference source audit: $replayed variant replays, 96 fixed source sides; original event/trace parity exact")
    }
}
