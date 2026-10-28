package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*
import kotlin.math.abs

internal object ClaheReferenceEvidence {
    const val SHA="5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"
    const val PIPELINE="mp33-full-image-lab-clahe2x8-coarse-bound/1"
    fun identity(payload: JsonObject, variant: String) {
        require(variant in setOf("original","clahe"))
        require(payload.getValue("model_sha256").jsonPrimitive.content==SHA)
        require(payload.getValue("preprocessing_id").jsonPrimitive.content==if(variant=="clahe") PIPELINE else "mp33-coarse-bound-full-image/1")
        require(payload.getValue("schema").jsonPrimitive.content==if(variant=="clahe") "mp33-clahe-reference-research/1" else "mp33-image-refinement-research/1")
    }
    fun unmodified(raw: List<List<Double>>, admitted: List<List<Double>>, bound: Boolean) {
        LiteReferenceEvidence.raw(raw)
        require(admitted.size==if(bound) raw.size else 0)
        if(bound) require(raw.zip(admitted).all { (a,b) -> b.size==8 && a.indices.all { i -> if(i<3) abs(a[i]-b[i])<=1e-12 else a[i]==b[i] } })
    }
}

/** Photometry screen only: original references, no source labels or holdout access. */
object ClaheReferenceCalibrationAudit {
    private fun sha(file: File, norm: Boolean=false): String = MessageDigest.getInstance("SHA-256").digest(
        if(norm) file.readText().replace("\r\n","\n").toByteArray(Charsets.UTF_8) else file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun read(file: File) = GZIPInputStream(file.inputStream()).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    private fun points(row: JsonObject,key: String) = row.getValue(key).jsonArray.map { it.jsonArray.map { v -> v.jsonPrimitive.double } }
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args[0]);val specFile=File(root,"models/clahe-reference-protocol.json")
        val output=File(root,"test-artifacts/exercise-knowledge/clahe-reference-screen.json");check(!output.exists())
        val spec=Json.parseToJsonElement(specFile.readText()).jsonObject
        require(spec.getValue("schema").jsonPrimitive.content=="senpai-clahe-reference-protocol/1")
        require(spec.getValue("reference_only").jsonPrimitive.boolean && !spec.getValue("allow_final_test_access").jsonPrimitive.boolean && !spec.getValue("allow_validation_access").jsonPrimitive.boolean)
        require(spec.getValue("compiler").jsonPrimitive.content==QualifiedRepCompiler.VERSION)
        require(spec.getValue("model_sha256").jsonPrimitive.content==ClaheReferenceEvidence.SHA)
        require(spec.getValue("preprocessing_id").jsonPrimitive.content==ClaheReferenceEvidence.PIPELINE)
        for(item in spec.getValue("receipts").jsonArray) {
            val r=item.jsonObject;val norm="sha256_lf" in r
            require(sha(File(root,r.getValue("path").jsonPrimitive.content),norm)==r.getValue(if(norm) "sha256_lf" else "sha256").jsonPrimitive.content)
        }
        val baseline=Json.parseToJsonElement(File(root,spec.getValue("baseline_registry").jsonPrimitive.content).readText()).jsonArray.map { it.jsonObject }
        fun key(r: JsonObject) = listOf("reference_video_sha256","exercise","side","view").joinToString("|") { r.getValue(it).jsonPrimitive.content }
        val old=baseline.associateBy(::key);require(old.size==32 && baseline.size==32)
        val entries=spec.getValue("references").jsonArray.map { it.jsonObject }
        require(entries.size==16 && entries.map { it.getValue("video") }.distinct().size==16)
        require(entries.all { it.getValue("video").jsonPrimitive.content.matches(Regex("subject_(001|005)_(squat|push_up|bicep_curl|shoulder)_good_(front|side)\\.mp4")) })
        val extractionFile=File(root,"test-artifacts/exercise-knowledge/clahe-reference-extraction.json")
        val extraction=Json.parseToJsonElement(extractionFile.readText()).jsonObject
        require(extraction.getValue("protocol_sha256").jsonPrimitive.content==sha(specFile) && extraction.getValue("failures").jsonArray.isEmpty())
        val receipts=extraction.getValue("records").jsonArray.map { it.jsonObject };require(receipts.size==16)
        val ids=mapOf("squat" to "bodyweight_squat","push_up" to "push_up","bicep_curl" to "biceps_curl","shoulder" to "overhead_press")
        val spatial=SpatialSynchronizationEngine(SpatialSynchronizationConfig(includeWorkoutFeatures=true))
        val rows=mutableListOf<JsonObject>();val choices=mutableListOf<JsonObject>()
        for(entry in entries) {
            val name=entry.getValue("video").jsonPrimitive.content
            val coarseFile=File(root,entry.getValue("coarse_cache_path").jsonPrimitive.content)
            require(sha(coarseFile)==entry.getValue("coarse_cache_sha256").jsonPrimitive.content)
            val coarse=read(coarseFile).getValue("frames").jsonArray.map { it.jsonObject }
            val candidates=mutableListOf<JsonObject>()
            for(variant in listOf("original","clahe")) {
                val relative=if(variant=="clahe") "test-artifacts/exercise-knowledge/clahe-reference-cache/${name.removeSuffix(".mp4")}.json.gz"
                    else "test-artifacts/exercise-knowledge/image-reference-full_image-cache/${name.removeSuffix(".mp4")}.json.gz"
                val file=File(root,relative)
                if(variant=="clahe") require(sha(file)==receipts.single { it.getValue("video").jsonPrimitive.content==name }.getValue("cache_sha256").jsonPrimitive.content)
                val payload=read(file);ClaheReferenceEvidence.identity(payload,variant)
                require(payload.getValue("source_video_sha256")==entry.getValue("source_sha256") && payload.getValue("coarse_cache_sha256")==entry.getValue("coarse_cache_sha256"))
                require(payload.getValue("sample_period_ms").jsonPrimitive.int==66 && payload.getValue("maximum_sampled_duration_ms")==JsonNull)
                val raw=payload.getValue("frames").jsonArray.map { it.jsonObject }
                require(raw.map { it.getValue("timestamp_ms") }==coarse.map { it.getValue("timestamp_ms") })
                raw.zip(coarse).forEach { (r,c) ->
                    require(r.getValue("image_width")==c.getValue("image_width") && r.getValue("image_height")==c.getValue("image_height"))
                    require(r.getValue("coarse_timestamp_ms")==r.getValue("timestamp_ms"))
                    if(variant=="clahe") for(k in listOf("input_rgb_sha256","original_rgb_sha256")) require(r.getValue(k).jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
                    val original=points(r,"raw_input_landmarks");val bound=RotationReferenceMapping.sameSubject(points(c,"landmarks"),original)
                    require(r.getValue("subject_bound").jsonPrimitive.boolean==bound)
                    ClaheReferenceEvidence.unmodified(original,points(r,"landmarks"),bound)
                    val confidences=points(r,"raw_world_visibility_presence")
                    require(confidences.size==original.size && confidences.all { it.size==2 && it.all { v -> v.isFinite() && v in 0.0..1.0 } })
                }
                val observations=raw.map(QualifiedRepReplayTool::observation)
                val sequence=spatial.analyzeSequence(CanonicalObservationSequence(VideoRole.REFERENCE,DurationMs(observations.last().timestamp.value+1),ObservationSampling(analysisFramesPerSecond=15.0),observations))
                val identity=RepEvidenceFingerprint(entry.getValue("source_sha256").jsonPrimitive.content,ClaheReferenceEvidence.SHA,payload.getValue("preprocessing_id").jsonPrimitive.content,payload.getValue("view").jsonPrimitive.content)
                val exercise=ids.getValue(payload.getValue("exercise").jsonPrimitive.content)
                for(side in RepObservedSide.entries) for(fit in listOf(RepReferenceCurveFit.ORDINARY_LEAST_SQUARES,RepReferenceCurveFit.BOUNDED_MEDIAN_PAIRWISE)) {
                    val result=QualifiedRepCompiler.compile(sequence,exercise,identity,QualifiedRepConfig(side=side,referenceCurveFit=fit));val ready=(result as? QualifiedRepCompilation.Ready)?.reference
                    candidates+=buildJsonObject {
                        put("video",name);put("variant",variant);put("reference_video_sha256",identity.referenceVideoSha256);put("exercise",exercise);put("side",side.name);put("view",identity.declaredView);put("fit",fit.name)
                        put("model_sha256",ClaheReferenceEvidence.SHA);put("preprocessing_id",identity.preprocessingId);put("cache_sha256",sha(file));put("status",if(ready!=null) "READY" else (result as QualifiedRepCompilation.Unavailable).reason.name)
                        put("detail",(result as? QualifiedRepCompilation.Unavailable)?.detail ?: "Ready under unchanged gates")
                        ready?.let { put("reference_key",it.referenceKey);put("selected_cycles",it.calibration?.observedCycles);put("self_accepted_cycles",it.calibration?.acceptedSelfCycles) }
                    }
                }
            }
            rows+=candidates
            for(side in RepObservedSide.entries) {
                val matches=candidates.filter { it.getValue("side").jsonPrimitive.content==side.name }
                val established=old.getValue(key(matches.first()));val retained=established.getValue("selected_model").jsonPrimitive.content!="unavailable"
                val candidate=matches.firstOrNull { it.getValue("variant").jsonPrimitive.content=="clahe" && it.getValue("status").jsonPrimitive.content=="READY" }
                choices+=buildJsonObject {
                    for(f in listOf("video","reference_video_sha256","exercise","side","view")) put(f,matches.first().getValue(f))
                    put("baseline_available",retained);put("established_selection",established);put("selected_stage",if(retained) "KEEP_ESTABLISHED_BACKEND" else if(candidate!=null) "RESEARCH_CLAHE" else "UNAVAILABLE");put("candidate",candidate ?: JsonNull)
                }
            }
        }
        require(rows.size==128 && choices.size==32)
        val report=buildJsonObject {
            put("schema","senpai-clahe-reference-screen/1");put("protocol_sha256",sha(specFile));put("extraction_sha256",sha(extractionFile));put("baseline_usable",choices.count { it.getValue("baseline_available").jsonPrimitive.boolean })
            put("potential_additional_references",choices.count { it.getValue("selected_stage").jsonPrimitive.content=="RESEARCH_CLAHE" });put("established_choices_preserved",true);put("source_replay_performed",false);put("production_changed",false);put("human_rep_precision",JsonNull);put("rows",JsonArray(rows));put("choices",JsonArray(choices))
        }
        output.writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),report));println("CLAHE reference screen: ${report.getValue("potential_additional_references")} potential new sides")
    }
}
