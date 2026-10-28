package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*

/** Reference-only consequence audit. Does not modify raw caches or the app. */
object InImageReferenceAudit {
    private fun sha(f: File,norm: Boolean=false)=MessageDigest.getInstance("SHA-256").digest(if(norm) f.readText().replace("\r\n","\n").toByteArray(Charsets.UTF_8) else f.readBytes()).joinToString("") { "%02x".format(it) }
    private fun read(f: File)=GZIPInputStream(f.inputStream()).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args[0]);val path=File(root,"models/in-image-reference-protocol.json");val spec=Json.parseToJsonElement(path.readText()).jsonObject
        require(spec.getValue("schema").jsonPrimitive.content=="senpai-in-image-reference-protocol/1" && spec.getValue("reference_only").jsonPrimitive.boolean)
        require(!spec.getValue("allow_final_test_access").jsonPrimitive.boolean && !spec.getValue("allow_validation_access").jsonPrimitive.boolean)
        require(spec.getValue("compiler").jsonPrimitive.content==QualifiedRepCompiler.VERSION && spec.getValue("pipeline").jsonPrimitive.content==InImageMp33Evidence.PIPELINE)
        for(item in spec.getValue("receipts").jsonArray) { val r=item.jsonObject;val norm="sha256_lf" in r;require(sha(File(root,r.getValue("path").jsonPrimitive.content),norm)==r.getValue(if(norm) "sha256_lf" else "sha256").jsonPrimitive.content) }
        val output=File(root,"test-artifacts/exercise-knowledge/in-image-reference-screen.json");check(!output.exists()) { "Preserve original audit" }
        val registry=Json.parseToJsonElement(File(root,spec.getValue("baseline_registry").jsonPrimitive.content).readText()).jsonArray.map { it.jsonObject };require(registry.size==32)
        fun key(r: JsonObject)=listOf("reference_video_sha256","exercise","side","view").joinToString("|") { r.getValue(it).jsonPrimitive.content }
        val established=registry.associateBy(::key);require(established.size==32)
        val references=spec.getValue("references").jsonArray.map { it.jsonObject };require(references.size==16)
        require(references.map { it.getValue("video") }.distinct().size==16)
        require(references.all { it.getValue("video").jsonPrimitive.content.matches(Regex("subject_(001|005)_(squat|push_up|bicep_curl|shoulder)_good_(front|side)\\.mp4")) })
        val rows=mutableListOf<JsonObject>();val choices=mutableListOf<JsonObject>()
        val spatial=SpatialSynchronizationEngine(SpatialSynchronizationConfig(includeWorkoutFeatures=true))
        val exercises=mapOf("squat" to "bodyweight_squat","push_up" to "push_up","bicep_curl" to "biceps_curl","shoulder" to "overhead_press")
        for(entry in references) {
            val name=entry.getValue("video").jsonPrimitive.content;val candidates=mutableListOf<JsonObject>()
            for(model in listOf("full","heavy")) {
                val payload=read(File(root,entry.getValue("${model}_cache_path").jsonPrimitive.content))
                require(payload.getValue("source_video_sha256")==entry.getValue("source_sha256"))
                require(payload.getValue("model_sha256")==spec.getValue("models").jsonObject.getValue(model))
                require(payload.getValue("sample_period_ms").jsonPrimitive.int==66 && payload.getValue("maximum_sampled_duration_ms")==JsonNull)
                val raw=payload.getValue("frames").jsonArray.map { it.jsonObject };val observations=raw.map(QualifiedRepReplayTool::observation)
                val masked=observations.map(InImageMp33Evidence::mask)
                require(masked.map { it.timestamp }==observations.map { it.timestamp })
                require(masked.zip(observations).all { (m,o) -> m.channels.first()==o.channels.first() })
                val changed=masked.zip(observations).count { (a,b) -> a!=b }
                for(variant in listOf("original","in_image")) {
                    val source=if(variant=="original") observations else masked
                    val seq=spatial.analyzeSequence(CanonicalObservationSequence(VideoRole.REFERENCE,DurationMs(source.last().timestamp.value+1),ObservationSampling(analysisFramesPerSecond=15.0),source))
                    val identity=RepEvidenceFingerprint(entry.getValue("source_sha256").jsonPrimitive.content,payload.getValue("model_sha256").jsonPrimitive.content,if(variant=="original") RepEvidenceFingerprint.RAW_WORLD_PIPELINE else InImageMp33Evidence.PIPELINE,payload.getValue("view").jsonPrimitive.content)
                    val exercise=exercises.getValue(payload.getValue("exercise").jsonPrimitive.content)
                    for(side in RepObservedSide.entries) for(fit in listOf(RepReferenceCurveFit.ORDINARY_LEAST_SQUARES,RepReferenceCurveFit.BOUNDED_MEDIAN_PAIRWISE)) {
                        val result=QualifiedRepCompiler.compile(seq,exercise,identity,QualifiedRepConfig(side=side,referenceCurveFit=fit));val ready=(result as? QualifiedRepCompilation.Ready)?.reference
                        candidates+=buildJsonObject {
                            put("video",name);put("reference_video_sha256",identity.referenceVideoSha256);put("exercise",exercise);put("side",side.name);put("view",identity.declaredView);put("model",model);put("fit",fit.name);put("variant",variant);put("pipeline",identity.preprocessingId)
                            put("status",if(ready!=null) "READY" else (result as QualifiedRepCompilation.Unavailable).reason.name);put("detail",(result as? QualifiedRepCompilation.Unavailable)?.detail ?: "Ready under unchanged gates");put("frames",raw.size);put("frames_with_world_mask_change",if(variant=="in_image") changed else 0)
                            ready?.let { put("reference_key",it.referenceKey);put("selected_cycles",it.calibration?.observedCycles);put("self_accepted_cycles",it.calibration?.acceptedSelfCycles) }
                        }
                    }
                }
            }
            rows+=candidates
            for(side in RepObservedSide.entries) {
                fun choose(variant: String): JsonObject? {
                    for(fit in listOf("ORDINARY_LEAST_SQUARES","BOUNDED_MEDIAN_PAIRWISE")) for(model in listOf("full","heavy")) {
                        val candidate=candidates.single { it.getValue("variant").jsonPrimitive.content==variant && it.getValue("side").jsonPrimitive.content==side.name && it.getValue("fit").jsonPrimitive.content==fit && it.getValue("model").jsonPrimitive.content==model }
                        if(candidate.getValue("status").jsonPrimitive.content=="READY") return candidate
                    }
                    return null
                }
                val anchor=candidates.first { it.getValue("side").jsonPrimitive.content==side.name };val old=established.getValue(key(anchor));val original=choose("original");val candidate=choose("in_image")
                require((original!=null)==(old.getValue("selected_model").jsonPrimitive.content!="unavailable"))
                if(original!=null) {
                    require(original.getValue("model")==old.getValue("selected_model") && original.getValue("fit")==old.getValue("selected_fit"))
                    require(original.getValue("reference_key")==old.getValue("reference_key"))
                }
                choices+=buildJsonObject {
                    for(f in listOf("video","reference_video_sha256","exercise","side","view")) put(f,anchor.getValue(f))
                    put("baseline_available",original!=null);put("masked_available",candidate!=null);put("reference_lost",original!=null && candidate==null);put("new_reference",original==null && candidate!=null);put("original_choice",original ?: JsonNull);put("masked_choice",candidate ?: JsonNull)
                }
            }
        }
        require(rows.size==256 && choices.size==32)
        val report=buildJsonObject {
            put("schema","senpai-in-image-reference-audit/1");put("protocol_sha256",sha(path));put("baseline_usable",choices.count { it.getValue("baseline_available").jsonPrimitive.boolean });put("masked_usable",choices.count { it.getValue("masked_available").jsonPrimitive.boolean });put("reference_losses",choices.count { it.getValue("reference_lost").jsonPrimitive.boolean });put("potential_additional_references",choices.count { it.getValue("new_reference").jsonPrimitive.boolean });put("production_changed",false);put("source_replay_performed",false);put("human_rep_precision",JsonNull);put("rows",JsonArray(rows));put("choices",JsonArray(choices))
        }
        output.writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),report));println("In-image audit: ${report.getValue("masked_usable")} usable; ${report.getValue("reference_losses")} existing sides withheld")
    }
}
