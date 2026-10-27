package ai.senp.motion

import ai.senp.core.contracts.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*

/** One predeclared RGB activity proposal, not correctness-labelled rep evaluation. */
object MmfitReferenceCalibrationAudit {
    private fun sha(file: File, normalize: Boolean = false): String {
        val bytes = if (normalize) file.readText().replace("\r\n", "\n").toByteArray(Charsets.UTF_8) else file.readBytes()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0]);val directory = File(root,"test-artifacts/exercise-knowledge/mmfit")
        val protocolFile = File(root,"models/mmfit-reference-protocol.json")
        val protocol = Json.parseToJsonElement(protocolFile.readText()).jsonObject
        val output = File(directory,"reference-screen.json")
        check(!output.exists()) { "Preserve the completed screen" }
        require(protocol.getValue("schema").jsonPrimitive.content == "senpai-mmfit-reference/1")
        require(protocol.getValue("reference_only").jsonPrimitive.boolean)
        require(!protocol.getValue("allow_final_test_access").jsonPrimitive.boolean && !protocol.getValue("annotation_archive_access").jsonPrimitive.boolean)
        require(protocol.getValue("exercise").jsonPrimitive.content == "lateral_raise")
        require(protocol.getValue("selection").jsonPrimitive.content == "first-chronological-eligible-bout")
        for (element in protocol.getValue("source_receipts").jsonArray) {
            val receipt = element.jsonObject;val normalized = "sha256_lf" in receipt
            require(sha(File(root,receipt.getValue("path").jsonPrimitive.content),normalized) == receipt.getValue(if (normalized) "sha256_lf" else "sha256").jsonPrimitive.content)
        }
        val extraction = Json.parseToJsonElement(File(directory,"reference-extraction.json").readText()).jsonObject
        require(extraction.getValue("protocol_sha256").jsonPrimitive.content == sha(protocolFile))
        val rows = mutableListOf<JsonObject>()
        if (extraction.getValue("selected") != JsonNull) {
            val selected = extraction.getValue("selected").jsonObject
            require(selected == extraction.getValue("activity_proposals").jsonArray.first().jsonObject)
            val file = File(directory,"fine.json.gz")
            require(sha(file) == extraction.getValue("fine_sha256").jsonPrimitive.content)
            val payload = GZIPInputStream(file.inputStream()).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            require(payload.getValue("schema").jsonPrimitive.content == "mmfit-mp33-reference/1")
            require(payload.getValue("source_video_sha256") == protocol.getValue("video_sha256"))
            require(payload.getValue("model_sha256") == protocol.getValue("model_sha256"))
            require(payload.getValue("protocol_sha256").jsonPrimitive.content == sha(protocolFile))
            require(payload.getValue("interval") == selected && payload.getValue("sample_period_ms").jsonPrimitive.int == 66)
            require(payload.getValue("original_pts_preserved").jsonPrimitive.boolean && !payload.getValue("independent_form_labels").jsonPrimitive.boolean)
            val raw = payload.getValue("frames").jsonArray.map { it.jsonObject }
            verifyRaw(raw,selected.getValue("start_ms").jsonPrimitive.long,selected.getValue("end_ms").jsonPrimitive.long)
            val observations = raw.map(QualifiedRepReplayTool::observation)
            val sequence = SpatialSynchronizationEngine(SpatialSynchronizationConfig(includeWorkoutFeatures = true)).analyzeSequence(
                CanonicalObservationSequence(VideoRole.REFERENCE,DurationMs(observations.last().timestamp.value+1),
                    ObservationSampling(analysisFramesPerSecond = 15.0),observations))
            val identity = RepEvidenceFingerprint(protocol.getValue("video_sha256").jsonPrimitive.content,
                protocol.getValue("model_sha256").jsonPrimitive.content,declaredView = "MMFIT_FIXED_CAMERA_SEATED")
            for (side in RepObservedSide.entries) for (fit in listOf(RepReferenceCurveFit.ORDINARY_LEAST_SQUARES,RepReferenceCurveFit.BOUNDED_MEDIAN_PAIRWISE)) {
                val result = QualifiedRepCompiler.compile(sequence,"lateral_raise",identity,QualifiedRepConfig(side = side,referenceCurveFit = fit))
                val ready = (result as? QualifiedRepCompilation.Ready)?.reference
                rows += buildJsonObject {
                    put("exercise","lateral_raise");put("variant","seated-dumbbell-lateral-raise");put("side",side.name);put("fit",fit.name)
                    put("status",if (ready != null) "READY" else (result as QualifiedRepCompilation.Unavailable).reason.name)
                    put("detail",(result as? QualifiedRepCompilation.Unavailable)?.detail ?: "Ready under unchanged production gates")
                    put("observations",raw.size);put("raw_model_observations",raw.count { it.getValue("landmarks").jsonArray.isNotEmpty() })
                    if (ready != null) {
                        put("reference_key",ready.referenceKey);put("selected_cycles",ready.calibration?.observedCycles ?: 0)
                        put("self_accepted_cycles",ready.calibration?.acceptedSelfCycles ?: 0);put("self_acceptance_fraction",ready.calibration?.selfAcceptanceFraction ?: 0.0)
                        put("first_selected_ms",ready.calibration?.firstSelectedMs ?: 0);put("last_selected_ms",ready.calibration?.lastSelectedMs ?: 0)
                    }
                }
            }
        }
        val report = buildJsonObject {
            put("schema","mmfit-reference-screen/1");put("kind","camera-reference-calibration-not-independent-form-precision")
            put("protocol_sha256",sha(protocolFile));put("extraction_sha256",sha(File(directory,"reference-extraction.json")))
            put("selected",extraction.getValue("selected"));put("rows",JsonArray(rows))
            put("ready_sides",JsonArray(rows.filter { it.getValue("status").jsonPrimitive.content == "READY" }.map { it.getValue("side") }.distinct()))
            put("frozen_cohort_total",32);put("frozen_cohort_usable",12);put("frozen_cohort_changed",false)
            put("production_changed",false);put("independent_form_precision",JsonNull);put("final_test_access",false)
        }
        output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(),report))
        println("MM-Fit reference screen: ${report["ready_sides"]}; no human-rep precision claim")
    }

    internal fun verifyRaw(rows: List<JsonObject>, startMs: Long, endMs: Long) {
        require(rows.isNotEmpty() && rows.zipWithNext().all { (a,b) -> b.getValue("timestamp_ms").jsonPrimitive.long > a.getValue("timestamp_ms").jsonPrimitive.long })
        for (row in rows) {
            require(row.getValue("timestamp_ms").jsonPrimitive.long in startMs..endMs)
            val points = row.getValue("landmarks").jsonArray.map { it.jsonArray.map { n -> n.jsonPrimitive.double } }
            require(points.size in setOf(0,33) && points.all { p -> p.size == 8 && p.all(Double::isFinite) && p[3] in 0.0..1.0 && p[4] in 0.0..1.0 })
            val confidence = row.getValue("raw_world_visibility_presence").jsonArray
            require(confidence.size == points.size && confidence.all { p -> p.jsonArray.size == 2 && p.jsonArray.all { it.jsonPrimitive.double in 0.0..1.0 } })
        }
    }
}
