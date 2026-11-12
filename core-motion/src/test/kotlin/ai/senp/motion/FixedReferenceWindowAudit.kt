package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*

/** Reference-only, fixed-time-window screen. No source sessions or skeletal subjects loaded. */
object FixedReferenceWindowAudit {
    private fun sha(f: File, lf: Boolean=false): String {
        val bytes=if(lf) f.readText().replace("\r\n","\n").toByteArray(Charsets.UTF_8) else f.readBytes()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    private data class Input(val spatial: SpatialSequenceAnalysis,val identity: RepEvidenceFingerprint)
    private fun outcome(result: QualifiedRepCompilation): JsonObject=buildJsonObject {
        val ready=(result as? QualifiedRepCompilation.Ready)?.reference
        put("status",if(ready!=null) "READY" else (result as QualifiedRepCompilation.Unavailable).reason.name)
        put("detail",(result as? QualifiedRepCompilation.Unavailable)?.detail ?: "Ready under unchanged production gates")
        ready?.let { r ->
            put("reference_key",r.referenceKey)
            requireNotNull(r.calibration).let { c ->
                put("selected_cycles",c.observedCycles);put("accepted_self_cycles",c.acceptedSelfCycles)
                put("self_acceptance_fraction",c.selfAcceptanceFraction)
                put("selected_first_ms",c.firstSelectedMs);put("selected_last_ms",c.lastSelectedMs)
            }
        }
    }
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args[0]);val protocolFile=File(root,"models/fixed-reference-window-protocol.json")
        val protocol=Json.parseToJsonElement(protocolFile.readText()).jsonObject
        require(protocol.getValue("schema").jsonPrimitive.content=="senpai-fixed-reference-window/1")
        require(protocol.getValue("reference_only").jsonPrimitive.boolean && !protocol.getValue("allow_final_test_access").jsonPrimitive.boolean)
        require(!protocol.getValue("source_outcome_selection_allowed").jsonPrimitive.boolean)
        require(protocol.getValue("compiler").jsonPrimitive.content==QualifiedRepCompiler.VERSION)
        require(protocol.getValue("window_ms").jsonPrimitive.long==FixedReferenceWindowResearch.WINDOW_MS)
        require(protocol.getValue("stride_ms").jsonPrimitive.long==FixedReferenceWindowResearch.STRIDE_MS)
        require(protocol.getValue("minimum_window_ms").jsonPrimitive.long==FixedReferenceWindowResearch.MINIMUM_WINDOW_MS)
        for(item in protocol.getValue("source_receipts").jsonArray) {
            val r=item.jsonObject;val lf="sha256_lf" in r
            require(sha(File(root,r.getValue("path").jsonPrimitive.content),lf)==r.getValue(if(lf) "sha256_lf" else "sha256").jsonPrimitive.content) { "Frozen reference research source/input changed" }
        }
        val output=File(root,"test-artifacts/exercise-knowledge/fixed-reference-window-screen.json")
        check(!output.exists()) { "Preserve earlier fixed-window results" }
        val old=Json.parseToJsonElement(File(root,protocol.getValue("baseline_registry").jsonPrimitive.content).readText()).jsonArray.map { it.jsonObject }
        fun key(r: JsonObject)=listOf("reference_video_sha256","exercise","side","view").joinToString("|") { r.getValue(it).jsonPrimitive.content }
        val registry=old.associateBy(::key);require(registry.size==32)
        val fits=listOf(RepReferenceCurveFit.ORDINARY_LEAST_SQUARES,RepReferenceCurveFit.BOUNDED_MEDIAN_PAIRWISE)
        val priority=listOf("full" to fits[0],"heavy" to fits[0],"full" to fits[1],"heavy" to fits[1])
        // These baseline reports are accessed for reference calibration status/key/detail only.
        val prior=priority.associateWith { (model,fit) ->
            val label=if(fit==fits[0]) "standard" else "robust"
            Json.parseToJsonElement(File(root,"test-artifacts/exercise-knowledge/clear-ready-complete-$model-$label/summary.json").readText())
                .jsonObject.getValue("rows").jsonArray.map { it.jsonObject }.groupBy(::key).mapValues { (_,rows) ->
                    require(rows.map { listOf(it["status"],it["reference_key"],it["detail"]) }.distinct().size==1)
                    rows.first()
                }
        }
        val rows=mutableListOf<JsonObject>();val choices=mutableListOf<JsonObject>()
        val ids=mapOf("squat" to "bodyweight_squat","push_up" to "push_up","bicep_curl" to "biceps_curl","shoulder" to "overhead_press")
        val spatial=SpatialSynchronizationEngine(SpatialSynchronizationConfig(includeWorkoutFeatures=true))
        val references=protocol.getValue("references").jsonArray.map { it.jsonObject };require(references.size==16)
        for(ref in references) {
            val name=ref.getValue("video").jsonPrimitive.content
            require(name.matches(Regex("subject_(001|005)_(squat|push_up|bicep_curl|shoulder)_good_(front|side)\\.mp4")))
            val inputs=listOf("full","heavy").associateWith { model ->
                val file=File(root,"test-artifacts/exercise-knowledge/complete-reference-$model-cache/${name.removeSuffix(".mp4")}.json.gz")
                val p=GZIPInputStream(file.inputStream()).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
                require(p.getValue("source_video_sha256")==ref.getValue("source_sha256") && p.getValue("video").jsonPrimitive.content==name)
                require(p.getValue("model_sha256")==protocol.getValue("model_sha256").jsonObject.getValue(model))
                require(p.getValue("sample_period_ms").jsonPrimitive.int==66 && p.getValue("maximum_sampled_duration_ms")==JsonNull)
                val raw=p.getValue("frames").jsonArray.map { it.jsonObject }
                for(r in raw) {
                    val points=r.getValue("landmarks").jsonArray.map { it.jsonArray.map { n -> n.jsonPrimitive.double } }
                    require(points.size in setOf(0,33) && points.all { it.size==8 && it.all(Double::isFinite) && it[3] in 0.0..1.0 && it[4] in 0.0..1.0 })
                }
                val observations=raw.map(QualifiedRepReplayTool::observation)
                require(observations.zipWithNext().all { (a,b) -> b.timestamp.value>a.timestamp.value })
                Input(spatial.analyzeSequence(CanonicalObservationSequence(VideoRole.REFERENCE,DurationMs(observations.last().timestamp.value+1),
                    ObservationSampling(analysisFramesPerSecond=15.0),observations)),
                    RepEvidenceFingerprint(ref.getValue("source_sha256").jsonPrimitive.content,p.getValue("model_sha256").jsonPrimitive.content,
                        declaredView=p.getValue("view").jsonPrimitive.content))
            }
            require(inputs.getValue("full").spatial.frames.map { it.timestamp }==inputs.getValue("heavy").spatial.frames.map { it.timestamp })
            val exercise=ids.getValue(name.removePrefix("subject_001_").removePrefix("subject_005_").substringBefore("_good_"))
            for(side in RepObservedSide.entries) {
                val identity=inputs.getValue("full").identity
                val descriptor=buildJsonObject {
                    put("video",name);put("reference_video_sha256",identity.referenceVideoSha256);put("exercise",exercise)
                    put("side",side.name);put("view",identity.declaredView)
                }
                val k=key(descriptor);val previous=registry.getValue(k)
                val retained=previous.getValue("selected_model").jsonPrimitive.content!="unavailable"
                val complete=priority.associateWith { (model,fit) ->
                    val input=inputs.getValue(model)
                    QualifiedRepCompiler.compile(input.spatial,exercise,input.identity,QualifiedRepConfig(side=side,referenceCurveFit=fit))
                }
                for((setting,result) in complete) {
                    val oldRow=prior.getValue(setting).getValue(k);val expected=oldRow.getValue("status").jsonPrimitive.content
                    val now=outcome(result)
                    require(now.getValue("status").jsonPrimitive.content==if(expected=="REPLAYED") "READY" else expected)
                    if(expected=="REPLAYED") require(now["reference_key"]==oldRow["reference_key"]) else require(now["detail"]==oldRow["detail"])
                }
                var selected: JsonObject?=null
                if(!retained) {
                    val windows=inputs.mapValues { (_,input) -> FixedReferenceWindowResearch.windows(input.spatial) }
                    for(index in windows.getValue("full").indices) {
                        for((model,fit) in priority) {
                            if(!FixedReferenceWindowResearch.mayScreen(complete.getValue(model to fit))) continue
                            val input=inputs.getValue(model);val w=windows.getValue(model)[index]
                            val result=QualifiedRepCompiler.compile(w.sequence,exercise,input.identity,QualifiedRepConfig(side=side,referenceCurveFit=fit))
                            val trial=buildJsonObject {
                                descriptor.forEach { (k,v) -> put(k,v) };put("model",model);put("fit",fit.name)
                                put("window_start_ms",w.startMs);put("window_end_exclusive_ms",w.endExclusiveMs)
                                put("observations",w.sequence.frames.size);put("first_pts_ms",w.sequence.frames.first().timestamp.value)
                                put("last_pts_ms",w.sequence.frames.last().timestamp.value)
                                put("whole_reference_outcome",outcome(complete.getValue(model to fit)))
                                outcome(result).forEach { (k,v) -> put(k,v) }
                            }
                            rows+=trial
                            if(selected==null && result is QualifiedRepCompilation.Ready) selected=trial
                        }
                    }
                }
                choices+=buildJsonObject {
                    descriptor.forEach { (k,v) -> put(k,v) };put("baseline_available",retained);put("established_selection",previous)
                    put("selected_stage",if(retained) "KEEP_ESTABLISHED_BACKEND" else if(selected!=null) "RESEARCH_FIXED_WINDOW" else "UNAVAILABLE")
                    put("window_candidate",selected ?: JsonNull)
                }
            }
        }
        require(choices.size==32 && choices.count { it.getValue("baseline_available").jsonPrimitive.boolean }==12)
        val report=buildJsonObject {
            put("schema","senpai-fixed-reference-window-screen/1");put("kind","reference-only-screen-not-human-form-precision")
            put("protocol_sha256",sha(protocolFile));put("compiler",QualifiedRepCompiler.VERSION)
            put("baseline_usable",12);put("potential_additional_references",choices.count { it.getValue("selected_stage").jsonPrimitive.content=="RESEARCH_FIXED_WINDOW" })
            put("whole_reference_baselines_reproduced",true);put("established_choices_preserved",true)
            put("production_changed",false);put("source_replay_performed",false);put("final_test_access",false)
            put("human_rep_precision",JsonNull);put("rows",JsonArray(rows));put("choices",JsonArray(choices))
        }
        output.writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),report))
        println("Fixed-window reference screen: ${report["potential_additional_references"]} potential additions; no production or accuracy gain established")
    }
}
