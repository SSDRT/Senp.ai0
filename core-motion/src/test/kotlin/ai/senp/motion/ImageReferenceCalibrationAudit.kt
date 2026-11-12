package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlinx.serialization.json.*

/** Reference-only screen. Verifies raw/crop provenance before compiling unchanged production policies. */
object ImageReferenceCalibrationAudit {
    private val pipelines = mapOf("full_image" to "mp33-coarse-bound-full-image/1","coarse_roi_image" to "mp33-coarse-bound-roi-image/1")
    private fun sha(file: File, normalize: Boolean = false): String {
        val bytes = if (normalize) file.readText().replace("\r\n","\n").toByteArray(Charsets.UTF_8) else file.readBytes()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    private fun read(file: File) = GZIPInputStream(file.inputStream()).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0]);val protocolFile = File(root,"models/image-reference-extraction-protocol.json")
        val output = File(root,"test-artifacts/exercise-knowledge/image-reference-screen.json")
        check(!output.exists()) { "Preserve the completed screen" }
        val protocol = Json.parseToJsonElement(protocolFile.readText()).jsonObject
        require(protocol.getValue("schema").jsonPrimitive.content == "senpai-image-reference-research/1")
        require(protocol.getValue("reference_only").jsonPrimitive.boolean && !protocol.getValue("allow_final_test_access").jsonPrimitive.boolean)
        require(protocol.getValue("sample_period_ms").jsonPrimitive.int == 66 && protocol.getValue("maximum_duration_ms") == JsonNull)
        for ((variant,pipeline) in pipelines) require(protocol.getValue("pipelines").jsonObject.getValue(variant).jsonPrimitive.content == pipeline)
        for (receipt in protocol.getValue("source_receipts").jsonArray.map { it.jsonObject }) {
            val normalized = "sha256_lf" in receipt
            require(sha(File(root,receipt.getValue("path").jsonPrimitive.content),normalized) == receipt.getValue(if (normalized) "sha256_lf" else "sha256").jsonPrimitive.content)
        }
        val baseline = Json.parseToJsonElement(File(root,protocol.getValue("baseline_registry").jsonPrimitive.content).readText()).jsonArray.map { it.jsonObject }
        fun key(row: JsonObject) = listOf("reference_video_sha256","exercise","side","view").joinToString("|") { row.getValue(it).jsonPrimitive.content }
        val old = baseline.associateBy(::key)
        require(old.size == 32 && baseline.size == 32)
        val references = protocol.getValue("references").jsonArray.map { it.jsonObject }
        require(references.size == 16 && references.map { it.getValue("video") }.distinct().size == 16)
        for (variant in pipelines.keys) {
            val directory = File(root,"test-artifacts/exercise-knowledge/image-reference-$variant-cache")
            val names = requireNotNull(directory.listFiles()).filter { it.name.endsWith(".json.gz") }.map { it.name }.toSet()
            require(names == references.map { it.getValue("video").jsonPrimitive.content.removeSuffix(".mp4")+".json.gz" }.toSet())
        }
        val ids = mapOf("squat" to "bodyweight_squat","push_up" to "push_up","bicep_curl" to "biceps_curl","shoulder" to "overhead_press")
        val spatial = SpatialSynchronizationEngine(SpatialSynchronizationConfig(includeWorkoutFeatures = true))
        val rows = mutableListOf<JsonObject>();val choices = mutableListOf<JsonObject>()
        for (entry in references) {
            val name = entry.getValue("video").jsonPrimitive.content
            require(name.matches(Regex("subject_(001|005)_(squat|push_up|bicep_curl|shoulder)_good_(front|side)\\.mp4")))
            val coarseFile = File(root,entry.getValue("coarse_cache_path").jsonPrimitive.content)
            require(sha(coarseFile) == entry.getValue("coarse_cache_sha256").jsonPrimitive.content)
            val coarse = read(coarseFile).getValue("frames").jsonArray.map { it.jsonObject }
            val perReference = mutableListOf<JsonObject>()
            for ((variant,pipeline) in pipelines) {
                val file = File(root,"test-artifacts/exercise-knowledge/image-reference-$variant-cache/${name.removeSuffix(".mp4")}.json.gz")
                val payload = read(file)
                require(payload.getValue("schema").jsonPrimitive.content == "mp33-image-refinement-research/1")
                require(payload.getValue("preprocessing_id").jsonPrimitive.content == pipeline)
                require(payload.getValue("source_video_sha256") == entry.getValue("source_sha256") && payload.getValue("model_sha256") == protocol.getValue("model_sha256"))
                require(payload.getValue("coarse_cache_sha256") == entry.getValue("coarse_cache_sha256"))
                require(payload.getValue("sample_period_ms").jsonPrimitive.int == 66 && payload.getValue("maximum_sampled_duration_ms") == JsonNull)
                val raw = payload.getValue("frames").jsonArray.map { it.jsonObject }
                require(raw.map { it.getValue("timestamp_ms") } == coarse.map { it.getValue("timestamp_ms") })
                raw.forEach(::verifyMapping)
                val observations = raw.map(QualifiedRepReplayTool::observation)
                val sequence = spatial.analyzeSequence(CanonicalObservationSequence(VideoRole.REFERENCE,DurationMs(observations.last().timestamp.value+1),
                    ObservationSampling(analysisFramesPerSecond = 15.0),observations))
                val identity = RepEvidenceFingerprint(entry.getValue("source_sha256").jsonPrimitive.content,
                    payload.getValue("model_sha256").jsonPrimitive.content,pipeline,payload.getValue("view").jsonPrimitive.content)
                val id = ids.getValue(payload.getValue("exercise").jsonPrimitive.content)
                for (side in RepObservedSide.entries) for (fit in listOf(RepReferenceCurveFit.ORDINARY_LEAST_SQUARES,RepReferenceCurveFit.BOUNDED_MEDIAN_PAIRWISE)) {
                    val result = QualifiedRepCompiler.compile(sequence,id,identity,QualifiedRepConfig(side = side,referenceCurveFit = fit))
                    val reference = (result as? QualifiedRepCompilation.Ready)?.reference
                    perReference += buildJsonObject {
                        put("video",name);put("reference_video_sha256",identity.referenceVideoSha256);put("exercise",id);put("side",side.name);put("view",identity.declaredView)
                        put("variant",variant);put("fit",fit.name);put("preprocessing_id",pipeline);put("cache_sha256",sha(file))
                        put("status",if (reference != null) "READY" else (result as QualifiedRepCompilation.Unavailable).reason.name)
                        put("detail",(result as? QualifiedRepCompilation.Unavailable)?.detail ?: "Ready under unchanged calibration gates")
                        put("observations",raw.size);put("admitted_observations",raw.count { it.getValue("subject_bound").jsonPrimitive.boolean })
                        if (reference != null) {
                            put("reference_key",reference.referenceKey);put("selected_cycles",reference.calibration?.observedCycles ?: 0)
                            put("self_accepted_cycles",reference.calibration?.acceptedSelfCycles ?: 0);put("self_acceptance_fraction",reference.calibration?.selfAcceptanceFraction ?: 0.0)
                            put("selected_first_ms",reference.calibration?.firstSelectedMs ?: 0);put("selected_last_ms",reference.calibration?.lastSelectedMs ?: 0)
                        }
                    }
                }
            }
            rows += perReference
            for (side in RepObservedSide.entries) {
                val sideRows = perReference.filter { it.getValue("side").jsonPrimitive.content == side.name }
                val previous = old.getValue(key(sideRows.first()))
                val retained = previous.getValue("selected_model").jsonPrimitive.content != "unavailable"
                val ranked = sideRows.sortedWith(compareBy({ if (it.getValue("fit").jsonPrimitive.content == "ORDINARY_LEAST_SQUARES") 0 else 1 },
                    { if (it.getValue("variant").jsonPrimitive.content == "full_image") 0 else 1 }))
                val candidate = ranked.firstOrNull { it.getValue("status").jsonPrimitive.content == "READY" }
                choices += buildJsonObject {
                    for (field in listOf("video","reference_video_sha256","exercise","side","view")) put(field,sideRows.first().getValue(field))
                    put("baseline_available",retained);put("established_selection",previous)
                    put("selected_stage",if (retained) "KEEP_ESTABLISHED_BACKEND" else if (candidate != null) "RESEARCH_IMAGE" else "UNAVAILABLE")
                    put("image_candidate",candidate ?: JsonNull)
                }
            }
        }
        require(rows.size == 128 && choices.size == 32)
        val report = buildJsonObject {
            put("schema","senpai-image-reference-screen/1");put("kind","reference-only-calibration-not-source-precision-or-production-coverage")
            put("protocol_sha256",sha(protocolFile));put("baseline_usable",choices.count { it.getValue("baseline_available").jsonPrimitive.boolean })
            put("potential_additional_references",choices.count { it.getValue("selected_stage").jsonPrimitive.content == "RESEARCH_IMAGE" })
            put("established_choices_preserved",true);put("source_replay_performed",false);put("production_changed",false);put("human_rep_precision",JsonNull)
            put("rows",JsonArray(rows));put("choices",JsonArray(choices))
        }
        output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(),report))
        println("IMAGE reference-only screen: baseline ${report["baseline_usable"]}/32; potential additions ${report["potential_additional_references"]}; no precision claim")
    }

    internal fun verifyMapping(row: JsonObject) {
        require(row.getValue("timestamp_ms") == row.getValue("coarse_timestamp_ms"))
        val raw = row.getValue("raw_input_landmarks").jsonArray.map { it.jsonArray.map { n -> n.jsonPrimitive.double } }
        val mapped = row.getValue("landmarks").jsonArray.map { it.jsonArray.map { n -> n.jsonPrimitive.double } }
        val worldConfidence = row.getValue("raw_world_visibility_presence").jsonArray
        require(raw.size in setOf(0,33) && mapped.size in setOf(0,33))
        require(worldConfidence.size == raw.size && worldConfidence.all { it.jsonArray.size == 2 })
        require((raw+mapped).all { p -> p.size == 8 && p.all(Double::isFinite) })
        require(row.getValue("subject_bound").jsonPrimitive.boolean == mapped.isNotEmpty())
        if (mapped.isEmpty()) return
        require(raw.size == 33)
        val bounds = row.getValue("crop_bounds_xyxy").jsonArray.map { it.jsonPrimitive.int }
        val width = row.getValue("image_width").jsonPrimitive.int;val height = row.getValue("image_height").jsonPrimitive.int
        require(bounds.size == 4 && bounds[0] >= 0 && bounds[1] >= 0 && bounds[2] <= width && bounds[3] <= height)
        require(bounds[2] > bounds[0] && bounds[3] > bounds[1])
        for ((a,b) in raw.zip(mapped)) {
            require(abs(b[0]-(bounds[0]+a[0]*(bounds[2]-bounds[0]))/width) <= 1e-12)
            require(abs(b[1]-(bounds[1]+a[1]*(bounds[3]-bounds[1]))/height) <= 1e-12)
            require(abs(b[2]-a[2]*(bounds[2]-bounds[0])/width) <= 1e-12)
            require(a.subList(3,8) == b.subList(3,8)) { "World/confidence data changed during image-coordinate mapping" }
        }
    }
}
