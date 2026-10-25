package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*

/** Fixed reference-only extraction screen; never reads source performance or skeletal holdouts. */
object DenseReferenceCalibrationAudit {
    private const val PIPELINE = "mp33-raw-world-dense33-research/1"
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    @JvmStatic fun main(args: Array<String>) {
        val directory = File(args[0]); val output = File(args[1]); val protocolFile = File(args[2]); val root = File(args[3])
        check(!output.exists()) { "Preserve the previous reference-only screen" }
        val protocol = Json.parseToJsonElement(protocolFile.readText()).jsonObject
        require(protocol.getValue("schema").jsonPrimitive.content == "senpai-dense-reference-research/1")
        require(protocol.getValue("reference_only").jsonPrimitive.boolean && !protocol.getValue("allow_final_test_access").jsonPrimitive.boolean)
        require(protocol.getValue("sample_period_ms").jsonPrimitive.int == 33 && protocol.getValue("maximum_duration_ms") == JsonNull)
        require(protocol.getValue("preprocessing_id").jsonPrimitive.content == PIPELINE)
        val receipts = protocol.getValue("source_receipts").jsonArray.map { it.jsonObject }
        for (receipt in receipts) require(sha(File(root,receipt.getValue("path").jsonPrimitive.content)) == receipt.getValue("sha256").jsonPrimitive.content) {
            "Frozen extraction/audit source changed"
        }
        val baselineFile = File(root,protocol.getValue("baseline_registry").jsonPrimitive.content)
        val baseline = Json.parseToJsonElement(baselineFile.readText()).jsonArray.map { it.jsonObject }
        require(baseline.size == 32)
        fun key(row: JsonObject) = listOf("reference_video_sha256","exercise","side","view").joinToString("|") { row.getValue(it).jsonPrimitive.content }
        val old = baseline.associateBy(::key)
        require(old.size == 32)
        val expected = protocol.getValue("references").jsonArray.map { it.jsonObject }
        require(expected.size == 16 && expected.map { it.getValue("video").jsonPrimitive.content }.distinct().size == 16)
        val files = requireNotNull(directory.listFiles()).filter { it.name.endsWith(".json.gz") }
        require(files.map { it.name }.toSet() == expected.map { it.getValue("video").jsonPrimitive.content.removeSuffix(".mp4")+".json.gz" }.toSet())
        val ids = mapOf("squat" to "bodyweight_squat","push_up" to "push_up","bicep_curl" to "biceps_curl","shoulder" to "overhead_press")
        val spatial = SpatialSynchronizationEngine(SpatialSynchronizationConfig(includeWorkoutFeatures = true))
        val rows = mutableListOf<JsonObject>(); val choices = mutableListOf<JsonObject>()
        for (entry in expected) {
            val name = entry.getValue("video").jsonPrimitive.content
            require(name.matches(Regex("subject_(001|005)_(squat|push_up|bicep_curl|shoulder)_good_(front|side)\\.mp4")))
            val cacheFile = File(directory,name.removeSuffix(".mp4")+".json.gz")
            val payload = GZIPInputStream(cacheFile.inputStream()).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            require(payload.getValue("video").jsonPrimitive.content == name)
            require(payload.getValue("source_video_sha256") == entry.getValue("source_sha256"))
            require(payload.getValue("model_sha256") == protocol.getValue("model_sha256"))
            require(payload.getValue("sample_period_ms").jsonPrimitive.int == 33 && payload.getValue("maximum_sampled_duration_ms") == JsonNull)
            val raw = payload.getValue("frames").jsonArray.map { it.jsonObject }
            require(raw.isNotEmpty() && raw.all { it.getValue("landmarks").jsonArray.size in setOf(0,33) })
            val observations = raw.map(QualifiedRepReplayTool::observation)
            require(observations.zipWithNext().all { (a,b) -> b.timestamp.value > a.timestamp.value })
            val sequence = spatial.analyzeSequence(CanonicalObservationSequence(VideoRole.REFERENCE,DurationMs(observations.last().timestamp.value+1),
                ObservationSampling(analysisFramesPerSecond = 1000.0/33),observations))
            val id = ids.getValue(payload.getValue("exercise").jsonPrimitive.content)
            val fingerprint = RepEvidenceFingerprint(entry.getValue("source_sha256").jsonPrimitive.content,
                payload.getValue("model_sha256").jsonPrimitive.content,PIPELINE,payload.getValue("view").jsonPrimitive.content)
            for (side in RepObservedSide.entries) {
                val candidates = listOf(RepReferenceCurveFit.ORDINARY_LEAST_SQUARES,RepReferenceCurveFit.BOUNDED_MEDIAN_PAIRWISE).map { fit ->
                    val result = QualifiedRepCompiler.compile(sequence,id,fingerprint,QualifiedRepConfig(side = side,referenceCurveFit = fit))
                    val reference = (result as? QualifiedRepCompilation.Ready)?.reference
                    buildJsonObject {
                        put("video",name);put("reference_video_sha256",fingerprint.referenceVideoSha256);put("exercise",id);put("side",side.name);put("view",fingerprint.declaredView)
                        put("fit",fit.name);put("status",if (reference != null) "READY" else (result as QualifiedRepCompilation.Unavailable).reason.name)
                        put("detail",(result as? QualifiedRepCompilation.Unavailable)?.detail ?: "Ready under unchanged production calibration gates")
                        put("cache_sha256",sha(cacheFile));put("observations",observations.size);put("first_pts_ms",observations.first().timestamp.value)
                        put("last_pts_ms",observations.last().timestamp.value);put("preprocessing_id",PIPELINE)
                        if (reference != null) {
                            put("reference_key",reference.referenceKey);put("selected_cycles",reference.calibration?.observedCycles ?: 0)
                            put("self_accepted_cycles",reference.calibration?.acceptedSelfCycles ?: 0)
                            put("self_acceptance_fraction",reference.calibration?.selfAcceptanceFraction ?: 0.0)
                            put("selected_first_ms",reference.calibration?.firstSelectedMs ?: 0);put("selected_last_ms",reference.calibration?.lastSelectedMs ?: 0)
                        }
                    }
                }
                rows += candidates
                val previous = old.getValue(key(candidates.first()))
                val retained = previous.getValue("selected_model").jsonPrimitive.content != "unavailable"
                val dense = candidates.firstOrNull { it.getValue("status").jsonPrimitive.content == "READY" }
                choices += buildJsonObject {
                    for (field in listOf("video","reference_video_sha256","exercise","side","view")) put(field,candidates.first().getValue(field))
                    put("baseline_available",retained)
                    put("selected_stage",if (retained) "KEEP_ESTABLISHED_BACKEND" else if (dense != null) "RESEARCH_DENSE_FULL" else "UNAVAILABLE")
                    put("established_selection",previous)
                    put("dense_candidate",dense ?: JsonNull)
                }
            }
        }
        require(choices.size == 32 && rows.size == 64)
        val report = buildJsonObject {
            put("schema","senpai-dense-reference-screen/1");put("kind","reference-extraction-screen-not-source-rep-precision-or-production-coverage")
            put("protocol_sha256",sha(protocolFile));put("compiler",QualifiedRepCompiler.VERSION);put("preprocessing_id",PIPELINE)
            put("baseline_usable",choices.count { it.getValue("baseline_available").jsonPrimitive.boolean })
            put("potential_additional_references",choices.count { it.getValue("selected_stage").jsonPrimitive.content == "RESEARCH_DENSE_FULL" })
            put("established_choices_preserved",true);put("source_replay_performed",false);put("production_changed",false)
            put("human_rep_precision",JsonNull);put("rows",JsonArray(rows));put("choices",JsonArray(choices))
        }
        output.parentFile.mkdirs();output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(),report))
        println("Dense reference-only screen: baseline ${report["baseline_usable"]}/32; potential additions ${report["potential_additional_references"]}; no source accuracy or readiness gain established")
    }
}
