package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*

internal object LiteReferenceEvidence {
    const val SHA = "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a"
    const val PIPELINE = "mp33-lite-video-coarse-bound-research/1"
    fun identity(payload: JsonObject) {
        require(payload.getValue("schema").jsonPrimitive.content == "mp33-lite-reference-research/1")
        require(payload.getValue("model_sha256").jsonPrimitive.content == SHA)
        require(payload.getValue("model_variant").jsonPrimitive.content == "lite")
        require(payload.getValue("preprocessing_id").jsonPrimitive.content == PIPELINE)
    }
    fun raw(points: List<List<Double>>) {
        require(points.isEmpty() || points.size == 33)
        require(points.all { it.size == 8 && it.all(Double::isFinite) && it[3] in 0.0..1.0 && it[4] in 0.0..1.0 })
    }
}

/** First Lite screen: references only. No source/holdout reads or selector mutation. */
object LiteReferenceCalibrationAudit {
    private fun sha(file: File, normalized: Boolean = false): String = MessageDigest.getInstance("SHA-256").digest(
        if (normalized) file.readText().replace("\r\n","\n").toByteArray(Charsets.UTF_8) else file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun read(file: File) = GZIPInputStream(file.inputStream()).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    private fun points(row: JsonObject,key: String) = row.getValue(key).jsonArray.map { it.jsonArray.map { v -> v.jsonPrimitive.double } }
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0]);val specFile = File(root,"models/lite-reference-protocol.json")
        val output = File(root,"test-artifacts/exercise-knowledge/lite-reference-screen.json")
        check(!output.exists()) { "Preserve completed Lite screen" }
        val spec = Json.parseToJsonElement(specFile.readText()).jsonObject
        require(spec.getValue("schema").jsonPrimitive.content == "senpai-lite-reference-protocol/1")
        require(spec.getValue("reference_only").jsonPrimitive.boolean && !spec.getValue("allow_final_test_access").jsonPrimitive.boolean && !spec.getValue("allow_validation_access").jsonPrimitive.boolean)
        require(spec.getValue("model_sha256").jsonPrimitive.content == LiteReferenceEvidence.SHA)
        require(spec.getValue("preprocessing_id").jsonPrimitive.content == LiteReferenceEvidence.PIPELINE)
        for (item in spec.getValue("receipts").jsonArray) {
            val r = item.jsonObject;val normalize = "sha256_lf" in r
            require(sha(File(root,r.getValue("path").jsonPrimitive.content),normalize) == r.getValue(if (normalize) "sha256_lf" else "sha256").jsonPrimitive.content)
        }
        val baseline = Json.parseToJsonElement(File(root,spec.getValue("baseline_registry").jsonPrimitive.content).readText()).jsonArray.map { it.jsonObject }
        fun key(r: JsonObject) = listOf("reference_video_sha256","exercise","side","view").joinToString("|") { r.getValue(it).jsonPrimitive.content }
        val old = baseline.associateBy(::key);require(old.size == 32 && baseline.size == 32)
        val entries = spec.getValue("references").jsonArray.map { it.jsonObject }
        require(entries.size == 16 && entries.map { it.getValue("video") }.distinct().size == 16)
        require(entries.all { it.getValue("video").jsonPrimitive.content.matches(Regex("subject_(001|005)_(squat|push_up|bicep_curl|shoulder)_good_(front|side)\\.mp4")) })
        val extractionFile = File(root,"test-artifacts/exercise-knowledge/lite-reference-extraction.json")
        val extraction = Json.parseToJsonElement(extractionFile.readText()).jsonObject
        require(extraction.getValue("protocol_sha256").jsonPrimitive.content == sha(specFile) && extraction.getValue("failures").jsonArray.isEmpty())
        val receipts = extraction.getValue("records").jsonArray.map { it.jsonObject }
        require(receipts.size == 16 && receipts.map { it.getValue("cache_path") }.distinct().size == 16)
        val ids = mapOf("squat" to "bodyweight_squat","push_up" to "push_up","bicep_curl" to "biceps_curl","shoulder" to "overhead_press")
        val spatial = SpatialSynchronizationEngine(SpatialSynchronizationConfig(includeWorkoutFeatures = true))
        val rows = mutableListOf<JsonObject>();val choices = mutableListOf<JsonObject>()
        for (entry in entries) {
            val name = entry.getValue("video").jsonPrimitive.content
            val coarseFile = File(root,entry.getValue("coarse_cache_path").jsonPrimitive.content)
            require(sha(coarseFile) == entry.getValue("coarse_cache_sha256").jsonPrimitive.content)
            val coarse = read(coarseFile).getValue("frames").jsonArray.map { it.jsonObject }
            val relative = "test-artifacts/exercise-knowledge/lite-reference-cache/${name.removeSuffix(".mp4")}.json.gz"
            val file = File(root,relative);val receipt = receipts.single { it.getValue("cache_path").jsonPrimitive.content == relative }
            require(sha(file) == receipt.getValue("cache_sha256").jsonPrimitive.content)
            val payload = read(file);LiteReferenceEvidence.identity(payload)
            require(payload.getValue("source_video_sha256") == entry.getValue("source_sha256") && payload.getValue("coarse_cache_sha256") == entry.getValue("coarse_cache_sha256"))
            require(payload.getValue("sample_period_ms").jsonPrimitive.int == 66 && payload.getValue("maximum_sampled_duration_ms") == JsonNull)
            val raw = payload.getValue("frames").jsonArray.map { it.jsonObject }
            require(raw.map { it.getValue("timestamp_ms") } == coarse.map { it.getValue("timestamp_ms") })
            raw.zip(coarse).forEach { (r,c) ->
                require(r.getValue("image_width") == c.getValue("image_width") && r.getValue("image_height") == c.getValue("image_height"))
                require(r.getValue("coarse_timestamp_ms") == r.getValue("timestamp_ms") && r.getValue("input_rgb_sha256").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
                val original = points(r,"raw_input_landmarks");LiteReferenceEvidence.raw(original)
                val bound = RotationReferenceMapping.sameSubject(points(c,"landmarks"),original)
                require(r.getValue("subject_bound").jsonPrimitive.boolean == bound)
                require(points(r,"landmarks") == if (bound) original else emptyList<List<Double>>())
                val confidence = points(r,"raw_world_visibility_presence")
                require(confidence.size == original.size && confidence.all { it.size == 2 && it.all { value -> value.isFinite() && value in 0.0..1.0 } })
            }
            val observations = raw.map(QualifiedRepReplayTool::observation)
            val sequence = spatial.analyzeSequence(CanonicalObservationSequence(VideoRole.REFERENCE,DurationMs(observations.last().timestamp.value+1),
                ObservationSampling(analysisFramesPerSecond = 15.0),observations))
            val fingerprint = RepEvidenceFingerprint(entry.getValue("source_sha256").jsonPrimitive.content,
                LiteReferenceEvidence.SHA,LiteReferenceEvidence.PIPELINE,payload.getValue("view").jsonPrimitive.content)
            val exercise = ids.getValue(payload.getValue("exercise").jsonPrimitive.content)
            val perReference = mutableListOf<JsonObject>()
            for (side in RepObservedSide.entries) for (fit in listOf(RepReferenceCurveFit.ORDINARY_LEAST_SQUARES,RepReferenceCurveFit.BOUNDED_MEDIAN_PAIRWISE)) {
                val result = QualifiedRepCompiler.compile(sequence,exercise,fingerprint,QualifiedRepConfig(side = side,referenceCurveFit = fit))
                val reference = (result as? QualifiedRepCompilation.Ready)?.reference
                perReference += buildJsonObject {
                    put("video",name);put("reference_video_sha256",fingerprint.referenceVideoSha256);put("exercise",exercise);put("side",side.name);put("view",fingerprint.declaredView)
                    put("fit",fit.name);put("model_sha256",LiteReferenceEvidence.SHA);put("preprocessing_id",LiteReferenceEvidence.PIPELINE);put("cache_sha256",sha(file))
                    put("status",if (reference != null) "READY" else (result as QualifiedRepCompilation.Unavailable).reason.name)
                    put("detail",(result as? QualifiedRepCompilation.Unavailable)?.detail ?: "Ready under unchanged gates")
                    if (reference != null) { put("reference_key",reference.referenceKey);put("selected_cycles",reference.calibration?.observedCycles ?: 0);put("self_accepted_cycles",reference.calibration?.acceptedSelfCycles ?: 0) }
                }
            }
            rows += perReference
            for (side in RepObservedSide.entries) {
                val sideRows = perReference.filter { it.getValue("side").jsonPrimitive.content == side.name }
                val established = old.getValue(key(sideRows.first()));val retained = established.getValue("selected_model").jsonPrimitive.content != "unavailable"
                val candidate = sideRows.firstOrNull { it.getValue("status").jsonPrimitive.content == "READY" }
                choices += buildJsonObject {
                    for (field in listOf("video","reference_video_sha256","exercise","side","view")) put(field,sideRows.first().getValue(field))
                    put("baseline_available",retained);put("established_selection",established)
                    put("selected_stage",if (retained) "KEEP_ESTABLISHED_BACKEND" else if (candidate != null) "RESEARCH_LITE" else "UNAVAILABLE")
                    put("lite_candidate",candidate ?: JsonNull)
                }
            }
        }
        require(rows.size == 64 && choices.size == 32)
        val report = buildJsonObject {
            put("schema","senpai-lite-reference-screen/1");put("protocol_sha256",sha(specFile));put("extraction_sha256",sha(extractionFile))
            put("baseline_usable",choices.count { it.getValue("baseline_available").jsonPrimitive.boolean })
            put("potential_additional_references",choices.count { it.getValue("selected_stage").jsonPrimitive.content == "RESEARCH_LITE" })
            put("established_choices_preserved",true);put("source_replay_performed",false);put("production_changed",false);put("human_rep_precision",JsonNull)
            put("rows",JsonArray(rows));put("choices",JsonArray(choices))
        }
        output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(),report))
        println("Lite reference screen: ${report.getValue("potential_additional_references")} potential new sides")
    }
}
