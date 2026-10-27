package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.serialization.json.*

/** Independent inverse mapping check. Raw metric/image evidence remains separately retained. */
internal object RotationReferenceMapping {
    fun mapped(raw: List<Double>, turn: Int, width: Int, height: Int): List<Double> {
        require(turn in setOf(90,270) && width > 0 && height > 0 && raw.size == 8 && raw.all(Double::isFinite))
        val (x,y,z) = raw
        return if (turn == 90) listOf(y,1-x,z*height/width,raw[3],raw[4],raw[6],-raw[5],raw[7])
        else listOf(1-y,x,z*height/width,raw[3],raw[4],-raw[6],raw[5],raw[7])
    }
    fun usable(points: List<List<Double>>): Boolean = points.size == 33 && points.all { it.size == 8 && it.all(Double::isFinite) } &&
        listOf(11,12,23,24).all { minOf(points[it][3],points[it][4]) >= .65 && points[it][0] in 0.0..1.0 && points[it][1] in 0.0..1.0 }
    fun sameSubject(coarse: List<List<Double>>, refined: List<List<Double>>): Boolean {
        if (!usable(coarse) || !usable(refined)) return false
        fun center(points: List<List<Double>>, indexes: List<Int>) = (0..1).map { axis -> indexes.map { points[it][axis] }.average() }
        val a = center(coarse,listOf(11,12,23,24));val b = center(refined,listOf(11,12,23,24))
        val shoulders = center(coarse,listOf(11,12));val hips = center(coarse,listOf(23,24))
        val torso = hypot(shoulders[0]-hips[0],shoulders[1]-hips[1])
        return torso > 0 && hypot(a[0]-b[0],a[1]-b[1]) <= maxOf(.05,.5*torso)
    }
}

/** Research references only; no source/holdout data or production selector mutation. */
object RotationReferenceCalibrationAudit {
    private fun sha(file: File, normalized: Boolean = false): String = MessageDigest.getInstance("SHA-256").digest(
        if (normalized) file.readText().replace("\r\n","\n").toByteArray(Charsets.UTF_8) else file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun read(file: File) = GZIPInputStream(file.inputStream()).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    private fun points(row: JsonObject,key: String) = row.getValue(key).jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.double } }
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0]);val specFile = File(root,"models/rotation-reference-protocol.json")
        val output = File(root,"test-artifacts/exercise-knowledge/rotation-reference-screen.json")
        check(!output.exists()) { "Preserve completed rotation screen" }
        val spec = Json.parseToJsonElement(specFile.readText()).jsonObject
        require(spec.getValue("schema").jsonPrimitive.content == "senpai-rotation-reference-protocol/1")
        require(spec.getValue("reference_only").jsonPrimitive.boolean && !spec.getValue("allow_final_test_access").jsonPrimitive.boolean)
        require(spec.getValue("clockwise_degrees").jsonArray.map { it.jsonPrimitive.int } == listOf(90,270))
        for (item in spec.getValue("receipts").jsonArray) {
            val r = item.jsonObject;val normalize = "sha256_lf" in r
            require(sha(File(root,r.getValue("path").jsonPrimitive.content),normalize) == r.getValue(if (normalize) "sha256_lf" else "sha256").jsonPrimitive.content)
        }
        val baseline = Json.parseToJsonElement(File(root,spec.getValue("baseline_registry").jsonPrimitive.content).readText()).jsonArray.map { it.jsonObject }
        fun key(r: JsonObject) = listOf("reference_video_sha256","exercise","side","view").joinToString("|") { r.getValue(it).jsonPrimitive.content }
        val established = baseline.associateBy(::key);require(baseline.size == 32 && established.size == 32)
        val entries = spec.getValue("references").jsonArray.map { it.jsonObject }
        require(entries.size == 16 && entries.map { it.getValue("video") }.distinct().size == 16)
        // Scope check before candidate/coarse video data is opened.
        require(entries.all { it.getValue("video").jsonPrimitive.content.matches(Regex("subject_(001|005)_(squat|push_up|bicep_curl|shoulder)_good_(front|side)\\.mp4")) })
        val extractionFile = File(root,"test-artifacts/exercise-knowledge/rotation-reference-extraction.json")
        val extraction = Json.parseToJsonElement(extractionFile.readText()).jsonObject
        require(extraction.getValue("protocol_sha256").jsonPrimitive.content == sha(specFile) && extraction.getValue("failures").jsonArray.isEmpty())
        val receipts = extraction.getValue("records").jsonArray.map { it.jsonObject }
        require(receipts.size == 32 && receipts.map { it.getValue("cache_path") }.distinct().size == 32)
        val ids = mapOf("squat" to "bodyweight_squat","push_up" to "push_up","bicep_curl" to "biceps_curl","shoulder" to "overhead_press")
        val spatial = SpatialSynchronizationEngine(SpatialSynchronizationConfig(includeWorkoutFeatures = true))
        val rows = mutableListOf<JsonObject>();val choices = mutableListOf<JsonObject>()
        for (entry in entries) {
            val name = entry.getValue("video").jsonPrimitive.content
            val coarseFile = File(root,entry.getValue("coarse_cache_path").jsonPrimitive.content)
            require(sha(coarseFile) == entry.getValue("coarse_cache_sha256").jsonPrimitive.content)
            val coarse = read(coarseFile).getValue("frames").jsonArray.map { it.jsonObject }
            val perReference = mutableListOf<JsonObject>()
            for (turn in listOf(90,270)) {
                val relative = "test-artifacts/exercise-knowledge/rotation-reference-cw$turn-cache/${name.removeSuffix(".mp4")}.json.gz"
                val file = File(root,relative);val receipt = receipts.single { it.getValue("cache_path").jsonPrimitive.content == relative }
                require(sha(file) == receipt.getValue("cache_sha256").jsonPrimitive.content)
                val payload = read(file);val pipeline = "mp33-coarse-bound-cw$turn-image/1"
                require(payload.getValue("schema").jsonPrimitive.content == "mp33-quarter-turn-reference/1" && payload.getValue("preprocessing_id").jsonPrimitive.content == pipeline)
                require(payload.getValue("source_video_sha256") == entry.getValue("source_sha256") && payload.getValue("model_sha256") == spec.getValue("model_sha256"))
                require(payload.getValue("coarse_cache_sha256") == entry.getValue("coarse_cache_sha256"))
                require(payload.getValue("sample_period_ms").jsonPrimitive.int == 66 && payload.getValue("maximum_sampled_duration_ms") == JsonNull)
                val raw = payload.getValue("frames").jsonArray.map { it.jsonObject }
                require(raw.map { it.getValue("timestamp_ms") } == coarse.map { it.getValue("timestamp_ms") })
                raw.zip(coarse).forEach { (r,c) ->
                    val width = r.getValue("image_width").jsonPrimitive.int;val height = r.getValue("image_height").jsonPrimitive.int
                    require(r.getValue("image_width") == c.getValue("image_width") && r.getValue("image_height") == c.getValue("image_height"))
                    require(r.getValue("rotated_width").jsonPrimitive.int == height && r.getValue("rotated_height").jsonPrimitive.int == width)
                    require(r.getValue("clockwise_degrees").jsonPrimitive.int == turn && r.getValue("coarse_timestamp_ms") == r.getValue("timestamp_ms"))
                    val original = points(r,"raw_input_landmarks");val canonical = points(r,"landmarks");val coarsePoints = points(c,"landmarks")
                    require(original.isEmpty() || original.size == 33)
                    val expected = original.map { RotationReferenceMapping.mapped(it,turn,width,height) }
                    val bound = RotationReferenceMapping.sameSubject(coarsePoints,expected)
                    require(r.getValue("subject_bound").jsonPrimitive.boolean == bound)
                    require(canonical.size == if (bound) 33 else 0)
                    if (bound) require(canonical.zip(expected).all { (a,b) -> a.size == 8 && a.zip(b).all { (x,y) -> abs(x-y) <= 1e-10 } })
                    if (!RotationReferenceMapping.usable(coarsePoints)) require(original.isEmpty())
                    if (RotationReferenceMapping.usable(coarsePoints)) require(r.getValue("input_rgb_sha256").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
                    val confidence = points(r,"raw_world_visibility_presence")
                    require(confidence.size == original.size && confidence.all { it.size == 2 && it.all { value -> value.isFinite() && value in 0.0..1.0 } })
                    require(original.all { it[3] in 0.0..1.0 && it[4] in 0.0..1.0 })
                }
                val observations = raw.map(QualifiedRepReplayTool::observation)
                val sequence = spatial.analyzeSequence(CanonicalObservationSequence(VideoRole.REFERENCE,DurationMs(observations.last().timestamp.value+1),
                    ObservationSampling(analysisFramesPerSecond = 15.0),observations))
                val fingerprint = RepEvidenceFingerprint(entry.getValue("source_sha256").jsonPrimitive.content,
                    payload.getValue("model_sha256").jsonPrimitive.content,pipeline,payload.getValue("view").jsonPrimitive.content)
                val exercise = ids.getValue(payload.getValue("exercise").jsonPrimitive.content)
                for (side in RepObservedSide.entries) for (fit in listOf(RepReferenceCurveFit.ORDINARY_LEAST_SQUARES,RepReferenceCurveFit.BOUNDED_MEDIAN_PAIRWISE)) {
                    val result = QualifiedRepCompiler.compile(sequence,exercise,fingerprint,QualifiedRepConfig(side = side,referenceCurveFit = fit))
                    val ready = (result as? QualifiedRepCompilation.Ready)?.reference
                    perReference += buildJsonObject {
                        put("video",name);put("reference_video_sha256",fingerprint.referenceVideoSha256);put("exercise",exercise);put("side",side.name);put("view",fingerprint.declaredView)
                        put("clockwise_degrees",turn);put("fit",fit.name);put("preprocessing_id",pipeline);put("cache_sha256",sha(file))
                        put("status",if (ready != null) "READY" else (result as QualifiedRepCompilation.Unavailable).reason.name)
                        put("detail",(result as? QualifiedRepCompilation.Unavailable)?.detail ?: "Ready under unchanged gates")
                        if (ready != null) { put("reference_key",ready.referenceKey);put("selected_cycles",ready.calibration?.observedCycles ?: 0);put("self_accepted_cycles",ready.calibration?.acceptedSelfCycles ?: 0) }
                    }
                }
            }
            rows += perReference
            for (side in RepObservedSide.entries) {
                val sideRows = perReference.filter { it.getValue("side").jsonPrimitive.content == side.name }
                val old = established.getValue(key(sideRows.first()));val retained = old.getValue("selected_model").jsonPrimitive.content != "unavailable"
                val ranked = sideRows.sortedWith(compareBy({ if (it.getValue("fit").jsonPrimitive.content == "ORDINARY_LEAST_SQUARES") 0 else 1 },{ it.getValue("clockwise_degrees").jsonPrimitive.int }))
                val candidate = ranked.firstOrNull { it.getValue("status").jsonPrimitive.content == "READY" }
                choices += buildJsonObject {
                    for (field in listOf("video","reference_video_sha256","exercise","side","view")) put(field,sideRows.first().getValue(field))
                    put("baseline_available",retained);put("established_selection",old)
                    put("selected_stage",if (retained) "KEEP_ESTABLISHED_BACKEND" else if (candidate != null) "RESEARCH_ROTATION" else "UNAVAILABLE")
                    put("rotation_candidate",candidate ?: JsonNull)
                }
            }
        }
        require(rows.size == 128 && choices.size == 32)
        val report = buildJsonObject {
            put("schema","senpai-rotation-reference-screen/1");put("protocol_sha256",sha(specFile));put("extraction_sha256",sha(extractionFile))
            put("baseline_usable",choices.count { it.getValue("baseline_available").jsonPrimitive.boolean })
            put("potential_additional_references",choices.count { it.getValue("selected_stage").jsonPrimitive.content == "RESEARCH_ROTATION" })
            put("established_choices_preserved",true);put("source_replay_performed",false);put("production_changed",false);put("human_rep_precision",JsonNull)
            put("rows",JsonArray(rows));put("choices",JsonArray(choices))
        }
        output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(),report))
        println("Quarter-turn reference screen: ${report.getValue("potential_additional_references")} potential new sides")
    }
}
